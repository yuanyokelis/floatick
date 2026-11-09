package com.example.floatick.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.example.floatick.core.ActionType
import com.example.floatick.core.EngineState
import com.example.floatick.core.Limits
import com.example.floatick.core.RunRecord
import com.example.floatick.core.ScheduleRule
import com.example.floatick.core.Scheme
import com.example.floatick.core.StopReason
import com.example.floatick.data.AppSettings
import com.example.floatick.data.SchemeRepository
import com.example.floatick.data.SettingsRepository
import com.example.floatick.system.ClickAccessibilityService
import com.example.floatick.system.ClickForegroundService
import com.example.floatick.system.FloatingConsole
import com.example.floatick.system.ForegroundApp
import com.example.floatick.system.PermissionCheck
import com.example.floatick.system.Scheduler
import com.example.floatick.time.ClockAnchor
import com.example.floatick.time.ClockModel
import com.example.floatick.time.PrecisionTimer
import com.example.floatick.time.TriggerCalibration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.floatick.core.Step
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class ClickEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timer: PrecisionTimer,
    private val anchor: ClockAnchor,
    private val clockModel: ClockModel,
    private val calibration: TriggerCalibration,
    private val repository: SchemeRepository,
    private val settingsRepository: SettingsRepository,
    private val guard: RunGuard,
    private val scheduler: Scheduler,
    private val console: FloatingConsole
) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerHandle: PrecisionTimer.Cancellable? = null
    private var runJob: Job? = null
    private var armedSchemeId: Long = 0L

    /** 武装一次性定时任务。要求此时 App 前台、屏幕常亮、无障碍已连接。 */
    suspend fun arm(scheme: Scheme, rule: ScheduleRule) {
        cancelInternal()
        val cfg = settingsRepository.snapshot()
        if (cfg.globalDisabled) return fail("已全局停用，请在设置页恢复")
        if (!PermissionCheck.isAccessibilityEnabled(context) ||
            !ClickAccessibilityService.isConnected()
        ) return fail("无障碍服务未连接，请到权限页检查")

        val synced = clockModel.isFresh()
        // 开启校时却没校上时，拒绝以「假装精确」的方式执行。
        if (cfg.syncEnabled && !synced) {
            return fail("已开启校时但尚未成功校时，请先到设置页校时")
        }

        val targetElapsed = anchor.toElapsedMs(rule.triggerUtcMillis)
        if (targetElapsed - cfg.advanceMs <= SystemClock.elapsedRealtime()) {
            return fail("目标时刻已过，请重新设置")
        }

        armedSchemeId = scheme.id
        scheduler.armReminder(scheme.id, rule.triggerUtcMillis)
        _state.value = EngineState(
            armed = true,
            schemeName = scheme.name,
            targetUtcMillis = rule.triggerUtcMillis,
            synced = synced,
            message = if (synced) "已武装（已校时）"
            else "未校时：使用本机时钟，绝对精度不可保证"
        )
        console.update(_state.value)
        startForeground()

        timerHandle = timer.schedule(targetElapsed, cfg.advanceMs) { actualElapsed ->
            val deviation = actualElapsed - (targetElapsed - cfg.advanceMs)
            calibration.record(deviation)
            runJob = scope.launch { execute(scheme, rule, deviation, synced, cfg) }
        }
    }

    /** 立即执行（不等定时），用于手动验证方案。 */
    suspend fun startNow(scheme: Scheme) {
        cancelInternal()
        val cfg = settingsRepository.snapshot()
        if (cfg.globalDisabled) return fail("已全局停用，请在设置页恢复")
        if (!ClickAccessibilityService.isConnected()) return fail("无障碍服务未连接")

        val synced = clockModel.isFresh()
        _state.value = EngineState(
            running = true,
            schemeName = scheme.name,
            synced = synced,
            message = "立即执行"
        )
        console.update(_state.value)
        startForeground()
        runJob = scope.launch {
            execute(scheme, ScheduleRule(scheme.id, clockModel.nowUtcMs()), 0L, synced, cfg)
        }
    }

    fun stop() {
        cancelInternal()
        _state.value = EngineState(message = "已停止")
        console.update(_state.value)
    }

    /** 只发一次手势并回报系统是否接受，用来区分「没派发」和「派发了但目标没响应」。 */
    suspend fun diagnoseOnce(scheme: Scheme): String = withContext(Dispatchers.Main) {
        val service = ClickAccessibilityService.current()
            ?: return@withContext "诊断失败：无障碍服务未连接"
        val step = scheme.effectiveSteps.firstOrNull() ?: Step()
        val w = service.screenWidthPx.toFloat()
        val h = service.screenHeightPx.toFloat()
        val x = step.xRatio * w
        val y = step.yRatio * h
        val duration = step.durationMs.coerceAtLeast(Limits.MIN_GESTURE_DURATION_MS)
        val accepted = service.performTap(x, y, duration)
        val msg = "诊断：dispatchGesture=$accepted · 坐标(${x.toInt()}, ${y.toInt()})" +
            " · 屏幕 ${w.toInt()}×${h.toInt()} · 前台 ${ForegroundApp.current() ?: "未知"}" +
            " · 按下 ${duration}ms"
        _state.value = EngineState(message = msg)
        console.update(_state.value)
        msg
    }

    private suspend fun CoroutineScope.execute(
        scheme: Scheme,
        rule: ScheduleRule,
        deviationMs: Long,
        synced: Boolean,
        cfg: AppSettings
    ) {
        val steps = scheme.effectiveSteps
        val total = scheme.loopCount * steps.size
        val startedAtUtc = clockModel.nowUtcMs()
        val deadline = SystemClock.elapsedRealtime() + Limits.MAX_RUN_DURATION_MS
        var executed = 0
        var reason = StopReason.COMPLETED

        _state.value = EngineState(
            running = true,
            schemeName = scheme.name,
            remainingCount = total,
            targetUtcMillis = rule.triggerUtcMillis,
            synced = synced,
            message = "执行中"
        )
        console.update(_state.value)

        try {
            outer@ for (loop in 0 until scheme.loopCount) {
                for (step in steps) {
                    if (!isActive) {
                        reason = StopReason.USER_STOPPED
                        break@outer
                    }
                    if (SystemClock.elapsedRealtime() > deadline) {
                        reason = StopReason.ERROR
                        break@outer
                    }
                    if (settingsRepository.snapshot().globalDisabled) {
                        reason = StopReason.USER_STOPPED
                        break@outer
                    }
                    if (guard.isProtected(cfg)) {
                        reason = StopReason.PROTECTED_APP
                        break@outer
                    }
                    if (!ClickAccessibilityService.isConnected()) {
                        reason = StopReason.PERMISSION_LOST
                        break@outer
                    }
                    val service = ClickAccessibilityService.current()
                    if (service == null) {
                        reason = StopReason.PERMISSION_LOST
                        break@outer
                    }

                    val w = service.screenWidthPx.toFloat()
                    val h = service.screenHeightPx.toFloat()
                    val duration = step.durationMs.coerceAtLeast(Limits.MIN_GESTURE_DURATION_MS)
                    val ok = when (step.action) {
                        ActionType.SWIPE -> service.performSwipe(
                            step.xRatio * w, step.yRatio * h,
                            step.endXRatio * w, step.endYRatio * h,
                            duration
                        )
                        ActionType.LONG_PRESS -> service.performTap(
                            step.xRatio * w, step.yRatio * h, duration
                        )
                        ActionType.CLICK -> service.performTap(
                            step.xRatio * w, step.yRatio * h, duration
                        )
                    }
                    if (!ok) {
                        reason = StopReason.ERROR
                        break@outer
                    }

                    executed++
                    _state.value = _state.value.copy(remainingCount = total - executed)
                    console.update(_state.value)

                    // 步骤间隔对三种手势一律生效；至少等 gesture 本身走完。
                    val wait = max(
                        max(step.delayAfterMs, scheme.intervalMs),
                        duration
                    )
                    delay(wait)
                }
            }
        } catch (e: CancellationException) {
            reason = StopReason.USER_STOPPED
            throw e
        } catch (e: Exception) {
            reason = StopReason.ERROR
        } finally {
            withContext(NonCancellable) {
                repository.log(
                    RunRecord(
                        schemeId = scheme.id,
                        targetUtcMillis = rule.triggerUtcMillis,
                        offsetMs = clockModel.lastOffsetMs(),
                        rttMs = clockModel.lastRttMs(),
                        deviationMs = deviationMs,
                        executedCount = executed,
                        stopReason = reason,
                        synced = synced,
                        startedAtUtcMillis = startedAtUtc
                    )
                )
                scheduler.cancelReminder(scheme.id)
                stopForeground()
                _state.value = EngineState(
                    message = "已结束 · ${reason.name} · 共 $executed 次 · 触发偏差 ${deviationMs}ms"
                )
                console.update(_state.value)
            }
        }
    }

    private fun cancelInternal() {
        timerHandle?.cancel()
        timerHandle = null
        runJob?.cancel()
        runJob = null
        if (armedSchemeId != 0L) {
            scheduler.cancelReminder(armedSchemeId)
            armedSchemeId = 0L
        }
        stopForeground()
    }

    private fun fail(message: String) {
        _state.value = EngineState(message = message)
        console.update(_state.value)
    }

    /**
     * Android 12+ 在应用处于后台时启动前台服务会抛
     * ForegroundServiceStartNotAllowedException（常见于从悬浮窗点「开始」）。
     * 不能让它把整个进程带崩——否则悬浮窗会随进程一起消失。
     * 捕获后任务仍照常执行，只是少了保活，并明确提示用户。
     */
    private fun startForeground() {
        runCatching {
            val intent = Intent(context, ClickForegroundService::class.java)
                .setAction(ClickForegroundService.ACTION_ARM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { error ->
            _state.value = _state.value.copy(
                message = "前台服务启动受限（${error.javaClass.simpleName}）；" +
                    "任务仍会执行，但请回到 App 前台以免被系统回收"
            )
            console.update(_state.value)
        }
    }

    private fun stopForeground() {
        runCatching {
            context.stopService(Intent(context, ClickForegroundService::class.java))
        }
    }
}
