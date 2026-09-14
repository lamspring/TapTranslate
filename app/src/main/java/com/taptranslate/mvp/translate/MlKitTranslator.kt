package com.taptranslate.mvp.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 主引擎：ML Kit 本地翻译（免费、离线、单次 < 50ms）。
 * 模型首次使用时下载，之后完全离线。
 */
class MlKitTranslator(
    sourceLang: String = TranslateLanguage.ENGLISH,
    targetLang: String = TranslateLanguage.CHINESE,
) : Translator {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()
    )

    @Volatile
    private var ready = false

    override suspend fun translate(word: String, contextSentence: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!ready) {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    ready = true
                }
                translator.translate(word).await()
            }
        }
}
