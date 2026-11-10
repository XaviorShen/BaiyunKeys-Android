package com.baiyunkeys.core.protocol

import com.baiyunkeys.core.crypto.DesBlock
import com.baiyunkeys.core.hex.Hex
import java.time.Instant
import java.time.ZoneId

/**
 * 门锁私有协议帧构造与解析。
 *
 * 逐字节对照 `BaiyunKeys-iOS/BaiyunKeys/Core/BLEProtocol.swift`（协议唯一事实源）。
 * 帧结构：
 * - 握手：`A5 14 05 | header[4] | 00 01 07 | DES(8B) | sum 5A`
 * - 负载：`A5 16 01 | ids[4] | 00 00 | sum xor type | DES(8B) | sum 5A`
 *
 * 末位校验和固定落在**倒数第二字节**：`~(全部字节之和) & 0xFF`。
 */
object BleProtocol {

    /** 开锁回执。`00` 开门成功、`02` 门已打开、其它一律失败。 */
    data class OpenResult(val code: String, val message: String) {
        val isSuccess: Boolean get() = code == "00" || code == "02"
    }

    // MARK: - 设备标识

    /** 从蓝牙名称的第 4~11 位解析出 4 字节设备标识（取 [3,5,7,9] 各 2 字符）。 */
    fun deviceIdParts(bluetoothName: String): ByteArray {
        val clean = bluetoothName.uppercase().filter { it.isLetterOrDigit() }
        require(clean.length >= 11) { "蓝牙名称格式不合法，无法解析设备标识" }
        return ByteArray(4) { i ->
            val offset = 3 + i * 2
            byteAt(clean, offset)
        }
    }

    /** 取 MAC 的后 4 个字节作为帧头标识（12 位 hex → 6 字节 → 丢前 2 字节）。 */
    fun headerFromMac(mac: String): ByteArray {
        val compact = mac.uppercase().filter { it.isHex() }
        require(compact.length == 12) { "MAC 地址格式不合法，无法生成握手标识" }
        return Hex.bytesFromHex(compact).copyOfRange(2, 6)
    }

    /**
     * 由蓝牙名称推出一个「够用」的 MAC。
     *
     * 握手帧只吃 MAC 的后 4 字节，而蓝牙名第 4~11 位正好是这 4 字节，
     * 前 2 字节填 0 占位不影响开锁。
     */
    fun macFromBluetoothName(name: String): String? {
        val clean = name.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length < 11) return null
        val tail = clean.substring(3, 11)
        if (!tail.all { it.isHex() }) return null
        val nibble = if (clean[2].isHex()) clean[2].toString() else "0"
        return Hex.formatMac("000$nibble$tail")
    }

    // MARK: - 指令构造

    /** 握手指令：`A5 14 05 | header[4] | 00 01 07 | DES(随机数校验块) | sum 5A`。 */
    fun handshakeCommand(random: ByteArray, header: ByteArray, productKey: String): ByteArray {
        require(header.size == 4) { "握手指令头部需提供 4 个字节" }

        var sum = random.sumOf { it.toInt() and 0xFF }
        sum += Hex.bytesFromHex(productKey).sumOf { it.toInt() and 0xFF }

        val block = byteArrayOf(
            (sum and 0xFF).toByte(),
            ((sum shr 8) and 0xFF).toByte(),
            at(random, 0), at(random, 1), at(random, 2), at(random, 3),
            0x00, 0x00,
        )
        val encrypted = Hex.bytesFromHex(
            DesBlock.encryptBlock(keyHex = productKey, blockHex = Hex.hexFromBytes(block)),
        )

        val frame = byteArrayOf(0xA5.toByte(), 0x14, 0x05) + header +
            byteArrayOf(0x00, 0x01, 0x07) + encrypted + byteArrayOf(0x00, 0x5A)
        return sealed(frame)
    }

    /** 通讯密钥协商指令（type = 0x09）。 */
    fun commKeyCommand(random: ByteArray, bluetoothName: String, productKey: String): ByteArray {
        val ids = deviceIdParts(bluetoothName)
        val pivot = byteArrayOf(
            at(random, 0), at(random, 1), at(random, 2), at(random, 3),
            0x00, 0x00, 0x00, 0x00,
        )
        val encrypted = Hex.bytesFromHex(
            DesBlock.encryptBlock(keyHex = productKey, blockHex = Hex.hexFromBytes(pivot)),
        )
        // 校验和只覆盖前 4 字节随机数：RinkTika/BaiyunKeys 与小程序实现都是 `random.copyOf(4)`，
        // 门锁下发的随机数若超过 4 字节，多出来的部分不能进 sum/xor。
        val body = ids + byteArrayOf(0x00, 0x00) + random.copyOf(4)
        return sealed(payloadFrame(ids, body, TYPE_COMM_KEY, encrypted))
    }

    /** 时间同步指令（type = 0x06），使用协商出的会话密钥加密。 */
    fun timeSyncCommand(timeBytes: ByteArray, bluetoothName: String, sessionKey: String): ByteArray {
        val ids = deviceIdParts(bluetoothName)
        val padded = ByteArray(8)
        timeBytes.copyInto(padded, 0, 0, minOf(timeBytes.size, 8))
        val encrypted = Hex.bytesFromHex(
            DesBlock.encryptBlock(keyHex = sessionKey, blockHex = Hex.hexFromBytes(padded)),
        )
        val body = ids + byteArrayOf(0x00, 0x00) + timeBytes
        return sealed(payloadFrame(ids, body, TYPE_TIME_SYNC, encrypted))
    }

    /**
     * BCD 编码的时间：年 月 日 时 分 秒 星期。
     *
     * 年是 `(year-2000)` 拆成两个 4 bit，其余六项十进制 `%02d`，
     * **周日 = 0**（`java.time` 的 `dayOfWeek` 是周一 1 … 周日 7，取模 7）。
     *
     * 时区必须显式传入，保证 golden vector 与运行环境无关。
     */
    fun timeHex(date: Instant, zone: ZoneId): String {
        val t = date.atZone(zone)
        val year = t.year - 2000
        val yearByte = (((year / 10) shl 4) or (year % 10)) and 0xFF
        val weekday = t.dayOfWeek.value % 7
        return buildString {
            append(Hex.hexFromBytes(byteArrayOf(yearByte.toByte())))
            for (v in intArrayOf(t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, weekday)) {
                append(v.toString().padStart(2, '0'))
            }
        }
    }

    // MARK: - 报文解析

    /** 从设备下发的密钥片段还原会话密钥；长度不足返回空串。 */
    fun commKeyFromBody(bodyHex: String, productKey: String): String {
        val decrypted = try {
            DesBlock.decryptBlock(keyHex = productKey, blockHex = bodyHex)
        } catch (_: IllegalArgumentException) {
            return ""
        }
        if (decrypted.length < 32) return ""
        return Hex.hexSlice(decrypted, 16, 16).uppercase()
    }

    fun decryptBody(bodyHex: String, sessionKey: String): String = try {
        DesBlock.decryptBlock(keyHex = sessionKey, blockHex = bodyHex)
    } catch (_: IllegalArgumentException) {
        ""
    }

    /** 解析开锁回执：帧长 ≥ 36 hex 字符才解析，取 `slice(20,16)` 解密后看 `slice(4,2)`。 */
    fun openResult(frameHex: String, productKey: String): OpenResult {
        val clean = Hex.normalize(frameHex)
        if (clean.length < 36) {
            return OpenResult("FF", "数据长度不足，无法解析回执")
        }
        val decrypted = try {
            DesBlock.decryptBlock(keyHex = productKey, blockHex = Hex.hexSlice(clean, 20, 16))
        } catch (_: IllegalArgumentException) {
            return OpenResult("FF", "回执解密失败")
        }
        return when (val code = Hex.hexSlice(decrypted, 4, 2)) {
            "00" -> OpenResult("00", "开门成功")
            "02" -> OpenResult("02", "门已打开")
            else -> OpenResult(code, "开门失败，密码无效")
        }
    }

    // MARK: - 私有

    private fun payloadFrame(
        ids: ByteArray,
        body: ByteArray,
        type: Byte,
        encrypted: ByteArray,
    ): ByteArray {
        val sumBody = (body.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        var xorBody = if (body.isEmpty()) 0 else body[0].toInt() and 0xFF
        for (i in 1 until body.size) xorBody = xorBody xor (body[i].toInt() and 0xFF)

        return byteArrayOf(0xA5.toByte(), 0x16, 0x01) + ids +
            byteArrayOf(0x00, 0x00, sumBody, xorBody.toByte(), type) + encrypted +
            byteArrayOf(0x00, 0x5A)
    }

    /** 写入末位校验和：倒数第二字节 = 各字节之和的反码。 */
    private fun sealed(frame: ByteArray): ByteArray {
        val out = frame.copyOf()
        val checksum = out.sumOf { it.toInt() and 0xFF }
        out[out.size - 2] = ((checksum.inv()) and 0xFF).toByte()
        return out
    }

    private fun byteAt(str: String, offset: Int): Byte {
        val pair = Hex.hexSlice(str, offset, 2)
        if (pair.length != 2 || !pair.all { it.isHex() }) return 0
        return pair.toInt(16).toByte()
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'A'..'F'

    private fun at(bytes: ByteArray, index: Int): Byte =
        if (index in bytes.indices) bytes[index] else 0

    private const val TYPE_TIME_SYNC: Byte = 0x06
    private const val TYPE_COMM_KEY: Byte = 0x09
}
