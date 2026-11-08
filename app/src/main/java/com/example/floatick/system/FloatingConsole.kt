package com.example.floatick.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.floatick.core.EngineState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮控制台用原生 View 构建（不塞 ComposeView），避免 overlay 里
 * LifecycleOwner / SavedStateRegistry 的额外装配，稳定性优先。
 */
@Singleton
class FloatingConsole @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var panel: View? = null
    private var statusView: TextView? = null
    private var picker: View? = null

    fun show(onStart: () -> Unit, onStop: () -> Unit, onPick: () -> Unit) {
        if (panel != null) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 255, 255, 255))
            setPadding(16, 12, 16, 12)
        }
        val handle = TextView(context).apply {
            text = "Floatick  ·  拖动移动"
            setTextColor(Color.BLACK)
            textSize = 13f
        }
        val status = TextView(context).apply {
            text = "空闲"
            setTextColor(Color.DKGRAY)
            textSize = 12f
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(context).apply {
            text = "开始"
            setOnClickListener { onStart() }
        })
        row.addView(Button(context).apply {
            text = "停止"
            setOnClickListener { onStop() }
        })
        row.addView(Button(context).apply {
            text = "取点"
            setOnClickListener { onPick() }
        })
        root.addView(handle)
        root.addView(status)
        root.addView(row)

        val params = baseParams().apply { x = 40; y = 120 }
        attachDrag(handle, root, params)
        wm.addView(root, params)
        panel = root
        statusView = status
    }

    fun update(state: EngineState) {
        val text = when {
            state.running -> "执行中 · 剩余 ${state.remainingCount} 次"
            state.armed -> "已武装 · ${state.message}"
            state.message.isNotBlank() -> state.message
            else -> "空闲"
        }
        statusView?.post { statusView?.text = text }
    }

    fun hide() {
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        statusView = null
    }

    /** 取点器：拖动定位 + 1px 微调，返回屏幕比例坐标。 */
    @SuppressLint("ClickableViewAccessibility")
    fun showPicker(onPicked: (xRatio: Float, yRatio: Float) -> Unit) {
        if (picker != null) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 255, 255, 255))
            setPadding(12, 8, 12, 8)
        }
        val handle = TextView(context).apply {
            text = "取点器 · 拖动到目标位置"
            setTextColor(Color.BLACK)
            textSize = 12f
        }
        val crosshair = CrosshairView(context)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val params = baseParams().apply { x = 200; y = 300 }

        fun nudge(dx: Int, dy: Int) {
            params.x += dx
            params.y += dy
            wm.updateViewLayout(root, params)
        }
        row.addView(Button(context).apply { text = "-1x"; setOnClickListener { nudge(-1, 0) } })
        row.addView(Button(context).apply { text = "+1x"; setOnClickListener { nudge(1, 0) } })
        row.addView(Button(context).apply { text = "-1y"; setOnClickListener { nudge(0, -1) } })
        row.addView(Button(context).apply { text = "+1y"; setOnClickListener { nudge(0, 1) } })
        row.addView(Button(context).apply {
            text = "确定"
            setOnClickListener {
                val location = IntArray(2)
                crosshair.getLocationOnScreen(location)
                val cx = location[0] + crosshair.width / 2f
                val cy = location[1] + crosshair.height / 2f
                val metrics = context.resources.displayMetrics
                onPicked(
                    (cx / metrics.widthPixels).coerceIn(0f, 1f),
                    (cy / metrics.heightPixels).coerceIn(0f, 1f)
                )
                hidePicker()
            }
        })
        row.addView(Button(context).apply {
            text = "关闭"
            setOnClickListener { hidePicker() }
        })
        root.addView(handle)
        root.addView(crosshair, LinearLayout.LayoutParams(160, 160))
        root.addView(row)

        attachDrag(handle, root, params)
        wm.addView(root, params)
        picker = root
    }

    private fun hidePicker() {
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
    }

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(
        handle: View,
        target: View,
        params: WindowManager.LayoutParams
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(target, params)
                    true
                }
                else -> false
            }
        }
    }

    private class CrosshairView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 40, 40)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawLine(0f, cy, width.toFloat(), cy, paint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), paint)
            canvas.drawCircle(cx, cy, 12f, paint)
        }
    }
}
