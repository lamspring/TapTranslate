package com.taptranslate.mvp.translate

/** 双引擎统一接口。contextSentence 为被点词所在整句（LLM 引擎做上下文感知释义用）。 */
interface Translator {
    suspend fun translate(word: String, contextSentence: String?): Result<String>
}
