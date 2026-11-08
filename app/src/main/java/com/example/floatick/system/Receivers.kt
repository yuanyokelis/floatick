package com.example.floatick.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.floatick.data.SchemeRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 本应用的设计前提是「亮屏 + 人手动打开」，所以重启后不做后台自动拉起
 * （Android 12+ 也限制从 BOOT_COMPLETED 启动前台服务）。
 * 这里只提示用户回来重新武装任务，并借此处理系统时间/时区被修改的情况。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: SchemeRepository

    override fun onReceive(context: Context, intent: Intent) {
        val watched = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (intent.action !in watched) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (repository.activeScheduleCount() > 0) Notifications.showReminder(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** AlarmManager 的兜底提醒接收器：只发通知，不执行手势。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.showReminder(context)
    }
}
