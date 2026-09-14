package com.taptranslate.mvp.mode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * 模式开关（第一版用静音状态模拟）。
 *
 * 已拍板：仅完全静音（RINGER_MODE_SILENT）算开，振动（VIBRATE）不算。
 * 广播通过动态注册接收（不受 Android 8+ 隐式广播限制），
 * 并以 RECEIVER_NOT_EXPORTED 注册满足 targetSdk 34+ 的强制要求。
 */
class ModeController(
    private val context: Context,
    private val onModeChanged: (Boolean) -> Unit,
) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            notifyState()
        }
    }

    fun start() {
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        notifyState()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun isModeOn(): Boolean = audio.ringerMode == AudioManager.RINGER_MODE_SILENT

    private fun notifyState() {
        onModeChanged(isModeOn())
    }
}
