package com.baiyunkeys.core.model

import com.baiyunkeys.core.hex.Hex
import kotlinx.serialization.Serializable

/**
 * 一套门禁参数。`key`（productKey）与 `mac`（macNum）来自官方下发的门禁信息。
 *
 * 与 `BaiyunKeys-iOS/BaiyunKeys/Core/DoorConfig.swift` 字段一一对应。
 * Android 能直接读到外设 MAC，所以 [bluetoothName] 从「必填」降级为「可选」，
 * 但导入 / 导出格式保持一致，空值仍要能正确往返。
 */
@Serializable
data class DoorConfig(
    val id: String = newId(),
    val name: String = "",
    val mac: String = "",
    val key: String = "",
    val bluetoothName: String = "",
    /** 上次成功连接的外设标识。Android 上是 MAC；iOS 上是 CoreBluetooth UUID。 */
    val cachedPeripheralId: String? = null,
) {
    val displayName: String
        get() = if (name.isBlank()) "未命名门禁" else name

    /**
     * 是否可用。
     *
     * Android 能直接读 `BluetoothDevice.address`，扫描匹配靠 MAC 就够，所以 [bluetoothName]
     * 不是硬性条件（这是相对 iOS 的放宽：iOS 只认广播名）。导入时若没抄蓝牙名也照收。
     */
    val isUsable: Boolean
        get() = Hex.isValidMac(mac) && Hex.isValidKey(key)

    /** 列表展示用的脱敏 MAC（注意是全角星号，与 iOS 视觉一致）。 */
    fun maskedMac(hidden: Boolean): String {
        if (!hidden || !Hex.isValidMac(mac)) return mac.ifEmpty { "未填写" }
        val parts = mac.split(":")
        if (parts.size != 6) return mac
        return (parts.take(2) + listOf("＊＊", "＊＊", "＊＊") + parts[5]).joinToString(":")
    }

    fun maskedKey(hidden: Boolean): String {
        if (key.isEmpty()) return "未填写"
        if (!hidden) return key
        if (key.length <= 8) return "＊".repeat(key.length)
        return key.take(4) + "＊".repeat(key.length - 8) + key.takeLast(4)
    }

    companion object {
        /** 本机生成的门禁 id：`door_` + 8 位小写 hex。只在单机内有效。 */
        fun newId(): String =
            "door_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8).lowercase()
    }
}

/** 门禁列表 + 当前选中项，对应 `DoorState`（`version` 已经是 2）。 */
@Serializable
data class DoorState(
    val version: Int = 2,
    val currentId: String? = null,
    val items: List<DoorConfig> = emptyList(),
) {
    val current: DoorConfig?
        get() = currentId?.let { id -> items.firstOrNull { it.id == id } } ?: items.firstOrNull()
}
