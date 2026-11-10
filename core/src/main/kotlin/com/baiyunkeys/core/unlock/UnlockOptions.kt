package com.baiyunkeys.core.unlock

import java.time.Clock
import java.time.Instant

/** 开锁选项。默认值对应 iOS `AppStore` 的初始状态（不开自动重试、扫描 10s）。 */
data class UnlockOptions(
    val autoRetry: Boolean = false,
    val autoRetryCount: Int = 1,
    val scanTimeoutMs: Long = 10_000,
    val scanFilter: ScanFilter = ScanFilter.None,
    /** 静默模式：失败不写历史、不震动、不落到 Failure 阶段。 */
    val silent: Boolean = false,
) {
    val maxAttempts: Int get() = if (autoRetry) maxOf(1, autoRetryCount) else 1
}

/** 一次开锁的结果。 */
data class UnlockResult(val success: Boolean, val message: String)

/** 一条开锁记录（对应 `AppStore.UnlockRecord`，历史保留 50 条）。 */
@kotlinx.serialization.Serializable
data class UnlockRecord(
    /** 毫秒时间戳。`java.time.Instant` 没有内置 serializer，存 long 更稳。 */
    val atEpochMillis: Long,
    val doorId: String,
    val doorName: String,
    val ok: Boolean,
    val message: String,
) {
    val at: Instant get() = Instant.ofEpochMilli(atEpochMillis)

    companion object {
        fun now(
            clock: Clock,
            doorId: String,
            doorName: String,
            ok: Boolean,
            message: String,
        ): UnlockRecord = UnlockRecord(
            atEpochMillis = clock.millis(),
            doorId = doorId,
            doorName = doorName,
            ok = ok,
            message = message,
        )
    }
}

/** 开锁历史的写入点。app 层实现成 DataStore，测试里用内存实现。 */
fun interface UnlockHistory {
    fun add(record: UnlockRecord)
}

/** 上次成功连接的外设缓存（Android 上是 MAC），用于跳过扫描直连。 */
interface PeripheralCache {
    fun put(doorId: String, deviceId: String)
    fun clear(doorId: String)
}

/** 触感反馈；core 层不依赖 Android，具体实现放 app。 */
interface HapticsFeedback {
    fun success()
    fun failure()

    companion object {
        val None = object : HapticsFeedback {
            override fun success() = Unit
            override fun failure() = Unit
        }
    }
}

/**
 * 各阶段超时。数值直接照搬 iOS `UnlockService` 的常量：
 * 连接 / 服务发现 8s、读随机数 5s、回执 20s；扫描超时由用户配置（3–60s，默认 10s）。
 */
data class UnlockTimeouts(
    val connectMs: Long = 8_000,
    val seedMs: Long = 5_000,
    val ackMs: Long = 20_000,
    val retryDelayMs: Long = 300,
)
