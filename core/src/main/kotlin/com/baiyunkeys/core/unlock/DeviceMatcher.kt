package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig

/** 一条广播属于某台门禁的依据，由强到弱。诊断页用它标出每台设备为什么（不）匹配。 */
enum class MatchTier {
    /** 地址与门禁 MAC 完全一致。 */
    Mac,

    /** 蓝牙名完全一致（与 iOS 相同）。 */
    Name,

    /** 厂商数据里含 MAC / 反序 MAC / 名字的 ASCII。 */
    AdvPayload,
}

/**
 * 判断一条广播是否属于某个门禁。
 *
 * 与 iOS `UnlockService.matches` 相比多了一档 **MAC 精确匹配**并把它排在最前 ——
 * Android 能直接读到 `BluetoothDevice.address`，这是本平台独有的优势。
 */
object DeviceMatcher {

    /** 返回命中的档位；不属于这个门禁时返回 null。 */
    fun matchTier(hit: ScanHit, door: DoorConfig): MatchTier? {
        val doorMac = door.mac.uppercase().filter { it.isHex() }
        val hitMac = hit.deviceId.uppercase().filter { it.isHex() }

        // ① MAC 精确匹配（Android 独有，命中率最高）
        if (doorMac.length == 12 && hitMac.length == 12 && doorMac == hitMac) return MatchTier.Mac

        // ② 蓝牙名等值
        val wanted = door.bluetoothName.uppercase().trim()
        val name = hit.name?.uppercase()?.trim().orEmpty()
        if (wanted.isNotEmpty() && name == wanted) return MatchTier.Name

        // ③ 厂商数据里含 MAC / 反序 MAC / 名字的 ASCII
        val adv = hit.manufacturerData ?: return null
        val advHex = Hex.hexFromBytes(adv)
        if (doorMac.isNotEmpty() && (advHex.contains(doorMac) || advHex.contains(Hex.reversedPairs(doorMac)))) {
            return MatchTier.AdvPayload
        }
        if (wanted.isNotEmpty() && advHex.contains(Hex.ascii(wanted))) return MatchTier.AdvPayload

        return null
    }

    fun matches(hit: ScanHit, door: DoorConfig): Boolean = matchTier(hit, door) != null

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'A'..'F'
}
