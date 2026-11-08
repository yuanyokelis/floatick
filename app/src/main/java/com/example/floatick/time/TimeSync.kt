package com.example.floatick.time

import android.os.SystemClock
import com.example.floatick.core.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class SntpSample(val offsetMs: Long, val rttMs: Long, val server: String)

data class SyncResult(
    val success: Boolean,
    val offsetMs: Long = 0L,
    val rttMs: Long = 0L,
    val sampleCount: Int = 0,
    val message: String = ""
)

data class SyncStatus(
    val synced: Boolean = false,
    val offsetMs: Long = 0L,
    val rttMs: Long = 0L,
    val sampleCount: Int = 0,
    val lastSyncElapsedMs: Long = 0L,
    val message: String = "未校时"
)

/**
 * 精简 SNTP 客户端（NTP v3，UDP 123，48 字节报文）。
 * 不用 android.net.SntpClient —— 那是隐藏 API，反射调用在跨版本上不稳定。
 * 时间戳往返用 elapsedRealtime 计时，避免测量期间墙上时钟跳变污染结果。
 */
@Singleton
class SntpClient @Inject constructor() {

    suspend fun sample(server: String, timeoutMs: Int = 3000): SntpSample? =
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                val address = InetAddress.getByName(server)
                socket = DatagramSocket().apply { soTimeout = timeoutMs }

                val request = ByteArray(NTP_PACKET_SIZE)
                request[0] = 0x1B.toByte() // LI=0, VN=3, Mode=3 (client)
                val t1Wall = System.currentTimeMillis()
                val t1Elapsed = SystemClock.elapsedRealtime()
                writeTimestamp(request, 40, wallToNtp(t1Wall))

                socket.send(DatagramPacket(request, request.size, address, NTP_PORT))

                val response = ByteArray(NTP_PACKET_SIZE)
                socket.receive(DatagramPacket(response, response.size))
                val t4Wall = t1Wall + (SystemClock.elapsedRealtime() - t1Elapsed)

                val t2 = readTimestamp(response, 32) // server receive time
                val t3 = readTimestamp(response, 40) // server transmit time
                if (t2 == 0L || t3 == 0L) return@withContext null

                // offset = ((T2-T1) + (T3-T4)) / 2, rtt = (T4-T1) - (T3-T2)
                val offset = ((t2 - t1Wall) + (t3 - t4Wall)) / 2L
                val rtt = (t4Wall - t1Wall) - (t3 - t2)
                if (abs(offset) > 24L * 3600_000L) return@withContext null

                SntpSample(offset, rtt.coerceAtLeast(0L), server)
            } catch (e: Exception) {
                null
            } finally {
                socket?.close()
            }
        }

    private fun wallToNtp(wallMs: Long): Long {
        val seconds = wallMs / 1000L + NTP_EPOCH_OFFSET_SECONDS
        val fraction = (wallMs % 1000L) * FRACTION_SCALE / 1000L
        return (seconds shl 32) or fraction
    }

    private fun readTimestamp(bytes: ByteArray, offset: Int): Long {
        val seconds = readUInt32(bytes, offset)
        val fraction = readUInt32(bytes, offset + 4)
        return (seconds - NTP_EPOCH_OFFSET_SECONDS) * 1000L +
            fraction * 1000L / FRACTION_SCALE
    }

    private fun writeTimestamp(bytes: ByteArray, offset: Int, ntp: Long) {
        val seconds = (ntp ushr 32) and 0xFFFF_FFFFL
        val fraction = ntp and 0xFFFF_FFFFL
        bytes[offset] = (seconds ushr 24).toByte()
        bytes[offset + 1] = (seconds ushr 16).toByte()
        bytes[offset + 2] = (seconds ushr 8).toByte()
        bytes[offset + 3] = seconds.toByte()
        bytes[offset + 4] = (fraction ushr 24).toByte()
        bytes[offset + 5] = (fraction ushr 16).toByte()
        bytes[offset + 6] = (fraction ushr 8).toByte()
        bytes[offset + 7] = fraction.toByte()
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private companion object {
        const val NTP_PORT = 123
        const val NTP_PACKET_SIZE = 48
        const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L // 1900-01-01 -> 1970-01-01
        const val FRACTION_SCALE = 0x1_0000_0000L
    }
}

/**
 * 把「服务器 UTC 时间」映射成单调时钟基准。
 * 全程使用 elapsedRealtime 插值，因此用户改系统时间、NTP 跳变都不会污染倒计时。
 */
@Singleton
class ClockModel @Inject constructor(private val client: SntpClient) {

    private data class State(
        val baseUtcMs: Long,
        val baseElapsedMs: Long,
        val driftMsPerMs: Double,
        val offsetMs: Long,
        val rttMs: Long,
        val sampleCount: Int
    )

    @Volatile
    private var state: State? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** 校时结果在有效期内才算「已校时」；过期后 UI 必须显示未校时。 */
    fun isFresh(): Boolean {
        val s = state ?: return false
        return SystemClock.elapsedRealtime() - s.baseElapsedMs < Limits.SYNC_VALIDITY_MS
    }

    fun statusSnapshot(): SyncStatus {
        val s = _status.value
        return s.copy(synced = isFresh())
    }

    fun lastOffsetMs(): Long = state?.offsetMs ?: 0L
    fun lastRttMs(): Long = state?.rttMs ?: 0L

    /** 当前 UTC 毫秒；未校时时退化为本机墙上时钟（调用方需检查 isFresh）。 */
    fun nowUtcMs(): Long {
        val s = state ?: return System.currentTimeMillis()
        val delta = SystemClock.elapsedRealtime() - s.baseElapsedMs
        return s.baseUtcMs + (delta * (1.0 + s.driftMsPerMs)).toLong()
    }

    /** 目标 UTC 时刻对应的单调时钟毫秒值。 */
    fun utcToElapsedMs(targetUtcMs: Long): Long =
        SystemClock.elapsedRealtime() + (targetUtcMs - nowUtcMs())

    fun invalidate(message: String = "校时已失效，请重新校时") {
        state = null
        _status.value = SyncStatus(message = message)
    }

    suspend fun sync(server: String, sampleCount: Int): SyncResult {
        if (server.isBlank()) return SyncResult(false, message = "请先填写 NTP 服务器")
        val samples = mutableListOf<SntpSample>()
        repeat(sampleCount.coerceIn(4, 32)) {
            client.sample(server)?.let { samples.add(it) }
        }
        if (samples.isEmpty()) {
            invalidate("无法连接 NTP 服务器（UDP 123 可能被网络拦截）")
            return SyncResult(false, message = "无法连接 NTP 服务器（UDP 123 可能被网络拦截）")
        }

        // 最小 RTT 的样本最不受排队延迟影响；再用中位数做离群保护。
        val best = samples.minBy { it.rttMs }
        val sorted = samples.map { it.offsetMs }.sorted()
        val median = sorted[sorted.size / 2]
        val chosen = if (abs(best.offsetMs - median) <= abs(best.rttMs)) best.offsetMs else median

        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = state
        val drift = if (previous != null && nowElapsed > previous.baseElapsedMs) {
            val expectedUtc = previous.baseUtcMs + (nowElapsed - previous.baseElapsedMs)
            val measuredUtc = System.currentTimeMillis() + chosen
            ((measuredUtc - expectedUtc).toDouble() /
                (nowElapsed - previous.baseElapsedMs).toDouble())
                .coerceIn(-1.0e-4, 1.0e-4)
        } else {
            previous?.driftMsPerMs ?: 0.0
        }

        state = State(
            baseUtcMs = System.currentTimeMillis() + chosen,
            baseElapsedMs = nowElapsed,
            driftMsPerMs = drift,
            offsetMs = chosen,
            rttMs = best.rttMs,
            sampleCount = samples.size
        )
        _status.value = SyncStatus(
            synced = true,
            offsetMs = chosen,
            rttMs = best.rttMs,
            sampleCount = samples.size,
            lastSyncElapsedMs = nowElapsed,
            message = "校时完成：offset ${chosen}ms / RTT ${best.rttMs}ms"
        )
        return SyncResult(true, chosen, best.rttMs, samples.size, "校时完成")
    }
}
