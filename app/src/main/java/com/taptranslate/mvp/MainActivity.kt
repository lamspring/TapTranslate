package com.taptranslate.mvp

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import com.taptranslate.mvp.secure.ApiKeyStore
import com.taptranslate.mvp.settings.Granularity
import com.taptranslate.mvp.settings.Settings

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val settings = Settings(this)
        val keyStore = ApiKeyStore(this)

        val granularity = findViewById<RadioGroup>(R.id.granularity)
        granularity.check(
            if (settings.granularity == Granularity.WORD) R.id.granularity_word
            else R.id.granularity_phrase
        )
        granularity.setOnCheckedChangeListener { _, checkedId ->
            settings.granularity =
                if (checkedId == R.id.granularity_phrase) Granularity.PHRASE else Granularity.WORD
        }

        val endpoint = findViewById<EditText>(R.id.endpoint)
        val model = findViewById<EditText>(R.id.model)
        val apiKey = findViewById<EditText>(R.id.api_key)
        val status = findViewById<TextView>(R.id.llm_status)

        endpoint.setText(settings.llmEndpoint)
        model.setText(settings.llmModel)

        fun refreshStatus() {
            status.text = if (settings.isLlmConfigured(keyStore)) {
                "当前引擎：LLM（API Key 已用 Android Keystore 加密存储在本机）"
            } else {
                "当前引擎：本地离线翻译"
            }
        }
        refreshStatus()

        findViewById<Button>(R.id.save_llm).setOnClickListener {
            settings.llmEndpoint = endpoint.text.toString().trim()
            settings.llmModel = model.text.toString().trim()
            val key = apiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                keyStore.saveApiKey(key)
            }
            apiKey.setText("")
            refreshStatus()
        }
    }
}
