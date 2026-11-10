package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig

/**
 * 判断一条广播是否属于某个门禁。
 *
 * 与 iOS `UnlockService.matches` 相比多了一档 **MAC 精确匹配**并把它排在最前 ——
 * Android 能直接读到 `BluetoothDevice.address`，这是本平台独有的优势。
 */
object DeviceMatcher {

    fun matches(hit: ScanHit, door: DoorConfig): Boolean {
        val doorMac = door.mac.uppercase().filter { it.isHex() }
        val hitMac = hit.deviceId.uppercase().filter { it.isHex() }

        // ① MAC 精确匹配（Android 独有，命中率最高）
        if (doorMac.length == 12 && hitMac.length == 12 && doorMac == hitMac) return true

        // ② 蓝牙名等值
        val wanted = door.bluetoothName.uppercase().trim()
        val name = hit.name?.uppercase()?.trim().orEmpty()
        if (wanted.isNotEmpty() && name == wanted) return true

        // ③ 厂商数据里含 MAC / 反序 MAC / 名字的 ASCII
        val adv = hit.manufacturerData ?: return false
        val advHex = Hex.hexFromBytes(adv)
        if (doorMac.isNotEmpty() && (advHex.contains(doorMac) || advHex.contains(Hex.reversedPairs(doorMac)))) {
            return true
        }
        if (wanted.isNotEmpty() && advHex.contains(Hex.ascii(wanted))) return true

        return false
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'A'..'F'
}
