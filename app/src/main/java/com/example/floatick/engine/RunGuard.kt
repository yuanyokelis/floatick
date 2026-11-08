package com.example.floatick.engine

import com.example.floatick.data.AppSettings
import com.example.floatick.system.ForegroundApp
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 受保护应用名单。定位是「防误触」：避免悬浮点击意外落到资金/支付界面。
 * 不做用途审查，也不宣称能识别所有金融 App——包名匹配只是尽力而为的护栏。
 */
@Singleton
class RunGuard @Inject constructor() {

    fun isProtected(settings: AppSettings, pkg: String? = ForegroundApp.current()): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        if (DEFAULT_PREFIXES.any { lower.startsWith(it) }) return true
        if (DEFAULT_KEYWORDS.any { lower.contains(it) }) return true
        return settings.protectedPackages.any { custom ->
            val c = custom.trim().lowercase()
            c.isNotEmpty() && (lower == c || lower.startsWith(c))
        }
    }

    fun currentPackage(): String? = ForegroundApp.current()

    private companion object {
        val DEFAULT_PREFIXES = listOf(
            "com.icbc", "com.cmbchina", "com.bankcomm", "com.abchina",
            "com.ccb", "com.unionpay", "com.eg.android.alipaygphone"
        )
        val DEFAULT_KEYWORDS = listOf("bank", "banking", "wallet", "unionpay", "alipay")
    }
}
