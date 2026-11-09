package com.example.floatick.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.floatick.R

object Notifications {
    const val CHANNEL_TIMER = "floatick_timer"
    const val CHANNEL_REMINDER = "floatick_reminder"
    const val ID_TIMER = 1001
    const val ID_REMINDER = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TIMER,
                context.getString(R.string.channel_timer),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER,
                context.getString(R.string.channel_reminder),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    /**
     * @param withAction 设为 false 时构造「最小可用通知」：只有图标与标题。
     * 用于 startForeground 第一次失败后的兜底——宁可少一个「停止」按钮，
     * 也不能让服务没转成前台（那会以 ForegroundServiceDidNotStartInTimeException 崩溃）。
     */
    fun running(context: Context, text: String, withAction: Boolean = true): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.notification_running))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (withAction) {
            val stopIntent = Intent(context, ClickForegroundService::class.java)
                .setAction(ClickForegroundService.ACTION_STOP)
            val stopPending = PendingIntent.getService(
                context, 11, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notification_stop),
                stopPending
            )
        }
        return builder.build()
    }

    fun showReminder(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = PendingIntent.getActivity(
            context, 12, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.getString(R.string.reminder_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_REMINDER, notification)
    }
}
