package com.example.floatick.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.example.floatick.core.Limits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 唯一的系统手势入口。canRetrieveWindowContent=false：不读屏幕内容，
 * 只用窗口切换事件拿前台包名（供受保护应用名单做防误触判断）。
 */
class ClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) ForegroundApp.update(pkg)
        }
    }

    override fun onInterrupt() {
        _connected.value = false
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            _connected.value = false
        }
        super.onDestroy()
    }

    fun performTap(xPx: Float, yPx: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(xPx, yPx) }
        return dispatch(path, durationMs)
    }

    fun performSwipe(
        x1Px: Float, y1Px: Float, x2Px: Float, y2Px: Float, durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(x1Px, y1Px)
            lineTo(x2Px, y2Px)
        }
        return dispatch(path, durationMs)
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceAtLeast(Limits.MIN_GESTURE_DURATION_MS)
        )
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // 与取点器保持完全一致的尺寸来源，避免「取的点」和「点的位置」出现系统性偏移。
    val screenWidthPx: Int get() = Resources.getSystem().displayMetrics.widthPixels
    val screenHeightPx: Int get() = Resources.getSystem().displayMetrics.heightPixels

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        @Volatile
        private var instance: ClickAccessibilityService? = null

        fun current(): ClickAccessibilityService? = instance
        fun isConnected(): Boolean = instance != null && _connected.value
    }
}

object ForegroundApp {
    private val _packageName = MutableStateFlow<String?>(null)
    val packageName: StateFlow<String?> = _packageName.asStateFlow()

    fun update(pkg: String) {
        _packageName.value = pkg
    }

    fun current(): String? = _packageName.value
}
