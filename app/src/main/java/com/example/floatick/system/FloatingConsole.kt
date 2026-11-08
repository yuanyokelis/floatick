package com.example.floatick.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
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
 * 悬浮控制台 / 取点器。
 *
 * 用原生 View 构建（不塞 ComposeView），避免 overlay 里装配
 * LifecycleOwner / SavedStateRegistry。
 *
 * 三个关键修复：
 *  1) 整个面板的空白处、标题、十字准星都可拖动，按钮仍可点击；
 *  2) 取点器按钮分两行、固定宽度，不再被裁切；
 *  3) view 意外被移除时，update() 会自动重建（Activity 重建竞态 / 服务启动异常）。
 */
@SuppressLint("ClickableViewAccessibility")
@Singleton
class FloatingConsole @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null

    private var picker: View? = null
    private var pickerParams: WindowManager.LayoutParams? = null
    private var crosshair: CrosshairView? = null
    private var pickerLabel: TextView? = null

    private var callbacks: Callbacks? = null
    private var onPicked: ((Float, Float) -> Unit)? = null
    private var lastState = EngineState()

    private data class Callbacks(
        val onStart: () -> Unit,
        val onStop: () -> Unit,
        val onPick: () -> Unit
    )

    /** Activity 重建时只更新回调，复用已存在的 window，避免旧 onDestroy 把新 view 移除。 */
    fun show(onStart: () -> Unit, onStop: () -> Unit, onPick: () -> Unit) {
        callbacks = Callbacks(onStart, onStop, onPick)
        mainHandler.post { ensureShown() }
    }

    /** 每次引擎状态变化都调用：若 window 被意外移除，立即重建回来。 */
    fun update(state: EngineState) {
        lastState = state
        mainHandler.post {
            ensureShown()
            statusView?.text = formatState(state)
        }
    }

    private fun formatState(state: EngineState): String = when {
        state.running -> "执行中 · 剩余 ${state.remainingCount} 次"
        state.armed -> "已武装 · ${state.message}"
        state.message.isNotBlank() -> state.message
        else -> "空闲"
    }

    private fun ensureShown() {
        if (callbacks == null || panel != null) return
        val params = baseParams().apply { x = 40; y = 120 }
        val root = DragLinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 255, 255, 255))
            setPadding(12, 8, 12, 8)
            bindDrag(wm, params)
        }
        val title = TextView(context).apply {
            text = "Floatick · 拖动空白处移动"
            setTextColor(Color.BLACK)
            textSize = 13f
        }
        val status = TextView(context).apply {
            text = formatState(lastState)
            setTextColor(Color.DKGRAY)
            textSize = 12f
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("开始") { callbacks?.onStart?.invoke() }, weighted())
        row.addView(smallButton("停止") { callbacks?.onStop?.invoke() }, weighted())
        row.addView(smallButton("取点") { callbacks?.onPick?.invoke() }, weighted())

        root.addView(title)
        root.addView(status)
        root.addView(row)
        root.addDragHandle(title)
        root.addDragHandle(status)

        wm.addView(root, params)
        panel = root
        panelParams = params
        statusView = status
    }

    /** 取点器：拖动十字/面板定位，1px 微调，回传屏幕比例坐标。 */
    fun showPicker(onPicked: (xRatio: Float, yRatio: Float) -> Unit) {
        this.onPicked = onPicked
        mainHandler.post {
            if (picker != null) return@post
            val params = baseParams().apply {
                x = 120
                y = 240
                width = dp(264)
            }
            val root = DragLinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(240, 255, 255, 255))
                setPadding(8, 6, 8, 6)
                bindDrag(wm, params)
            }
            val label = TextView(context).apply {
                text = "取点器 · 拖动十字或空白处"
                setTextColor(Color.BLACK)
                textSize = 11f
            }
            val cross = CrosshairView(context)

            fun nudge(dx: Int, dy: Int) {
                params.x += dx
                params.y += dy
                wm.updateViewLayout(root, params)
                updatePickerLabel()
            }

            val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(smallButton("-1X") { nudge(-1, 0) }, weighted())
            row1.addView(smallButton("+1X") { nudge(1, 0) }, weighted())
            row1.addView(smallButton("-1Y") { nudge(0, -1) }, weighted())
            row1.addView(smallButton("+1Y") { nudge(0, 1) }, weighted())

            val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(smallButton("确定") { confirmPick() }, weighted())
            row2.addView(smallButton("关闭") { hidePicker() }, weighted())

            root.addView(label)
            root.addView(cross, LinearLayout.LayoutParams(dp(150), dp(150)))
            root.addView(row1)
            root.addView(row2)
            root.addDragHandle(label)
            root.addDragHandle(cross)
            root.onMoved = { updatePickerLabel() }

            wm.addView(root, params)
            picker = root
            pickerParams = params
            crosshair = cross
            pickerLabel = label
            updatePickerLabel()
        }
    }

    /** 与 ClickAccessibilityService 使用同一套屏幕尺寸，保证取点与点击位置一致。 */
    private fun currentRatio(): Pair<Float, Float> {
        val cross = crosshair ?: return 0.5f to 0.5f
        val location = IntArray(2)
        cross.getLocationOnScreen(location)
        val cx = location[0] + cross.width / 2f
        val cy = location[1] + cross.height / 2f
        val dm = Resources.getSystem().displayMetrics
        return (cx / dm.widthPixels).coerceIn(0f, 1f) to
            (cy / dm.heightPixels).coerceIn(0f, 1f)
    }

    private fun updatePickerLabel() {
        val (x, y) = currentRatio()
        pickerLabel?.text = "取点器 · X=${round3(x)} Y=${round3(y)}"
    }

    private fun confirmPick() {
        val (x, y) = currentRatio()
        val callback = onPicked
        hidePicker()
        callback?.invoke(x, y)
    }

    private fun round3(v: Float): String = (Math.round(v * 1000f) / 1000f).toString()

    private fun hidePicker() {
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
        pickerParams = null
        crosshair = null
        pickerLabel = null
    }

    /** 只在 Activity 真正结束时调用；配置变更重建时保留悬浮窗。 */
    fun hide() {
        mainHandler.post {
            hidePicker()
            panel?.let { runCatching { wm.removeView(it) } }
            panel = null
            panelParams = null
            statusView = null
            callbacks = null
            onPicked = null
        }
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

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(context).apply {
            setText(text)
            setAllCaps(false)
            setTextSize(11f)
            setMinWidth(0)
            setMinimumWidth(0)
            setPadding(2, 2, 2, 2)
            setOnClickListener { onClick() }
        }

    /**
     * 可拖动的容器：把同一个 OnTouchListener 挂到自身和指定子 View 上。
     * 子 Button 会优先消费触摸，因此点击不受影响；空白处/标题/十字可拖。
     */
    private class DragLinearLayout(context: Context) : LinearLayout(context) {
        private var manager: WindowManager? = null
        private var windowParams: WindowManager.LayoutParams? = null
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        var onMoved: (() -> Unit)? = null

        fun bindDrag(manager: WindowManager, params: WindowManager.LayoutParams) {
            this.manager = manager
            this.windowParams = params
            setOnTouchListener(dragListener)
        }

        fun addDragHandle(view: View) {
            view.setOnTouchListener(dragListener)
        }

        private val dragListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = windowParams ?: return@OnTouchListener false
                    startX = p.x
                    startY = p.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = windowParams ?: return@OnTouchListener false
                    p.x = startX + (event.rawX - touchX).toInt()
                    p.y = startY + (event.rawY - touchY).toInt()
                    manager?.updateViewLayout(this@DragLinearLayout, p)
                    onMoved?.invoke()
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
