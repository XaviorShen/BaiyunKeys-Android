package com.baiyunkeys.core.model

import com.baiyunkeys.core.hex.Hex

/**
 * 判断一个 MAC 是不是「按蓝牙名反推出来的」。
 *
 * `BleProtocol.macFromBluetoothName` 在拿不到真实 MAC 时会生成
 * `00:0X:AA:BB:CC:DD`：前缀 `000` + 名字第 3 位（不是 hex 则补 0）+ 名字第 4~11 位。
 * 这种 MAC 的后 4 字节可以用来握手，但它不是蓝牙地址，不能拿去直连。
 */
object MacOrigin {
    fun looksDerivedFromName(mac: String, bluetoothName: String): Boolean {
        val compact = Hex.sanitizeKey(mac) // 只留 hex 并转大写
        val name = bluetoothName.uppercase().filter { it.isLetterOrDigit() }
        if (compact.length != 12 || name.length < 11) return false
        return compact.startsWith("000") && compact.substring(4) == name.substring(3, 11)
    }
}
