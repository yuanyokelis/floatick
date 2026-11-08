package com.example.floatick.system

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.floatick.R
import com.example.floatick.engine.ClickEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 只负责「常驻通知 + 防止执行期间进程被回收」，不承载计时逻辑。
 * 计时在 PrecisionTimer 的专用线程，状态在 ClickEngine（进程内单例）。
 */
@AndroidEntryPoint
class ClickForegroundService : Service() {

    @Inject
    lateinit var engine: ClickEngine

    override fun onCreate() {
        super.onCreate()
        // Android 14 对 specialUse 前台服务校验较严；失败时自我停止，
        // 避免系统因「未及时调用 startForeground」而杀掉整个应用进程。
        try {
            startForeground(
                Notifications.ID_TIMER,
                Notifications.running(this, getString(R.string.notification_running_text))
            )
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ARM = "com.example.floatick.action.ARM"
        const val ACTION_STOP = "com.example.floatick.action.STOP"
    }
}
