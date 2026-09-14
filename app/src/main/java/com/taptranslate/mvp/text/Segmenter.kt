package com.taptranslate.mvp.text

import com.taptranslate.mvp.settings.Granularity

/**
 * 分词：
 *  - 拉丁文：字母数字连续段
 *  - 中文：词典正向最大匹配（FMM），短语级放宽到 12 字长词/惯用语
 *
 * MVP 内嵌微型词典只为跑通链路；生产版从 assets 加载 5–8 万词条（约 1–2 MB）。
 */
object Segmenter {

    /** MVP 微型词典（生产版替换为 assets 全量词典） */
    private val DICT: Set<String> = setOf(
        "学习", "英语", "手机", "翻译", "模式", "屏幕", "单词", "句子", "词语", "短语",
        "时间", "生活", "工作", "中国", "美国", "英国", "今天", "明天", "昨天", "朋友",
        "家庭", "社会", "问题", "情况", "系统", "应用", "文字", "语言", "发音", "设置",
        "静音", "振动", "点击", "滑动", "返回", "界面", "历史", "未来", "世界", "国家",
        "城市", "学校", "学生", "老师", "课程", "阅读", "写作", "听力", "口语", "考试",
        "游戏", "视频", "音乐", "电影", "新闻", "浏览器", "网络", "数据", "安全", "性能",
    )

    private const val MAX_WORD_LEN = 8
    private const val MAX_PHRASE_LEN = 12

    fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c)

    /** 对一段连续词字符分词，返回相对 run 起点的区间列表 */
    fun segment(run: String, granularity: Granularity): List<IntRange> {
        val hasCjk = run.any { it.code in 0x4E00..0x9FFF }
        return if (hasCjk) segmentCjk(run, granularity) else segmentLatin(run)
    }

    private fun segmentLatin(run: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var i = 0
        while (i < run.length) {
            if (!Character.isLetterOrDigit(run[i])) { i++; continue }
            val start = i
            while (i < run.length && Character.isLetterOrDigit(run[i])) i++
            spans.add(start until i)
        }
        return spans
    }

    private fun segmentCjk(run: String, granularity: Granularity): List<IntRange> {
        val maxLen = if (granularity == Granularity.PHRASE) MAX_PHRASE_LEN else MAX_WORD_LEN
        val spans = mutableListOf<IntRange>()
        var i = 0
        while (i < run.length) {
            var matchLen = 0
            val upper = minOf(maxLen, run.length - i)
            for (len in upper downTo 2) {
                if (run.substring(i, i + len) in DICT) { matchLen = len; break }
            }
            if (matchLen > 0) {
                spans.add(i until i + matchLen)
                i += matchLen
            } else {
                spans.add(i until i + 1)
                i++
            }
        }
        return spans
    }
}
