package com.example.floatick.time

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.example.floatick.core.Limits
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.abs

data class CalibrationStats(val count: Int, val p50Ms: Long, val p95Ms: Long)

internal fun List<Long>.toCalibrationStats(): CalibrationStats {
    if (isEmpty()) return CalibrationStats(0, 0, 0)
    val sorted = map { abs(it) }.sorted()
    val p95Index = ((size * 0.95).toInt() - 1).coerceIn(0, size - 1)
    return CalibrationStats(size, sorted[size / 2], sorted[p95Index])
}

/** 把目标 UTC 时刻换算成单调时钟上的触发点。 */
@Singleton
class ClockAnchor @Inject constructor(private val clockModel: ClockModel) {
    fun toElapsedMs(targetUtcMs: Long): Long = clockModel.utcToElapsedMs(targetUtcMs)
}

/**
 * 两阶段等待：
 *  1) 粗等待 —— Handler.postDelayed 睡到触发前 FINE_SPIN_WINDOW_MS；
 *  2) 细等待 —— 在专用计时线程上用 elapsedRealtime + parkNanos 逼近。
 *
 * 刻意不用 Choreographer：帧回调被 VSYNC 锁在 16.7ms(60Hz)/8.3ms(120Hz)，
 * 比独立线程轮询更差。也刻意不用 Thread.sleep 做最终判定。
 */
@Singleton
class PrecisionTimer @Inject constructor() {

    fun interface Cancellable {
        fun cancel()
    }

    private val thread = HandlerThread("floatick-precision").apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * @param targetElapsedMs 目标单调时钟时刻
     * @param advanceMs 提前量补偿：Binder 传递与 dispatchGesture 派发的固有延迟
     * @param onGo 在计时线程回调，参数是实际发出 GO 的单调时钟值
     */
    fun schedule(
        targetElapsedMs: Long,
        advanceMs: Long,
        onGo: (actualElapsedMs: Long) -> Unit
    ): Cancellable {
        val fireAt = targetElapsedMs - advanceMs
        val cancelled = AtomicBoolean(false)
        val task = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                val remaining = fireAt - SystemClock.elapsedRealtime()
                if (remaining > Limits.FINE_SPIN_WINDOW_MS) {
                    handler.postDelayed(
                        this,
                        (remaining - Limits.FINE_SPIN_WINDOW_MS).coerceAtLeast(1L)
                    )
                    return
                }
                while (!cancelled.get() && SystemClock.elapsedRealtime() < fireAt) {
                    LockSupport.parkNanos(100_000L) // 0.1ms
                }
                if (!cancelled.get()) onGo(SystemClock.elapsedRealtime())
            }
        }
        handler.post(task)
        return Cancellable {
            cancelled.set(true)
            handler.removeCallbacks(task)
        }
    }
}

/** 记录每次触发的偏差，供 UI 显示 P50/P95 并指导 advanceMs 校准。 */
@Singleton
class TriggerCalibration @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("floatick_calibration", Context.MODE_PRIVATE)
    private val samples: MutableList<Long> = prefs.getString("samples", "")
        .orEmpty()
        .split(',')
        .mapNotNull { it.toLongOrNull() }
        .takeLast(200)
        .toMutableList()

    fun record(deviationMs: Long) {
        synchronized(samples) {
            samples.add(deviationMs)
            if (samples.size > 200) samples.removeAt(0)
            prefs.edit().putString("samples", samples.joinToString(",")).apply()
        }
    }

    fun stats(): CalibrationStats = synchronized(samples) { samples.toList() }.toCalibrationStats()

    fun clear() {
        synchronized(samples) { samples.clear() }
        prefs.edit().remove("samples").apply()
    }
}

/** 空跑自测：只计时不派发手势，用来实测本机触发抖动分布。 */
@Singleton
class DryRunBenchmark @Inject constructor(private val timer: PrecisionTimer) {

    suspend fun run(count: Int = 20, spacingMs: Long = 100L): CalibrationStats =
        withContext(Dispatchers.Default) {
            val deviations = mutableListOf<Long>()
            repeat(count.coerceIn(1, 100)) {
                val target = SystemClock.elapsedRealtime() + spacingMs
                val actual = suspendCancellableCoroutine<Long> { cont ->
                    val handle = timer.schedule(target, 0L) { elapsed -> cont.resume(elapsed) }
                    cont.invokeOnCancellation { handle.cancel() }
                }
                deviations.add(actual - target)
            }
            deviations.toCalibrationStats()
        }
}
