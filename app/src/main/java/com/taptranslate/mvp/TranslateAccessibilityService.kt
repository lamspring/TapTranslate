package com.taptranslate.mvp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.taptranslate.mvp.mode.ModeController
import com.taptranslate.mvp.overlay.OverlayController
import com.taptranslate.mvp.secure.ApiKeyStore
import com.taptranslate.mvp.settings.Settings
import com.taptranslate.mvp.text.TextHitResolver
import com.taptranslate.mvp.translate.LlmTranslator
import com.taptranslate.mvp.translate.MlKitTranslator
import com.taptranslate.mvp.translate.Translator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 翻译模式核心服务。
 *
 * 职责：
 *  - 监听静音状态，开关翻译模式（模式 ON 才全开事件订阅 + 挂 overlay）
 *  - 接收 overlay 的点击回调，做词边界解析 + 翻译 + 回显
 *  - 滚动/窗口变化时清除悬浮卡片与虚线
 */
class TranslateAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "uncaught coroutine error", t) },
    )

    private lateinit var settings: Settings
    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var modeController: ModeController
    private lateinit var overlay: OverlayController

    private val mlKitTranslator by lazy { MlKitTranslator() }
    private val llmTranslator by lazy { LlmTranslator(settings, apiKeyStore) }

    /** 引擎选择：LLM 配置齐全走用户自费引擎，否则本地离线 */
    private val translator: Translator
        get() = if (settings.isLlmConfigured(apiKeyStore)) llmTranslator else mlKitTranslator

    override fun onServiceConnected() {
        settings = Settings(this)
        apiKeyStore = ApiKeyStore(this)
        overlay = OverlayController(this, ::onWordTap)
        modeController = ModeController(this, ::onModeChanged)
        // 同步一次初始状态（例如服务重启时手机已是静音）
        onModeChanged(modeController.isModeOn())
        modeController.start()
    }

    private fun onModeChanged(on: Boolean) {
        // 运行时动态调整事件订阅：模式关闭时几乎零开销
        setServiceInfo(buildServiceInfo(on))
        if (on) overlay.show() else overlay.hide()
    }

    private fun buildServiceInfo(on: Boolean): AccessibilityServiceInfo =
        AccessibilityServiceInfo().apply {
            eventTypes = if (on) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            } else {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            }
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 只在真正滚动时清除；网页懒加载/布局变化会刷窗口事件风暴，不能跟着清
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            overlay.clearTransient()
        }
    }

    override fun onInterrupt() {}

    private fun onWordTap(x: Float, y: Float) {
        val engine = translator
        val granularity = settings.granularity
        scope.launch(Dispatchers.Default) {
            val hit = runCatching {
                TextHitResolver.resolve(this@TranslateAccessibilityService, x, y, granularity)
            }.getOrElse {
                Log.e(TAG, "resolve failed", it)
                null
            }
            if (hit == null) {
                val hint = TextHitResolver.lastMissHint
                withContext(Dispatchers.Main) { overlay.showMiss(x, y, hint) }
                return@launch
            }
            val result = engine.translate(hit.word, hit.contextSentence)
            withContext(Dispatchers.Main) {
                overlay.showResult(hit, result.getOrElse { it.message ?: "翻译失败" })
            }
        }
    }

    override fun onDestroy() {
        modeController.stop()
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TapTranslate"
    }
}
