package com.baiyunkeys.core.unlock

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 蓝牙适配器状态（对齐 `CBManagerState`）。 */
enum class BleState {
    Unknown,
    Resetting,
    Unsupported,
    Unauthorized,
    PoweredOff,
    PoweredOn,
    ;

    val isReady: Boolean get() = this == PoweredOn
}

/** 扫描时的服务过滤。前台不按服务过滤（不同门锁 UUID 不一致），后台才带已知 UUID。 */
data class ScanFilter(val serviceUuids: List<UUID> = emptyList()) {
    val isEmpty: Boolean get() = serviceUuids.isEmpty()

    companion object {
        val None = ScanFilter()
    }
}

/**
 * 一次扫描命中。
 *
 * Android 的 [deviceId] 就是 `BluetoothDevice.address`（MAC），这是相对 iOS 的结构性优势：
 * 可以先用 MAC 精确匹配，匹配不到再看蓝牙名 / 厂商数据。
 */
data class ScanHit(
    val deviceId: String,
    val name: String?,
    val rssi: Int,
    val manufacturerData: ByteArray? = null,
) {
    // data class + ByteArray：手写 equals/hashCode，避免数组引用比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanHit) return false
        return deviceId == other.deviceId &&
            name == other.name &&
            rssi == other.rssi &&
            (manufacturerData?.contentEquals(other.manufacturerData)
                ?: (other.manufacturerData == null))
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + rssi
        result = 31 * result + (manufacturerData?.contentHashCode() ?: 0)
        return result
    }
}

/** 找到的可用服务：同一服务里同时具备可读 + 可写特征，附带可订阅的通知特征。 */
data class BleService(
    val uuid: UUID,
    val readUuid: UUID,
    val writeUuid: UUID,
    val notifyUuids: List<UUID>,
    /** 写特征是否支持「有响应写」。false 时走 writeWithoutResponse。 */
    val writeWithResponse: Boolean,
)

/** 服务发现结果：要区分「没有服务」（可重试）和「有服务但没有读写特征」（协议层失败）。 */
sealed interface ServiceDiscovery {
    data class Found(val service: BleService) : ServiceDiscovery
    data object NoServices : ServiceDiscovery
    data object NoReadWriteCharacteristics : ServiceDiscovery
}

interface BleSession : AutoCloseable {
    val deviceId: String

    suspend fun findService(): ServiceDiscovery

    /** 订阅通知特征；回调可能来自 BLE 线程，实现方负责切主线程或线程安全。 */
    suspend fun subscribe(uuid: UUID, onValue: (ByteArray) -> Unit)

    suspend fun read(uuid: UUID): ByteArray

    suspend fun write(uuid: UUID, data: ByteArray)
}

/**
 * 蓝牙适配器抽象。core 层不 import 任何 `android.*`，真实现放在 `app/ble/BleAdapterImpl.kt`，
 * 测试里换成 [FakeBleAdapter]（见 core 测试）。
 */
interface BleAdapter {

    val state: StateFlow<BleState>

    /**
     * 扫描附近的设备。
     *
     * @param filter 服务 UUID 过滤；前台传 [ScanFilter.None]，靠名字 / MAC 匹配
     * @param allowDuplicates 是否重复回调同一设备（蓝牙扫描面板需要，开锁流程不需要）
     */
    fun scan(filter: ScanFilter = ScanFilter.None, allowDuplicates: Boolean = false): Flow<ScanHit>

    /** 连接设备；[deviceId] 在 Android 上是 MAC。失败时抛异常。 */
    suspend fun connect(deviceId: String, timeoutMs: Long): BleSession
}
