package com.example.floatick.system

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityServiceInfo

data class PermissionState(
    val accessibility: Boolean,
    val overlay: Boolean,
    val notifications: Boolean
) {
    val ready: Boolean get() = accessibility && overlay && notifications
}

object PermissionCheck {

    /** 每次冷启动都实时自检，绝不缓存：ROM 可能在更新或长期未用后回收授权。 */
    fun snapshot(context: Context): PermissionState = PermissionState(
        accessibility = isAccessibilityEnabled(context),
        overlay = Settings.canDrawOverlays(context),
        notifications = notificationsGranted(context)
    )

    fun isAccessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, ClickAccessibilityService::class.java)
        val flat = component.flattenToString()
        val short = component.flattenToShortString()
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }.getOrDefault("")
        val inSecure = raw.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { it.equals(flat, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        if (inSecure) return true

        // 部分 ROM 不写标准 Settings.Secure，或用了不同格式；用系统服务列表兜底。
        return runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
        }.getOrDefault(false)
    }

    private fun notificationsGranted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Android 13+ 侧载 APK 需在此页右上 ⋮ 选择「允许受限设置」。 */
    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
