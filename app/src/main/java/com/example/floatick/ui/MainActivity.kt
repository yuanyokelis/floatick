package com.example.floatick.ui

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.floatick.CrashReporter
import com.example.floatick.engine.ClickEngine
import com.example.floatick.system.FloatingConsole
import com.example.floatick.ui.theme.FloatickTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var console: FloatingConsole

    @Inject
    lateinit var engine: ClickEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不在启动时自动弹 POST_NOTIFICATIONS 权限框：系统对话框会立刻
        // 把 MainActivity 压到后台，部分 ROM 上表现为「页面闪一下就消失，
        // 但后台还显示应用在运行」。改为在权限页由用户主动开启。
        // 上次闪退的堆栈：进界面后弹出来，用户可直接复制定位。
        val crashText = CrashReporter.lastCrash(this)
        CrashReporter.clear(this)
        setContent {
            FloatickTheme {
                AppNav(engine = engine, console = console, crashText = crashText)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台都确保悬浮控制台存在：ROM 可能回收 overlay，
        // 或上一次 hide() 清空了回调，导致「悬浮框不出现、看不到错误提示」。
        if (Settings.canDrawOverlays(this)) console.ensureVisible()
    }

    override fun onDestroy() {
        // 只在真正退出时移除悬浮窗；旋转、深色模式切换等配置变更会重建 Activity，
        // 此时若无条件 hide，旧 onDestroy 会在新 Activity show() 之后把 view 移除。
        if (isFinishing) console.hide()
        super.onDestroy()
    }
}
