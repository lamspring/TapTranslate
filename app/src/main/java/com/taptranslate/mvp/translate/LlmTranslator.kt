package com.taptranslate.mvp.translate

import com.taptranslate.mvp.secure.ApiKeyStore
import com.taptranslate.mvp.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 自费引擎：用户自带 LLM API Key（OpenAI 兼容 chat/completions 接口）。
 *
 * 核心优势：上下文感知——把整句 + 被点词一起发给 LLM，
 * 返回该词在当前语句链路中的恰当代入释义。
 */
class LlmTranslator(
    private val settings: Settings,
    private val keyStore: ApiKeyStore,
) : Translator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(word: String, contextSentence: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = keyStore.getApiKey() ?: error("未配置 API Key")
                val prompt = buildString {
                    append("你是英汉词典。给出「").append(word).append("」")
                    if (!contextSentence.isNullOrBlank()) {
                        append("在句子「").append(contextSentence).append("」中")
                    }
                    append("的中文释义，只输出简短释义（不超过30字）。")
                }
                val body = JSONObject()
                    .put("model", settings.llmModel)
                    .put("temperature", 0.2)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", prompt)
                        )
                    )
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(settings.llmEndpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("LLM 请求失败 HTTP ${resp.code}")
                    val json = JSONObject(resp.body?.string() ?: error("空响应"))
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                }
            }
        }
}
