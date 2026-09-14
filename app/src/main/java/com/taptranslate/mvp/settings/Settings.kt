package com.taptranslate.mvp.settings

import android.content.Context
import com.taptranslate.mvp.secure.ApiKeyStore

enum class Granularity { WORD, PHRASE }

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var granularity: Granularity
        get() = if (prefs.getString(KEY_GRANULARITY, "word") == "phrase") {
            Granularity.PHRASE
        } else {
            Granularity.WORD
        }
        set(value) = prefs.edit().putString(KEY_GRANULARITY, value.name.lowercase()).apply()

    var llmEndpoint: String
        get() = prefs.getString(KEY_LLM_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LLM_ENDPOINT, value).apply()

    var llmModel: String
        get() = prefs.getString(KEY_LLM_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LLM_MODEL, value).apply()

    /** LLM 三项配置齐全才启用自费引擎，否则回退本地离线翻译 */
    fun isLlmConfigured(keyStore: ApiKeyStore): Boolean =
        llmEndpoint.isNotBlank() && llmModel.isNotBlank() && keyStore.hasApiKey()

    private companion object {
        const val KEY_GRANULARITY = "granularity"
        const val KEY_LLM_ENDPOINT = "llm_endpoint"
        const val KEY_LLM_MODEL = "llm_model"
    }
}
