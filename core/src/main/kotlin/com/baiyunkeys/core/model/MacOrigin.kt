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

    /**
     * 导入的门禁里有推导值 MAC 时，返回给用户看的提醒；没有则返回 null。
     *
     * 这种 MAC 在 iOS 上够用（握手只吃后 4 字节），但在安卓上既匹配不上也没法直连，
     * 只能靠扫描和蓝牙名。从 iOS 复刻版扫码导入的数据里很常见：那边的编辑器会自动反推。
     */
    fun importNotice(doors: List<DoorConfig>): String? {
        val derived = doors.count { looksDerivedFromName(it.mac, it.bluetoothName) }
        if (derived == 0) return null
        return "有 $derived 条门禁的 MAC 是按蓝牙名反推的（`00:0` 开头），不是真实地址。" +
            "握手不受影响，但安卓版没法直接连接门锁，只能靠扫描和蓝牙名匹配。" +
            "想更稳的话：到「配置 → 打开扫描面板」找到门锁后点「绑定」，或者补填真实 MAC。"
    }
}
