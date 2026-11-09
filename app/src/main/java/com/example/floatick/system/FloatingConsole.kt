package com.example.floatick.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import com.example.floatick.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮控制台 + 取点器（原生 View，避免 overlay 里装配 Compose）。
 *
 * 取点器拆成两个独立 window：
 *  - 一个 56dp 的十字，可拖到屏幕任意位置（含右下角），只按自身半径钳制；
 *  - 一条固定在屏幕顶部的工具条，放 X/Y、确定、关闭和 1px 微调。
 * 这样「按钮要可见」和「十字要能到达边缘」不再互相打架，也不会整块挡住页面。
 */
@SuppressLint("ClickableViewAccessibility")
@Singleton
class FloatingConsole @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val crossSize: Int get() = dp(56)

    private var panel: View? = null
    private var statusView: TextView? = null

    private var pickerCross: View? = null
    private var crossParams: WindowManager.LayoutParams? = null
    private var pickerBar: View? = null
    private var pickerLabel: TextView? = null

    private var callbacks: Callbacks? = null
    private var onPicked: ((Float, Float) -> Unit)? = null
    private var lastState = EngineState()

    /** 用户真正退出 App 后不再自动重建；回到前台时由 show/ensureVisible 解除。 */
    private var dismissed = false

    /** addView 失败不能让整个 App 闪退，错误要能在界面上看到。 */
    @Volatile
    private var lastOverlayError: String? = null

    fun overlayError(): String? = lastOverlayError

    private data class Callbacks(
        val onStart: () -> Unit,
        val onArm: () -> Unit,
        val onStop: () -> Unit,
        val onPick: () -> Unit
    )

    private fun activeCallbacks(): Callbacks = callbacks ?: Callbacks(
        onStart = { openMain() },
        onArm = { openMain() },
        onStop = {},
        onPick = {}
    )

    private fun openMain() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    fun show(
        onStart: () -> Unit,
        onArm: () -> Unit,
        onStop: () -> Unit,
        onPick: () -> Unit
    ) {
        callbacks = Callbacks(onStart, onArm, onStop, onPick)
        dismissed = false
        mainHandler.post { ensureShown() }
    }

    /** Activity 每次 onResume 都调用：即使被系统移除或回调被清空，也把控制台找回来。 */
    fun ensureVisible() {
        dismissed = false
        mainHandler.post { ensureShown() }
    }

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
        // 不再因 callbacks 为空而拒绝显示：用一组降级回调（点击回到 App）。
        if (dismissed || panel != null) return
        val params = baseParams().apply { x = 20; y = 100 }
        val root = DragLinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 255, 255, 255))
            setPadding(10, 6, 10, 6)
            bindDrag(wm, params)
        }
        val title = TextView(context).apply {
            text = "Floatick · 拖动空白处移动"
            setTextColor(Color.BLACK)
            setTextSize(12f)
        }
        val status = TextView(context).apply {
            text = formatState(lastState)
            setTextColor(Color.DKGRAY)
            setTextSize(11f)
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("立即") { activeCallbacks().onStart() }, weighted())
        row.addView(smallButton("武装") { activeCallbacks().onArm() }, weighted())
        row.addView(smallButton("停止") { activeCallbacks().onStop() }, weighted())
        row.addView(smallButton("取点") { activeCallbacks().onPick() }, weighted())

        root.addView(title)
        root.addView(status)
        root.addView(row)
        root.addDragHandle(title)
        root.addDragHandle(status)
        // 被系统/ROM 移除时清空引用，下一次状态更新会自动重建。
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                if (panel === v) {
                    panel = null
                    statusView = null
                }
            }
        })

        try {
            wm.addView(root, params)
            panel = root
            statusView = status
            lastOverlayError = null
        } catch (e: Throwable) {
            lastOverlayError = "悬浮窗添加失败：${e.javaClass.simpleName}: ${e.message}"
            panel = null
            statusView = null
        }
    }

    /** 取点器：小十字 + 顶部固定工具条。 */
    fun showPicker(onPicked: (xRatio: Float, yRatio: Float) -> Unit) {
        this.onPicked = onPicked
        mainHandler.post {
            if (pickerCross != null) return@post
            val dm = Resources.getSystem().displayMetrics
            val size = crossSize

            val cp = baseParams().apply {
                width = size
                height = size
                x = dm.widthPixels / 2 - size / 2
                y = dm.heightPixels / 2 - size / 2
            }
            val cross = CrosshairView(context)
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            cross.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = cp.x
                        startY = cp.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        cp.x = startX + (event.rawX - touchX).toInt()
                        cp.y = startY + (event.rawY - touchY).toInt()
                        clampCross()
                        safeUpdate(cross, cp)
                        updatePickerLabel()
                        true
                    }
                    else -> false
                }
            }

            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(235, 255, 255, 255))
                setPadding(8, 4, 8, 4)
            }
            val label = TextView(context).apply {
                text = "X=0.500 Y=0.500"
                setTextColor(Color.BLACK)
                setTextSize(10f)
            }
            fun nudge(dx: Int, dy: Int) {
                cp.x += dx
                cp.y += dy
                clampCross()
                wm.updateViewLayout(cross, cp)
                updatePickerLabel()
            }
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(smallButton("确定") { confirmPick() })
            head.addView(smallButton("关闭") { hidePicker() })
            val micro = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            micro.addView(smallButton("-1X") { nudge(-1, 0) }, weighted())
            micro.addView(smallButton("+1X") { nudge(1, 0) }, weighted())
            micro.addView(smallButton("-1Y") { nudge(0, -1) }, weighted())
            micro.addView(smallButton("+1Y") { nudge(0, 1) }, weighted())
            bar.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            bar.addView(micro, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val bp = baseParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                x = 0
                y = 0
            }
            try {
                wm.addView(cross, cp)
                wm.addView(bar, bp)
                pickerCross = cross
                crossParams = cp
                pickerBar = bar
                pickerLabel = label
                lastOverlayError = null
                updatePickerLabel()
            } catch (e: Throwable) {
                lastOverlayError = "取点器添加失败：${e.javaClass.simpleName}: ${e.message}"
                runCatching { wm.removeView(cross) }
                runCatching { wm.removeView(bar) }
                pickerCross = null
                crossParams = null
                pickerBar = null
                pickerLabel = null
            }
        }
    }

    /** 只按十字自身半径钳制，中心可到达 0..屏幕宽高，右下角也能取。 */
    private fun clampCross() {
        val p = crossParams ?: return
        val dm = Resources.getSystem().displayMetrics
        val half = crossSize / 2
        p.x = p.x.coerceIn(-half, dm.widthPixels - half)
        p.y = p.y.coerceIn(-half, dm.heightPixels - half)
    }

    /** 用 window 参数直接算中心，不依赖 getLocationOnScreen 的布局时序。 */
    private fun currentRatio(): Pair<Float, Float> {
        val p = crossParams ?: return 0.5f to 0.5f
        val half = crossSize / 2f
        val dm = Resources.getSystem().displayMetrics
        return ((p.x + half) / dm.widthPixels).coerceIn(0f, 1f) to
            ((p.y + half) / dm.heightPixels).coerceIn(0f, 1f)
    }

    private fun updatePickerLabel() {
        val (x, y) = currentRatio()
        pickerLabel?.text = "X=${round3(x)} Y=${round3(y)} · 拖十字定位"
    }

    private fun confirmPick() {
        val (x, y) = currentRatio()
        val callback = onPicked
        hidePicker()
        callback?.invoke(x, y)
    }

    private fun round3(v: Float): String = (Math.round(v * 1000f) / 1000f).toString()

    /** 编辑页离开时调用，避免取点器跨页面一直挡着。 */
    fun hidePicker() {
        pickerBar?.let { runCatching { wm.removeView(it) } }
        pickerCross?.let { runCatching { wm.removeView(it) } }
        pickerBar = null
        pickerCross = null
        crossParams = null
        pickerLabel = null
    }

    fun hide() {
        dismissed = true
        mainHandler.post {
            hidePicker()
            panel?.let { runCatching { wm.removeView(it) } }
            panel = null
            statusView = null
            onPicked = null
            // 保留 callbacks：回来重建时按钮仍指向当前 ViewModel，而不是降级回调。
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

    private fun safeUpdate(view: View?, params: WindowManager.LayoutParams) {
        if (view == null) return
        runCatching { wm.updateViewLayout(view, params) }
    }

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

    /** 可拖动容器：空白处/标题可拖，子 Button 仍可点击。 */
    private class DragLinearLayout(context: Context) : LinearLayout(context) {
        private var manager: WindowManager? = null
        private var windowParams: WindowManager.LayoutParams? = null
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

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
                    runCatching { manager?.updateViewLayout(this@DragLinearLayout, p) }
                    true
                }
                else -> false
            }
        }
    }

    private class CrosshairView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 40, 40)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawLine(0f, cy, width.toFloat(), cy, paint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), paint)
            canvas.drawCircle(cx, cy, 10f, paint)
        }
    }
}
