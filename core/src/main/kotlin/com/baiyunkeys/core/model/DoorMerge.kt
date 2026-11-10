package com.baiyunkeys.core.model

import com.baiyunkeys.core.hex.Hex

/**
 * 批量入库：按 **MAC + Key** 去重，对应 iOS `AppStore.merge(_:)`。
 *
 * 语义（逐条照搬）：
 * - 先把每条的 MAC 规范成 `AA:BB:CC:DD:EE:FF`、Key 只留十六进制；
 * - 规范化后不合法（MAC 不是 6 组、Key 不是 16~32 位偶数）**直接丢掉**，不报错；
 * - 已存在同 MAC + 同 Key 的：只补齐缺失的蓝牙名与名称，不覆盖已有值；
 * - 否则追加，`added` 加一；
 * - 列表原本没有选中项时，选中第一条。
 */
object DoorMerge {

    data class Result(val state: DoorState, val added: Int)

    fun merge(current: DoorState, incoming: List<DoorConfig>): Result {
        val items = current.items.toMutableList()
        var added = 0

        for (raw in incoming) {
            val mac = Hex.formatMac(raw.mac)
            val key = Hex.sanitizeKey(raw.key)
            if (!Hex.isValidMac(mac) || !Hex.isValidKey(key)) continue

            val normalized = raw.copy(mac = mac, key = key)
            val index = items.indexOfFirst { it.mac == mac && it.key == key }

            if (index >= 0) {
                val existing = items[index]
                items[index] = existing.copy(
                    bluetoothName = if (existing.bluetoothName.isEmpty()) normalized.bluetoothName else existing.bluetoothName,
                    name = if (existing.name.isEmpty()) normalized.name else existing.name,
                )
            } else {
                items += normalized
                added++
            }
        }

        val currentId = current.currentId ?: items.firstOrNull()?.id
        return Result(current.copy(items = items, currentId = currentId), added)
    }
}
