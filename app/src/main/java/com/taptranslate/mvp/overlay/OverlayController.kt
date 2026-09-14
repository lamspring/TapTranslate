package com.taptranslate.mvp.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.taptranslate.mvp.text.TextHitResolver

/**
 * 全屏透明 overlay 窗口（TYPE_ACCESSIBILITY_OVERLAY）：
 * 随无障碍服务自动获得悬浮能力，无需 SYSTEM_ALERT_WINDOW 权限。
 *
 * 手势回注策略（解决 dispatchGesture 回环问题）：
 *  - dispatchGesture 注入的手势会作为"新事件流"重新派发，若 overlay 可触摸会弹回自身，
 *    形成 begin→回注→begin 的无限回环（实测 ColorOS 上必定触发）
 *  - 因此采用「累积 + 延迟一次回注」：滑动期间只记录路径点，并把 overlay 设为
 *    FLAG_NOT_TOUCHABLE（越过 touchSlop 时立即设置，给 WMS 传播留出整个滑动时长）；
 *    原始手势 UP 时才把完整路径作为单段手势 dispatchGesture 回注
 *  - 回注完成（或取消）后才恢复可触摸；回注落地时 overlay 已稳定不可触摸，
 *    回注事件直达底层 App，不会弹回
 */
class OverlayController(
    private val service: AccessibilityService,
    private val onWordTap: (Float, Float) -> Unit,
) : OverlayView.GestureForwarder {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var view: OverlayView
    private var added = false

    // ---- 手势回注状态 ----
    private val path = Path()            // 累积的原始滑动路径（屏幕坐标）
    private var hasPath = false
    private var strokeStartX = 0f
    private var strokeStartY = 0f
    private var inFlight = 0
    private var restoreWhenDrained = false
    private var streamEnded = false
    private var suppressTapUntil = 0L    // 回注落地后短暂屏蔽点击，防尾段弹回造成幽灵点词

    private fun setTouchable(t: Boolean) {
        if (!added || !::view.isInitialized) return
        val lp = view.layoutParams as LayoutParams
        val currentlyTouchable = lp.flags and LayoutParams.FLAG_NOT_TOUCHABLE == 0
        if (currentlyTouchable == t) return
        lp.flags = if (t) lp.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(view, lp)
        Log.d(TAG, "overlay touchable=$t")
    }

    private fun restoreIfIdle() {
        // 必须等原始手势结束 + 回注全部落地才恢复，否则回注尾段弹回 overlay 形成幽灵点击
        if (streamEnded && inFlight <= 0 && restoreWhenDrained) {
            restoreWhenDrained = false
            setTouchable(true)
        }
    }

    fun show() {
        if (added) return
        view = OverlayView(service, { x, y ->
            if (SystemClock.uptimeMillis() >= suppressTapUntil) onWordTap(x, y)
        }, this)
        val lp = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCH_MODAL or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(view, lp)
        added = true
    }

    fun hide() {
        if (!added) return
        wm.removeView(view)
        added = false
    }

    fun showResult(hit: TextHitResolver.Hit, translation: String) {
        if (added) view.showResult(hit, translation)
    }

    fun showMiss(x: Float, y: Float, hint: String? = null) {
        if (added) view.showMiss(x, y, hint)
    }

    /** 滚动/窗口变化时清除卡片与虚线 */
    fun clearTransient() {
        if (added) view.clearTransient()
    }

    // ==================== GestureForwarder ====================

    override fun begin(ev: MotionEvent) {
        path.reset()
        hasPath = false
        strokeStartX = ev.x
        strokeStartY = ev.y
        streamEnded = false
        Log.d(TAG, "begin at (${ev.x},${ev.y})")
    }

    override fun onForwardingStart() {
        // 越过 touchSlop、判定为滑动的瞬间：立即设为不可触摸。
        // 原始事件流仍锁定本窗口继续送达；此后产生的新事件流（含回注）会跳过本 overlay
        restoreWhenDrained = true
        setTouchable(false)
    }

    override fun move(ev: MotionEvent) {
        if (!hasPath) {
            path.moveTo(strokeStartX, strokeStartY)
            hasPath = true
        }
        path.lineTo(ev.x, ev.y)
    }

    override fun end(ev: MotionEvent) {
        streamEnded = true
        if (!restoreWhenDrained) return   // 未进入转发（纯点击），overlay 全程可触摸
        if (!hasPath) {
            path.moveTo(strokeStartX, strokeStartY)
            path.lineTo(ev.x, ev.y)
            hasPath = true
        } else {
            path.lineTo(ev.x, ev.y)
        }
        Log.d(TAG, "end at (${ev.x},${ev.y}), dispatching accumulated path")
        dispatchAccumulated((ev.eventTime - ev.downTime).coerceAtLeast(1))
        restoreIfIdle()
    }

    private fun dispatchAccumulated(durationMs: Long) {
        try {
            val desc = GestureDescription.StrokeDescription(Path(path), 0, durationMs, false)
            inFlight++
            service.dispatchGesture(
                GestureDescription.Builder().addStroke(desc).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "dispatchGesture completed")
                        onDone(durationMs)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "dispatchGesture CANCELLED")
                        onDone(durationMs)
                    }
                },
                null,
            )
        } catch (t: Throwable) {
            val b = RectF()
            runCatching { path.computeBounds(b, true) }
            Log.e(TAG, "dispatchAccumulated failed: bounds=$b", t)
            restoreIfIdle()
        }
    }

    private fun onDone(durationMs: Long) {
        inFlight--
        // 回注落地后再屏蔽一小段时间，防止极端时序下尾段弹回触发幽灵点词
        suppressTapUntil = maxOf(
            suppressTapUntil,
            SystemClock.uptimeMillis() + durationMs + 150,
        )
        restoreIfIdle()
    }

    private companion object {
        const val TAG = "GestureFW"
    }
}
