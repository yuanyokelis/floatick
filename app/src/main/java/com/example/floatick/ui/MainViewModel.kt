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
    private val benchmark: DryRunBenchmark
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

    fun startNow(scheme: Scheme) = viewModelScope.launch { engine.startNow(scheme) }

    fun startFirstScheme() = viewModelScope.launch {
        val first = repository.all().firstOrNull()
        if (first == null) {
            _message.value = "还没有方案，请先新建"
        } else {
            engine.startNow(first)
        }
    }

    fun stop() = engine.stop()

    fun arm(scheme: Scheme, triggerUtcMillis: Long) = viewModelScope.launch {
        val rule = ScheduleRule(scheme.id, triggerUtcMillis, true)
        repository.putSchedule(rule)
        engine.arm(scheme, rule)
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
}
