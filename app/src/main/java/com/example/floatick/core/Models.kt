package com.example.floatick.core

enum class SchemeType { SINGLE_REPEAT, STEP_SEQUENCE }

enum class ActionType { CLICK, LONG_PRESS, SWIPE }

enum class StopReason {
    COMPLETED, USER_STOPPED, PERMISSION_LOST, PROTECTED_APP, SCREEN_OFF, ERROR, NOT_SYNCED
}

/**
 * 运行结果的中文说明。数据库里存的是 StopReason.name，界面与悬浮框直接显示枚举名
 * （如 PROTECTED_APP）用户看不懂，统一在这里翻译。
 */
fun stopReasonLabel(name: String?): String = when (name) {
    "COMPLETED" -> "正常完成"
    "USER_STOPPED" -> "手动停止"
    "PERMISSION_LOST" -> "无障碍断开"
    "PROTECTED_APP" -> "命中受保护应用"
    "SCREEN_OFF" -> "屏幕熄灭"
    "ERROR" -> "出错中断"
    "NOT_SYNCED" -> "未校时拒绝执行"
    null, "" -> "未知"
    else -> name
}

fun stopReasonLabel(reason: StopReason): String = stopReasonLabel(reason.name)

/**
 * 坐标一律用屏幕比例 (0f..1f) 存储，执行时再乘以当前屏幕宽高。
 * 这样换机型、分屏、横竖屏切换都不会让坐标失效。
 */
data class Step(
    val id: Long = 0L,
    val orderIndex: Int = 0,
    val xRatio: Float = 0.5f,
    val yRatio: Float = 0.5f,
    val endXRatio: Float = 0.5f,
    val endYRatio: Float = 0.5f,
    val action: ActionType = ActionType.CLICK,
    val durationMs: Long = 80L,
    val delayAfterMs: Long = 0L
)

data class Scheme(
    val id: Long = 0L,
    val name: String = "新建方案",
    val type: SchemeType = SchemeType.SINGLE_REPEAT,
    val steps: List<Step> = emptyList(),
    val loopCount: Int = 1,
    val intervalMs: Long = 100L,
    val enabled: Boolean = true
) {
    /** 单点重复：只有一个步骤，循环 loopCount 次，每次间隔 intervalMs。 */
    val effectiveSteps: List<Step>
        get() = if (steps.isEmpty()) listOf(Step()) else steps
}

data class ScheduleRule(
    val schemeId: Long,
    val triggerUtcMillis: Long,
    val enabled: Boolean = true
)

data class RunRecord(
    val id: Long = 0L,
    val schemeId: Long,
    val targetUtcMillis: Long,
    val offsetMs: Long,
    val rttMs: Long,
    val deviationMs: Long,
    val executedCount: Int,
    val stopReason: StopReason,
    val synced: Boolean,
    val startedAtUtcMillis: Long
)

/** 悬浮控制台与主界面共用的运行状态。 */
data class EngineState(
    val running: Boolean = false,
    val armed: Boolean = false,
    val schemeName: String = "",
    val remainingCount: Int = 0,
    val targetUtcMillis: Long = 0L,
    val synced: Boolean = false,
    val message: String = ""
)

object Limits {
    /** 人手点击按下通常 50~150ms；过短会被部分 View / WebView 当成无效触摸。 */
    const val MIN_GESTURE_DURATION_MS = 50L
    const val MIN_INTERVAL_MS = 50L
    const val MAX_INTERVAL_MS = 3_600_000L
    const val MAX_LOOP_COUNT = 100_000
    const val MAX_RUN_DURATION_MS = 3_600_000L

    /** 两阶段等待参数。 */
    const val COARSE_WINDOW_MS = 200L
    const val FINE_SPIN_WINDOW_MS = 20L

    const val SYNC_VALIDITY_MS = 10 * 60 * 1000L
    const val DEFAULT_SAMPLE_COUNT = 8
    const val DEFAULT_ADVANCE_MS = 0L
    const val DEFAULT_NTP_SERVER = "ntp.aliyun.com"
}
