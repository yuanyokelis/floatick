package com.example.floatick.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.floatick.core.ActionType
import com.example.floatick.core.Limits
import com.example.floatick.core.RunRecord
import com.example.floatick.core.ScheduleRule
import com.example.floatick.core.Scheme
import com.example.floatick.core.SchemeType
import com.example.floatick.core.Step
import com.example.floatick.core.StopReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    /** 校时默认关闭：不开启就绝不产生任何网络请求。 */
    val syncEnabled: Boolean = false,
    val ntpServer: String = Limits.DEFAULT_NTP_SERVER,
    val sampleCount: Int = Limits.DEFAULT_SAMPLE_COUNT,
    val advanceMs: Long = Limits.DEFAULT_ADVANCE_MS,
    val protectedPackages: List<String> = emptyList(),
    /** 一键全局停用：打开后所有定时任务与手势注入都被拒绝。 */
    val globalDisabled: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val NTP_SERVER = stringPreferencesKey("ntp_server")
        val SAMPLE_COUNT = intPreferencesKey("sample_count")
        val ADVANCE_MS = longPreferencesKey("advance_ms")
        val PROTECTED_PACKAGES = stringPreferencesKey("protected_packages")
        val GLOBAL_DISABLED = booleanPreferencesKey("global_disabled")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            AppSettings(
                syncEnabled = p[Keys.SYNC_ENABLED] ?: false,
                ntpServer = p[Keys.NTP_SERVER] ?: Limits.DEFAULT_NTP_SERVER,
                sampleCount = p[Keys.SAMPLE_COUNT] ?: Limits.DEFAULT_SAMPLE_COUNT,
                advanceMs = p[Keys.ADVANCE_MS] ?: Limits.DEFAULT_ADVANCE_MS,
                protectedPackages = (p[Keys.PROTECTED_PACKAGES] ?: "")
                    .split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                globalDisabled = p[Keys.GLOBAL_DISABLED] ?: false
            )
        }

    suspend fun snapshot(): AppSettings = settings.first()

    private suspend fun write(block: MutablePreferences.() -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }

    suspend fun setSyncEnabled(value: Boolean) = write { this[Keys.SYNC_ENABLED] = value }
    suspend fun setNtpServer(value: String) = write { this[Keys.NTP_SERVER] = value.trim() }
    suspend fun setSampleCount(value: Int) = write {
        this[Keys.SAMPLE_COUNT] = value.coerceIn(4, 32)
    }
    suspend fun setAdvanceMs(value: Long) = write { this[Keys.ADVANCE_MS] = value }
    suspend fun setProtectedPackages(values: List<String>) = write {
        this[Keys.PROTECTED_PACKAGES] = values.joinToString("\n")
    }
    suspend fun setGlobalDisabled(value: Boolean) = write { this[Keys.GLOBAL_DISABLED] = value }
}

@Singleton
class SchemeRepository @Inject constructor(private val dao: AppDao) {

    fun observeSchemes(): Flow<List<Scheme>> =
        dao.observeSchemes().map { rows -> rows.map { it.toDomain() } }

    suspend fun all(): List<Scheme> = dao.observeSchemes().first().map { it.toDomain() }

    suspend fun get(id: Long): Scheme? = dao.getScheme(id)?.toDomain()

    suspend fun save(scheme: Scheme): Long {
        val entity = SchemeEntity(
            id = scheme.id,
            name = scheme.name,
            type = scheme.type.name,
            loopCount = scheme.loopCount.coerceIn(1, Limits.MAX_LOOP_COUNT),
            intervalMs = scheme.intervalMs.coerceIn(Limits.MIN_INTERVAL_MS, Limits.MAX_INTERVAL_MS),
            enabled = scheme.enabled
        )
        val id = if (scheme.id == 0L) dao.insertScheme(entity) else {
            dao.updateScheme(entity)
            scheme.id
        }
        dao.deleteSteps(id)
        dao.insertSteps(scheme.effectiveSteps.mapIndexed { index, step ->
            step.toEntity(id, index)
        })
        return id
    }

    suspend fun delete(id: Long) = dao.deleteSchemeById(id)

    suspend fun putSchedule(rule: ScheduleRule) = dao.putSchedule(
        ScheduleEntity(rule.schemeId, rule.triggerUtcMillis, rule.enabled)
    )

    suspend fun scheduleFor(id: Long): ScheduleRule? = dao.scheduleFor(id)?.let {
        ScheduleRule(it.schemeId, it.triggerUtcMillis, it.enabled)
    }

    suspend fun allSchedules(): List<ScheduleRule> = dao.allSchedules().map {
        ScheduleRule(it.schemeId, it.triggerUtcMillis, it.enabled)
    }

    suspend fun clearSchedule(id: Long) = dao.deleteSchedule(id)

    suspend fun activeScheduleCount(): Int = dao.activeScheduleCount()

    suspend fun log(record: RunRecord) = dao.insertLog(
        RunLogEntity(
            schemeId = record.schemeId,
            targetUtcMillis = record.targetUtcMillis,
            offsetMs = record.offsetMs,
            rttMs = record.rttMs,
            deviationMs = record.deviationMs,
            executedCount = record.executedCount,
            stopReason = record.stopReason.name,
            synced = record.synced,
            startedAtUtcMillis = record.startedAtUtcMillis
        )
    )

    fun observeLogs(limit: Int = 50): Flow<List<RunLogEntity>> = dao.observeLogs(limit)
}

@Singleton
class BackupManager @Inject constructor(private val repository: SchemeRepository) {

    suspend fun exportJson(): String {
        val root = JSONObject().put("version", 1)
        val schemes = JSONArray()
        repository.all().forEach { schemes.put(it.toJson()) }
        root.put("schemes", schemes)
        return root.toString(2)
    }

    /** 导入为「新建」，不覆盖已有 id，避免误伤现有配置。 */
    suspend fun importJson(json: String): Int {
        val root = JSONObject(json)
        require(root.optInt("version", 1) == 1) { "不支持的备份版本" }
        val schemes = root.getJSONArray("schemes")
        var count = 0
        for (i in 0 until schemes.length()) {
            repository.save(schemes.getJSONObject(i).toScheme())
            count++
        }
        return count
    }

    private fun Scheme.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("type", type.name)
        .put("loopCount", loopCount)
        .put("intervalMs", intervalMs)
        .put("enabled", enabled)
        .put("steps", JSONArray().also { arr ->
            effectiveSteps.forEach { step ->
                arr.put(JSONObject()
                    .put("xRatio", step.xRatio.toDouble())
                    .put("yRatio", step.yRatio.toDouble())
                    .put("endXRatio", step.endXRatio.toDouble())
                    .put("endYRatio", step.endYRatio.toDouble())
                    .put("action", step.action.name)
                    .put("durationMs", step.durationMs)
                    .put("delayAfterMs", step.delayAfterMs))
            }
        })

    private fun JSONObject.toScheme(): Scheme {
        val stepsJson = optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsJson.length()).map { i ->
            val s = stepsJson.getJSONObject(i)
            Step(
                orderIndex = i,
                xRatio = s.optDouble("xRatio", 0.5).toFloat(),
                yRatio = s.optDouble("yRatio", 0.5).toFloat(),
                endXRatio = s.optDouble("endXRatio", 0.5).toFloat(),
                endYRatio = s.optDouble("endYRatio", 0.5).toFloat(),
                action = runCatching { ActionType.valueOf(s.optString("action", "CLICK")) }
                    .getOrDefault(ActionType.CLICK),
                durationMs = s.optLong("durationMs", 30L),
                delayAfterMs = s.optLong("delayAfterMs", 0L)
            )
        }
        return Scheme(
            name = optString("name", "导入方案"),
            type = runCatching { SchemeType.valueOf(optString("type", "SINGLE_REPEAT")) }
                .getOrDefault(SchemeType.SINGLE_REPEAT),
            steps = steps,
            loopCount = optInt("loopCount", 1),
            intervalMs = optLong("intervalMs", 100L),
            enabled = optBoolean("enabled", true)
        )
    }
}

private fun SchemeWithSteps.toDomain(): Scheme = Scheme(
    id = scheme.id,
    name = scheme.name,
    type = runCatching { SchemeType.valueOf(scheme.type) }
        .getOrDefault(SchemeType.SINGLE_REPEAT),
    steps = steps.sortedBy { it.orderIndex }.map { it.toDomain() },
    loopCount = scheme.loopCount,
    intervalMs = scheme.intervalMs,
    enabled = scheme.enabled
)

private fun StepEntity.toDomain(): Step = Step(
    id = id,
    orderIndex = orderIndex,
    xRatio = xRatio,
    yRatio = yRatio,
    endXRatio = endXRatio,
    endYRatio = endYRatio,
    action = runCatching { ActionType.valueOf(action) }.getOrDefault(ActionType.CLICK),
    durationMs = durationMs,
    delayAfterMs = delayAfterMs
)

private fun Step.toEntity(schemeId: Long, index: Int): StepEntity = StepEntity(
    schemeId = schemeId,
    orderIndex = index,
    xRatio = xRatio,
    yRatio = yRatio,
    endXRatio = endXRatio,
    endYRatio = endYRatio,
    action = action.name,
    durationMs = durationMs.coerceAtLeast(Limits.MIN_GESTURE_DURATION_MS),
    delayAfterMs = delayAfterMs.coerceAtLeast(0L)
)
