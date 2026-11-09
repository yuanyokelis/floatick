package com.example.floatick.system

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.floatick.CrashReporter
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
        // 一旦用 startForegroundService 启动，系统就要求服务在 5 秒内调用
        // startForeground；否则会在主线程抛 ForegroundServiceDidNotStartInTimeException，
        // 整个进程（连同悬浮台）一起崩。所以这里是第一道也是必须成功的一道。
        ensureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 第二道：stop/start 撞车时，框架可能复用已存在的实例、只补发 onStartCommand
        // （onCreate 不会重跑）。此时只有这里能补上 startForeground。
        // 对已在运行的服务重复调用是安全的，只是更新通知内容。
        ensureForeground()

        if (intent?.action == ACTION_STOP) {
            engine.stop()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 通知引擎「服务已经没了」，否则它会在下一次启动时误以为服务还活着而不去拉起。
        runCatching { engine.onForegroundServiceStopped() }
        super.onDestroy()
    }

    /**
     * 保证 startForeground 一定被调用、且异常不逃出去。
     *
     * 以前失败就 stopSelf()——但停止服务**不会**撤销「超时未转前台」的判罚，
     * 5 秒后照样崩。现在退到最小通知再试一次，并把真实原因落盘，便于下次定位。
     */
    private fun ensureForeground() {
        val ok = runCatching { startForegroundCompat() }
            .onFailure { error ->
                Log.w(TAG, "startForeground failed", error)
                CrashReporter.logNote(
                    this,
                    "startForeground 失败：${error.javaClass.name}: ${error.message}"
                )
            }
            .isSuccess
        if (ok) return

        runCatching { startForegroundCompat(minimal = true) }
            .onFailure { error ->
                Log.w(TAG, "startForeground failed again with minimal notification", error)
                CrashReporter.logNote(
                    this,
                    "startForeground 二次失败：${error.javaClass.name}: ${error.message}"
                )
            }
    }

    private fun startForegroundCompat(minimal: Boolean = false) {
        // 幂等：通道被用户删除后重建，避免「Bad notification for startForeground」。
        Notifications.createChannels(this)
        val notification = if (minimal) {
            Notifications.running(this, getString(R.string.notification_running_text), withAction = false)
        } else {
            Notifications.running(this, getString(R.string.notification_running_text))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notifications.ID_TIMER,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifications.ID_TIMER, notification)
        }
    }

    companion object {
        private const val TAG = "ClickForegroundService"

        const val ACTION_ARM = "com.example.floatick.action.ARM"
        const val ACTION_STOP = "com.example.floatick.action.STOP"
    }
}
