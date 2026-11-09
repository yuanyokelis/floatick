package com.example.floatick

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 自用包没有应用商店的崩溃后台，闪退时用户什么也看不到。
 * 这里把未捕获异常落到三处：通知栏、系统剪贴板、私有文件，
 * 下次启动时 App 内也会弹出来，方便直接把堆栈贴回来定位。
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.log"
    private const val NOTE_FILE_NAME = "notes.log"
    private const val CHANNEL_ID = "floatick_crash"
    private const val NOTIFICATION_ID = 9001

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val text = "thread=${thread.name}\n$stack"
            runCatching { File(context.filesDir, FILE_NAME).writeText(text) }
            runCatching { copyToClipboard(context, text) }
            runCatching { showNotification(context, error, text) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 记录「不会崩溃但会静默降级」的异常（例如 startForeground 失败）。
     * 这类问题以前被 runCatching 吞掉，用户只看到「没通知」，无从排查。
     */
    fun logNote(context: Context, text: String) {
        runCatching {
            val file = File(context.filesDir, NOTE_FILE_NAME)
            if (file.exists() && file.length() > 16_000) file.writeText("")
            file.appendText("[${System.currentTimeMillis()}] $text\n")
        }
    }

    fun lastNote(context: Context): String? = runCatching {
        val file = File(context.filesDir, NOTE_FILE_NAME)
        if (file.exists()) file.readText() else null
    }.getOrNull()

    fun lastCrash(context: Context): String? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.readText() else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Floatick crash", text))
    }

    private fun showNotification(context: Context, error: Throwable, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "崩溃诊断",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Floatick 启动失败 · ${error.javaClass.simpleName}")
            .setContentText(error.message ?: "堆栈已复制到剪贴板")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(800)))
            .setAutoCancel(false)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
