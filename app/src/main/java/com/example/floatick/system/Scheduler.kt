package com.example.floatick.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.floatick.time.ClockAnchor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AlarmManager 只作兜底提醒（提前 1 分钟），不承担毫秒级触发。
 * 用非 WAKEUP 的 ELAPSED_REALTIME：设备睡着时不打扰用户，也不会
 * 造成「以为已经触发」的错觉；精确触发只能由前台 PrecisionTimer 完成。
 */
@Singleton
class Scheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clockAnchor: ClockAnchor
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun armReminder(schemeId: Long, targetUtcMs: Long) {
        val remindAt = clockAnchor.toElapsedMs(targetUtcMs) - REMIND_LEAD_MS
        if (remindAt <= SystemClock.elapsedRealtime()) return
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME,
            remindAt,
            pendingFor(schemeId)
        )
    }

    fun cancelReminder(schemeId: Long) {
        alarms.cancel(pendingFor(schemeId))
    }

    private fun pendingFor(schemeId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            schemeId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private companion object {
        const val REMIND_LEAD_MS = 60_000L
    }
}
