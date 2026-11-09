package com.example.floatick.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.floatick.core.EngineState
import com.example.floatick.core.ScheduleRule
import com.example.floatick.core.Scheme
import com.example.floatick.data.AppSettings
import com.example.floatick.data.BackupManager
import com.example.floatick.data.RunLogEntity
import com.example.floatick.data.SchemeRepository
import com.example.floatick.data.SettingsRepository
import com.example.floatick.engine.ClickEngine
import com.example.floatick.system.ClickAccessibilityService
import com.example.floatick.system.FloatingConsole
import com.example.floatick.time.CalibrationStats
import com.example.floatick.time.ClockModel
import com.example.floatick.time.DryRunBenchmark
import com.example.floatick.time.TriggerCalibration
import com.example.floatick.time.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: SchemeRepository,
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
    private val engine: ClickEngine,
    private val clockModel: ClockModel,
    private val calibration: TriggerCalibration,
    private val benchmark: DryRunBenchmark,
    private val console: FloatingConsole
) : ViewModel() {

    val schemes: StateFlow<List<Scheme>> = repository.observeSchemes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val logs: StateFlow<List<RunLogEntity>> = repository.observeLogs(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineState: StateFlow<EngineState> = engine.state
    val syncStatus: StateFlow<SyncStatus> = clockModel.status

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _benchmarkResult = MutableStateFlow<CalibrationStats?>(null)
    val benchmarkResult: StateFlow<CalibrationStats?> = _benchmarkResult.asStateFlow()

    private val _picked = MutableStateFlow<Pair<Float, Float>?>(null)
    val picked: StateFlow<Pair<Float, Float>?> = _picked.asStateFlow()

    private val _backupText = MutableStateFlow("")
    val backupText: StateFlow<String> = _backupText.asStateFlow()

    private var lastForwardedError = ""

    init {
        // 引擎的失败原因不能只写在悬浮框里：悬浮框一旦被 ROM 回收，
        // 用户就完全看不到「为什么没点击」。这里把错误类消息同步弹成对话框。
        viewModelScope.launch {
            engine.state.collect { state ->
                val msg = state.message
                if (msg.isNotBlank() && msg != lastForwardedError &&
                    ERROR_HINTS.any { msg.contains(it) }
                ) {
                    lastForwardedError = msg
                    _message.value = msg
                }
            }
        }
    }

    fun notify(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = ""
    }

    suspend fun scheme(id: Long): Scheme? = repository.get(id)

    suspend fun scheduleFor(id: Long): ScheduleRule? = repository.scheduleFor(id)

    fun save(scheme: Scheme, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = repository.save(scheme)
        onSaved(id)
        _message.value = "方案已保存"
    }

    fun delete(id: Long) = viewModelScope.launch {
        repository.delete(id)
        _message.value = "方案已删除"
    }

    private suspend fun requireAccessibility(): Boolean {
        if (ClickAccessibilityService.isConnected()) return true
        _message.value =
            "无障碍服务未连接，手势不会生效。请到「权限」页开启；" +
                "Android 13+ 侧载需先在应用详情页选择「允许受限设置」。" +
                "若已开启，请关闭再打开一次后点「重新检测」。"
        return false
    }

    fun startNow(scheme: Scheme) = viewModelScope.launch {
        if (requireAccessibility()) engine.startNow(scheme)
    }

    fun startFirstScheme() = viewModelScope.launch {
        val first = repository.all().firstOrNull()
        if (first == null) {
            _message.value = "还没有方案，请先新建"
        } else if (requireAccessibility()) {
            engine.startNow(first)
        }
    }

    fun stop() = engine.stop()

    /**
     * 悬浮控制台「5秒」：默认方案 5 秒后触发一次，留出切到目标 App 的时间。
     * 刻意不写 `_message`——人往往已经切走，App 内对话框等回到前台才弹，反而像「莫名其妙报错」；
     * 状态由悬浮台的状态行自己显示。
     */
    fun armDelayedFirst() = viewModelScope.launch {
        val first = repository.all().firstOrNull()
        if (first == null) {
            _message.value = "还没有方案，请先新建"
            return@launch
        }
        if (!requireAccessibility()) return@launch
        val rule = ScheduleRule(first.id, clockModel.nowUtcMs() + 5_000L, true)
        repository.putSchedule(rule)
        engine.arm(first, rule)
    }

    fun arm(scheme: Scheme, triggerUtcMillis: Long) = viewModelScope.launch {
        if (!requireAccessibility()) return@launch
        val rule = ScheduleRule(scheme.id, triggerUtcMillis, true)
        repository.putSchedule(rule)
        engine.arm(scheme, rule)
    }

    /** 3 秒后发一次手势并回报派发结果，用于快速定位「到底点没点」。 */
    fun diagnose(scheme: Scheme) = viewModelScope.launch {
        if (!requireAccessibility()) return@launch
        _message.value = "3 秒后在当前前台窗口点击一次，请立刻切到目标页面"
        delay(3_000L)
        _message.value = engine.diagnoseOnce(scheme)
    }

    /** 给一点时间切到目标 App 后再触发，用它验证点击是否真的生效。 */
    fun armDelayed(scheme: Scheme, delayMs: Long = 5_000L) = viewModelScope.launch {
        if (!requireAccessibility()) return@launch
        val rule = ScheduleRule(scheme.id, clockModel.nowUtcMs() + delayMs, true)
        repository.putSchedule(rule)
        engine.arm(scheme, rule)
        _message.value = "已武装：${delayMs / 1000} 秒后点击当前前台窗口，请立刻切到目标页面"
    }

    /** 悬浮控制台「武装」：用已保存的定时规则重新启动等待。 */
    fun armSavedFirst() = viewModelScope.launch {
        val first = repository.all().firstOrNull()
        if (first == null) {
            _message.value = "还没有方案，请先新建"
            return@launch
        }
        val rule = repository.scheduleFor(first.id)
        if (rule == null) {
            _message.value = "还没设定时间，请先在该方案里点「定时」设置一个未来时刻"
            return@launch
        }
        if (requireAccessibility()) engine.arm(first, rule)
    }

    fun cancelArm(schemeId: Long) = viewModelScope.launch {
        repository.clearSchedule(schemeId)
        engine.stop()
    }

    fun sync() = viewModelScope.launch {
        val cfg = settingsRepository.snapshot()
        if (!cfg.syncEnabled) {
            _message.value = "校时未开启，请先在设置页打开"
            return@launch
        }
        val result = clockModel.sync(cfg.ntpServer, cfg.sampleCount)
        _message.value = if (result.success) {
            "校时完成：offset ${result.offsetMs}ms / RTT ${result.rttMs}ms / ${result.sampleCount} 个样本"
        } else {
            result.message
        }
    }

    fun dryRun() = viewModelScope.launch {
        _message.value = "正在空跑 20 次…"
        val result = benchmark.run(20, 120L)
        _benchmarkResult.value = result
        _message.value = "空跑完成：P50 ${result.p50Ms}ms / P95 ${result.p95Ms}ms"
    }

    fun calibrationStats(): CalibrationStats = calibration.stats()

    fun nowUtcMillis(): Long = clockModel.nowUtcMs()

    fun isSynced(): Boolean = clockModel.isFresh()

    fun overlayError(): String? = console.overlayError()

    fun setSyncEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setSyncEnabled(value)
        if (!value) clockModel.invalidate("校时已关闭，改用本机时钟")
    }

    fun setNtpServer(value: String) = viewModelScope.launch {
        settingsRepository.setNtpServer(value)
    }

    fun setSampleCount(value: Int) = viewModelScope.launch {
        settingsRepository.setSampleCount(value)
    }

    fun setAdvanceMs(value: Long) = viewModelScope.launch {
        settingsRepository.setAdvanceMs(value)
    }

    fun setProtectedPackages(values: List<String>) = viewModelScope.launch {
        settingsRepository.setProtectedPackages(values)
    }

    fun setGlobalDisabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setGlobalDisabled(value)
        if (value) engine.stop()
    }

    fun setPicked(x: Float, y: Float) {
        _picked.value = x to y
    }

    fun consumePicked() {
        _picked.value = null
    }

    /**
     * 悬浮台「取点」专用。
     *
     * 控制台的「立即 / 武装」操作的是第一个方案，所以取点也必须落到同一个方案——
     * 以前这里走的是 [setPicked]，坐标只进了 `_picked` 这个 StateFlow，
     * 而消费它的只有编辑页；人在桌面点「取点」时编辑页根本没在组合，
     * 于是十字消失后什么都没发生，看起来就是「没反应」。
     */
    fun applyConsolePick(x: Float, y: Float, onHint: (String) -> Unit) = viewModelScope.launch {
        val scheme = repository.all().firstOrNull()
        if (scheme == null) {
            onHint("还没有方案：先回 App 新建并保存一个方案，再来取点")
            return@launch
        }
        val steps = scheme.effectiveSteps
        val updated = scheme.copy(
            steps = steps.mapIndexed { index, step ->
                if (index == 0) step.copy(xRatio = x, yRatio = y) else step
            }
        )
        repository.save(updated)
        val text = "已把「${scheme.name}」第 1 步改到 " +
            "X=${round3(x)} Y=${round3(y)}" +
            (if (steps.size > 1) "（多步骤方案只改了第 1 步）" else "")
        onHint(text)
    }

    private fun round3(value: Float): String =
        String.format(java.util.Locale.US, "%.3f", value)

    fun exportBackup() = viewModelScope.launch {
        _backupText.value = backupManager.exportJson()
        _message.value = "已导出到下方文本框"
    }

    fun importBackup() = viewModelScope.launch {
        val result = runCatching { backupManager.importJson(_backupText.value) }
        _message.value = result.fold(
            onSuccess = { "已导入 $it 个方案" },
            onFailure = { "导入失败：${it.message}" }
        )
    }

    fun onBackupTextChange(text: String) {
        _backupText.value = text
    }

    private companion object {
        val ERROR_HINTS = listOf(
            "未连接", "未开", "已过", "尚未成功校时", "启动受限",
            "全局停用", "前台服务", "已结束", "受保护", "屏幕熄灭", "出错"
        )
    }
}
