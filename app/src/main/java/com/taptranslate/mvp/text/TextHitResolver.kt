package com.taptranslate.mvp.text

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.taptranslate.mvp.settings.Granularity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 点击 → 词命中解析（免测量方案，依赖 API 29+ 的逐字符坐标）。
 *
 * 流程：选窗口 → DFS 命中最深文本节点 → refreshWithExtraData 取逐字符 RectF
 *       → 分词合并矩形 → 命中测试 → 提取被点词所在整句作为上下文。
 */
object TextHitResolver {

    private const val TAG = "TextHit"

    /** 最近一次落空的原因提示（resolve 返回 null 时由服务读取），仅主线程使用 */
    var lastMissHint: String? = null
        private set

    data class Hit(
        val word: String,
        val wordRect: RectF,
        val contextSentence: String,
        val nodeText: String,
        val nodeRect: RectF,
        val isFallback: Boolean,
    )

    private data class NodeSnapshot(
        val text: String,
        val bounds: RectF,
        val charRects: List<RectF>?,
        val nodeClass: String?,
    )

    private data class WordSpan(
        val text: String,
        val charStart: Int,
        val charEnd: Int,   // exclusive
        val rect: RectF,
    )

    fun resolve(service: AccessibilityService, x: Float, y: Float, granularity: Granularity): Hit? {
        val xi = x.toInt()
        val yi = y.toInt()

        var snap = findNodeSnapshot(service, xi, yi)
        if (snap == null) {
            Log.d(TAG, "resolve($x,$y): no text node at point")
            lastMissHint = null
            return null
        }
        val rects = snap.charRects
        val distinct = rects?.distinct()?.size
        Log.d(
            TAG,
            "resolve($x,$y): node='${snap.text.take(50)}' class=${snap.nodeClass} " +
                "bounds=${snap.bounds} charRects=${rects?.size}/${snap.text.length} " +
                "distinct=$distinct first=${rects?.firstOrNull()}",
        )
        if (snap.text.isBlank()) return null

        // 浏览器节点"存根化"（refresh 成功但 extras 空）：重新取窗口根再走一遍，
        // 强制无障碍树重新provision，往往就能拿到逐字符坐标
        if (rects == null && lastRectsStubbed && !isWebViewNode(snap.nodeClass)) {
            Log.d(TAG, "charRects stubbed, re-walking window hierarchy once")
            forceRefreshWindows(service)
            snap = findNodeSnapshot(service, xi, yi) ?: snap
            Log.d(
                TAG,
                "re-walk: node='${snap.text.take(50)}' charRects=${snap.charRects?.size}",
            )
        }

        // 逐字符矩形有效性校验：WebView 返回常量矩形时无法切词，直接按未识别处理，
        // 避免把"夸克-新生代智能搜索"这类浏览器标语当成被点词、画一条全屏宽虚线
        val reliable = snap.charRects != null && rectsReliable(snap.charRects!!, snap.bounds)
        if (!reliable && isWebViewNode(snap.nodeClass)) {
            Log.d(TAG, "WebView 未暴露逐字符坐标（常量矩形），按未识别处理")
            lastMissHint = "该应用未暴露网页文字，试试用 Chrome 打开"
            return null
        }
        lastMissHint = null
        val effectiveSnap = if (reliable) snap else snap.copy(charRects = null)

        val spans = wordSpans(effectiveSnap, granularity)
        if (spans.isEmpty()) {
            Log.d(TAG, "resolve($x,$y): no word spans, fallback to whole node")
            // 降级：整节点作为查询文本
            val text = snap.text.trim()
            return Hit(
                word = text,
                wordRect = snap.bounds,
                contextSentence = sentenceContaining(snap.text, 0, snap.text.length),
                nodeText = snap.text,
                nodeRect = snap.bounds,
                isFallback = true,
            )
        }

        val tapped = spans.minByOrNull { distanceTo(it.rect, x, y) } ?: return null
        Log.d(
            TAG,
            "tapped='${tapped.text}' rect=${tapped.rect} nodeBounds=${snap.bounds} " +
                "firstCharRect=${snap.charRects?.first()} lastCharRect=${snap.charRects?.last()}",
        )
        return Hit(
            word = tapped.text,
            wordRect = tapped.rect,
            contextSentence = sentenceContaining(snap.text, tapped.charStart, tapped.charEnd),
            nodeText = snap.text,
            nodeRect = snap.bounds,
            isFallback = false,
        )
    }

    /** 选窗口 → DFS → 快照（recycle 全部中间节点） */
    private fun findNodeSnapshot(service: AccessibilityService, xi: Int, yi: Int): NodeSnapshot? {
        // 第一优先：应用窗口；跳过 ACCESSIBILITY_OVERLAY（包括我们自己的 overlay，
        // 否则永远命中自己这棵无文本的树）
        var root: AccessibilityNodeInfo? = null
        val windows = service.windows
        for (i in 0 until windows.size) {
            val w = windows[i]
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = Rect()
            w.getBoundsInScreen(r)
            if (r.contains(xi, yi)) {
                root = w.root
                break
            }
        }
        // 兜底：任意包含点的非 overlay 窗口
        if (root == null) {
            for (i in 0 until windows.size) {
                val w = windows[i]
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val r = Rect()
                w.getBoundsInScreen(r)
                if (r.contains(xi, yi)) {
                    root = w.root
                    break
                }
            }
        }
        if (root == null) {
            Log.d(TAG, "no window contains point, windows=${windows.map { it.type }}")
            return null
        }
        val node = deepestTextNodeAt(root, xi, yi) ?: return null
        val snap = snapshot(node)
        node.recycle()
        return snap
    }

    /** 重新请求各应用窗口的根节点，促使无障碍树重新 provision（应对存根化） */
    private fun forceRefreshWindows(service: AccessibilityService) {
        for (w in service.windows) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                w.root?.recycle()
            }
        }
    }

    /** DFS 找到包含点击点、text 非空、深度最大的节点（拷贝返回，调用方负责 recycle） */
    private fun deepestTextNodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestDepth = -1
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.contains(x, y)) {
                if (!node.text.isNullOrEmpty() && depth > bestDepth) {
                    best?.recycle()
                    best = AccessibilityNodeInfo.obtain(node)
                    bestDepth = depth
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it to depth + 1) }
                }
            }
            node.recycle()
        }
        return best
    }

    /** 从活节点提取全部需要的数据（之后即可 recycle） */
    private fun snapshot(node: AccessibilityNodeInfo): NodeSnapshot {
        val text = node.text?.toString() ?: ""
        val r = Rect()
        node.getBoundsInScreen(r)
        return NodeSnapshot(
            text,
            RectF(r),
            charRects(node),
            node.className?.toString(),
        )
    }

    /**
     * 逐字符矩形是否可信。
     * 部分 WebView 会"撒谎"：对 refreshWithExtraData 返回与节点整边界相同的常量矩形
     * （实测夸克 10 个字符全部返回全屏矩形），据此切词会把整节点当成一个词。
     */
    private fun rectsReliable(rects: List<RectF>, nodeBounds: RectF): Boolean {
        if (rects.size < 2) return false
        val first = rects.first()
        if (rects.all { it == first }) return false
        val asBigAsNode = rects.count {
            it.width() >= nodeBounds.width() * 0.9f && it.height() >= nodeBounds.height() * 0.9f
        }
        return asBigAsNode < rects.size / 2
    }

    private fun isWebViewNode(nodeClass: String?): Boolean =
        nodeClass?.contains("WebView", ignoreCase = true) == true

    /** 最近一次 charRects 是否为"存根"失败（refresh 返回 true 但 extras 无数据），重走判定用 */
    private var lastRectsStubbed = false
        private set

    /** API 29+：逐字符屏幕坐标；失败（自绘节点等）返回 null，走降级路径。
     *  实测部分浏览器节点会"存根化"：refreshWithExtraData 返回 true 但 extras 无数据，
     *  先 refresh 再请求，并最多重试 3 次 */
    private fun charRects(node: AccessibilityNodeInfo): List<RectF>? {
        val len = node.text?.length ?: 0
        if (len == 0) return null
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
                min(len, AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH)
            )
        }
        node.refresh()
        for (attempt in 0..2) {
            if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) {
                lastRectsStubbed = false
                Log.d(TAG, "charRects: refreshWithExtraData false (attempt $attempt), " +
                    "available=${node.availableExtraData}")
                return null
            }
            val arr = node.extras.getParcelableArray(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
            )
            if (arr != null) {
                lastRectsStubbed = false
                return arr.mapNotNull { (it as? RectF)?.let { rf -> RectF(rf) } }
                    .takeIf { it.isNotEmpty() }
            }
            lastRectsStubbed = true
            Log.d(TAG, "charRects: extras null after refresh (attempt $attempt), " +
                "available=${node.availableExtraData}")
            node.refresh()
        }
        return null
    }

    private fun wordSpans(snap: NodeSnapshot, granularity: Granularity): List<WordSpan> {
        val text = snap.text
        val rects = snap.charRects
        if (rects == null || rects.size != text.length) return emptyList()

        // 1) 按词字符切 run，再按语言分词
        val base = mutableListOf<WordSpan>()
        var i = 0
        while (i < text.length) {
            if (!Segmenter.isWordChar(text[i])) { i++; continue }
            val start = i
            while (i < text.length && Segmenter.isWordChar(text[i])) i++
            val run = text.substring(start, i)
            for (rel in Segmenter.segment(run, granularity)) {
                val cs = start + rel.first
                val ce = start + rel.last + 1
                val r = RectF()
                for (k in cs until ce) r.union(rects[k])
                base.add(WordSpan(text.substring(cs, ce), cs, ce, r))
            }
        }

        // 2) 短语级：相邻词合并启发式（间隔 < 0.8 倍行高，最多合并 3 个词）
        return if (granularity == Granularity.PHRASE) mergeIntoPhrases(base) else base
    }

    private fun mergeIntoPhrases(spans: List<WordSpan>): List<WordSpan> {
        if (spans.isEmpty()) return spans
        val out = mutableListOf<WordSpan>()
        var cur = spans.first()
        var count = 1
        for (next in spans.drop(1)) {
            val gap = next.rect.left - cur.rect.right
            val lineHeight = max(cur.rect.height(), 1f)
            if (count < 3 && gap >= -2f && gap <= lineHeight * 0.8f) {
                val joiner = if (needsSpace(cur.text, next.text)) " " else ""
                cur = WordSpan(
                    cur.text + joiner + next.text,
                    cur.charStart,
                    next.charEnd,
                    RectF(cur.rect).apply { union(next.rect) },
                )
                count++
            } else {
                out.add(cur)
                cur = next
                count = 1
            }
        }
        out.add(cur)
        return out
    }

    private fun needsSpace(a: String, b: String): Boolean {
        val cjk = 0x4E00..0x9FFF
        return a.last().code !in cjk && b.first().code !in cjk
    }

    private fun distanceTo(r: RectF, x: Float, y: Float): Float {
        val dx = when {
            x < r.left -> r.left - x
            x > r.right -> x - r.right
            else -> 0f
        }
        val dy = when {
            y < r.top -> r.top - y
            y > r.bottom -> y - r.bottom
            else -> 0f
        }
        return sqrt(dx * dx + dy * dy)
    }

    /** 提取覆盖 [start, end) 的整句（按中英文句末标点切分） */
    private fun sentenceContaining(text: String, start: Int, end: Int): String {
        val boundaries = setOf('.', '!', '?', '。', '！', '？', '\n')
        var s = start
        while (s > 0 && text[s - 1] !in boundaries) s--
        var e = end
        while (e < text.length && text[e] !in boundaries) e++
        if (e < text.length) e++ // 带上句末标点
        return text.substring(s, e).trim()
    }
}
