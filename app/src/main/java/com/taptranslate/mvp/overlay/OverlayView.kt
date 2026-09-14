package com.taptranslate.mvp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.taptranslate.mvp.text.TextHitResolver
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * overlay 内容视图，自绘全部内容（虚线 + 悬浮小卡片）：
 *  - 点击：回调 onWordTap
 *  - 滑动：交给 forwarder 回注给底层 App
 *  - 虚线：只在命中真实字词时画在词下方（落空不画）
 *  - 悬浮小卡片：锚定词上方；靠近屏幕顶部自动翻到词下方；水平方向钳制屏内
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    private val onWordTap: (Float, Float) -> Unit,
    private val forwarder: GestureForwarder,
) : View(context) {

    interface GestureForwarder {
        fun begin(ev: MotionEvent)
        fun move(ev: MotionEvent)
        fun end(ev: MotionEvent)
        /** 判定为滑动的瞬间调用：overlay 应立即设为 NOT_TOUCHABLE，
         *  否则 dispatchGesture 的回注事件会作为新手势再次派发进 overlay 形成无限回环 */
        fun onForwardingStart()
    }

    private data class Annotation(
        val word: String,
        val translation: String,
        val rect: RectF,
        val isMiss: Boolean = false,
    )

    private var annotation: Annotation? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var forwarding = false

    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2212B3B")
    }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val wordPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
    }
    private val transPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDE3EA")
        textSize = 30f
    }

    private val cardPadding = 24f
    private val cardGap = 16f
    private val cardMargin = 16f
    private val wordLineHeight = 40f

    // ==================== 触摸判定：轻点 vs 滑动 ====================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 视图坐标 → 屏幕坐标：部分 ROM 会给 overlay 内容施加系统栏 insets 偏移
        //（实测 ColorOS 偏移 ≈ 状态栏高度），必须补回，否则点词坐标和手势回注全部错位
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        event.offsetLocation(loc[0].toFloat(), loc[1].toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 任何按下都先清掉上一个卡片（点击卡片外消失）
                clearTransient()
                forwarding = false
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                forwarder.begin(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!forwarding && hypot(
                        (event.x - downX).toDouble(),
                        (event.y - downY).toDouble()
                    ) > touchSlop
                ) {
                    forwarding = true
                    // 关键：回注前先把 overlay 设为不可触摸，
                    // 否则回注手势会作为新手势再次派发进本 overlay 形成无限回环
                    forwarder.onForwardingStart()
                }
                if (forwarding) forwarder.move(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (forwarding) {
                    forwarder.end(event)
                } else if (event.eventTime - downTime <= 500L) {
                    Log.d("OverlayDraw", "tap screen=(${event.x},${event.y}) loc=(${loc[0]},${loc[1]})")
                    onWordTap(event.x, event.y)
                }
                forwarding = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // 手势被系统取消（如来电、手掌误触）：结束回注，释放不可触摸状态
                if (forwarding) forwarder.end(event)
                forwarding = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ==================== 对外接口 ====================

    fun showResult(hit: TextHitResolver.Hit, translation: String) {
        Log.d("OverlayDraw", "showResult word='${hit.word}' trans='$translation' rect=${hit.wordRect}")
        // 降级命中（整节点）时卡片只显示截断文本，避免整段英文撑爆卡片
        val displayWord = if (hit.isFallback && hit.word.length > 40) {
            hit.word.take(40) + "…"
        } else {
            hit.word
        }
        annotation = Annotation(displayWord, translation, RectF(hit.wordRect))
        invalidate()
    }

    fun showMiss(x: Float, y: Float, hint: String? = null) {
        annotation = Annotation(
            word = "?",
            translation = hint ?: "未识别到文字",
            rect = RectF(x - 8f, y - 8f, x + 8f, y + 8f),
            isMiss = true,
        )
        invalidate()
    }

    fun clearTransient() {
        if (annotation == null) return
        annotation = null
        invalidate()
    }

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        val a = annotation ?: return
        // 视图坐标 → 屏幕坐标：触摸事件入口已 +loc 换算，绘制侧必须对称地 -loc，
        // 否则内容被画到 屏幕坐标 + loc 处（ColorOS 实测向下偏移 ~280px）
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        Log.d(
            "OverlayDraw",
            "onDraw loc=(${loc[0]},${loc[1]}) view=${width}x${height} rect=${a.rect}",
        )
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val r = a.rect
        if (!a.isMiss && !r.isEmpty) {
            // 虚线 = 命中确认：画在真实命中的词下方
            canvas.drawLine(r.left, r.bottom + 6f, r.right, r.bottom + 6f, dashPaint)
        }
        val card = layoutCard(a, width, height)
        drawCard(canvas, a, card)
    }

    private data class CardGeom(val rect: RectF, val transLayout: StaticLayout)

    private fun layoutCard(a: Annotation, viewW: Int, viewH: Int): CardGeom {
        val maxCardW = min(viewW * 0.75f, 560f).toInt()
        val transLayout = StaticLayout.Builder
            .obtain(a.translation, 0, a.translation.length, transPaint, maxCardW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val wordW = wordPaint.measureText(a.word)
        // 注意：StaticLayout.width 返回的是构造时传入的布局宽度（恒为 maxCardW），
        // 不是文字实际占用宽度，必须用每行 getLineWidth 取实际宽度
        var transW = 0f
        for (i in 0 until transLayout.lineCount) {
            transW = max(transW, transLayout.getLineWidth(i))
        }
        val contentW = max(wordW, transW)
        val cardW = contentW + cardPadding * 2
        val cardH = wordLineHeight + transLayout.height + cardPadding * 2

        val left = (a.rect.centerX() - cardW / 2)
            .coerceIn(cardMargin, max(cardMargin, viewW - cardW - cardMargin))

        var top = a.rect.top - cardH - cardGap
        if (top < cardMargin + 24f) {
            // 靠近屏幕顶部：翻转到词下方
            top = a.rect.bottom + cardGap
        }
        top = top.coerceIn(cardMargin, max(cardMargin, viewH - cardH - cardMargin))

        return CardGeom(RectF(left, top, left + cardW, top + cardH), transLayout)
    }

    private fun drawCard(canvas: Canvas, a: Annotation, g: CardGeom) {
        canvas.drawRoundRect(g.rect, 18f, 18f, cardBgPaint)
        canvas.drawRoundRect(g.rect, 18f, 18f, cardStrokePaint)
        val x = g.rect.left + cardPadding
        val baselineY = g.rect.top + cardPadding + wordPaint.textSize * 0.85f
        canvas.drawText(a.word, x, baselineY, wordPaint)
        canvas.save()
        canvas.translate(x, baselineY + 10f)
        g.transLayout.draw(canvas)
        canvas.restore()
    }
}
