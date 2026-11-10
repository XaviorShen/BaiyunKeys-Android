package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.protocol.BleProtocol

/** 协议状态机吐出的动作，由编排器落到「日志 / 状态文案 / 结束」。 */
sealed interface ProtocolEvent {
    data class Log(val text: String, val level: LogLevel = LogLevel.INFO) : ProtocolEvent
    data class Status(val text: String) : ProtocolEvent
    data class Terminal(val success: Boolean, val message: String) : ProtocolEvent
}

/**
 * 门锁通知帧解析器（纯函数，不碰蓝牙、不碰协程）。
 *
 * 逐行对照 `BaiyunKeys-iOS/BaiyunKeys/Core/UnlockService.swift` 的 `processNotification`：
 * - `cmd=04` 握手回执：短帧取 `slice(14,4)` 反转成 16 bit 返回码（`000B` = Key / 设备标识不匹配）；
 *   长帧则**开门成功**（门锁在握手阶段就执行开锁）
 * - `cmd=08` 协商拒绝：取尾部 4 个字符（`E36F` = 拒绝协商）
 * - `type=87` + 尾 `5A` 最终回执：交给 [BleProtocol.openResult]
 * - `type=89/86/82`：分片缓存，按 `5A` 尾合并后解密
 */
class NotificationProcessor(private val productKey: String) {

    /** 握手是否已通过。未通过前只认 04 / 08 / 87 三种帧。 */
    var handshakeOk: Boolean = false
        private set

    /** 协商出的通讯密钥（会话密钥），时间同步要用。 */
    var sessionKey: String = ""
        private set

    private var commBuffer: String = ""
    private var timeBuffer: String = ""

    fun reset() {
        handshakeOk = false
        sessionKey = ""
        commBuffer = ""
        timeBuffer = ""
    }

    fun process(frameHex: String): List<ProtocolEvent> {
        val events = mutableListOf<ProtocolEvent>()
        val hex = frameHex.uppercase()

        val header = Hex.hexSlice(hex, 0, 2)
        val command = if (hex.length >= 6) Hex.hexSlice(hex, 4, 2) else ""
        val type = if (hex.length >= 20) Hex.hexSlice(hex, 18, 2) else ""
        val tail = if (hex.length >= 2) hex.substring(hex.length - 2) else ""

        events += ProtocolEvent.Log(
            "收到 ${hex.length / 2}B header=$header cmd=$command type=$type",
            LogLevel.DEBUG,
        )
        if (header != "A5") return events

        // 握手回执
        if (command == "04") {
            if (hex.length > 24) {
                handshakeOk = true
                events += ProtocolEvent.Log("握手成功")
                events += diagnose04(hex)
                events += ProtocolEvent.Terminal(true, "握手成功，门锁已执行开锁")
            } else {
                val statusField = Hex.hexSlice(hex, 14, 4)
                val word = (
                    Hex.hexSlice(statusField, 2, 2) + Hex.hexSlice(statusField, 0, 2)
                    ).uppercase()
                val hint = if (word == "000B") "（Key 或设备标识不匹配，门锁拒绝握手）" else ""
                events += ProtocolEvent.Terminal(false, "握手失败，设备返回码 0x$word$hint")
            }
            return events
        }

        // 通讯密钥协商失败
        if (command == "08") {
            val word = if (hex.length >= 6) hex.substring(hex.length - 6, hex.length - 2) else ""
            val hint = if (word == "E36F") "（门锁拒绝协商，通常是 Key 或授权信息不匹配）" else ""
            events += ProtocolEvent.Terminal(false, "通讯密钥协商失败，设备返回码 0x$word$hint")
            return events
        }

        // 开锁结果回执
        if (type == "87" && tail == "5A") {
            val result = BleProtocol.openResult(frameHex = hex, productKey = productKey)
            events += ProtocolEvent.Terminal(result.isSuccess, result.message)
            return events
        }

        if (!handshakeOk) return events

        when (type) {
            "89" -> {
                commBuffer = hex
                events += ProtocolEvent.Log("门锁开始下发通讯密钥片段")
                return events
            }

            "86" -> {
                timeBuffer = hex
                events += ProtocolEvent.Log("门锁返回时间同步片段")
                return events
            }

            "82" -> {
                events += ProtocolEvent.Log("门锁返回黑名单同步片段", LogLevel.DEBUG)
                return events
            }
        }

        if (tail == "5A" && commBuffer.isNotEmpty()) {
            val merged = commBuffer + hex
            val key = BleProtocol.commKeyFromBody(Hex.hexSlice(merged, 24, 32), productKey)
            if (key.isEmpty()) {
                commBuffer = merged
            } else {
                sessionKey = key
                commBuffer = ""
                events += ProtocolEvent.Log("通讯密钥协商成功")
            }
            return events
        }

        if (tail == "5A" && timeBuffer.isNotEmpty() && sessionKey.isNotEmpty()) {
            val merged = timeBuffer + hex
            val plain = BleProtocol.decryptBody(Hex.hexSlice(merged, 24, 16), sessionKey)
            if (plain == "0000000000000000") {
                timeBuffer = ""
                events += ProtocolEvent.Log("门锁时间同步成功")
                events += ProtocolEvent.Status("时间同步完成，等待门锁最终回执…")
            } else {
                timeBuffer = merged
            }
        }
        return events
    }

    /**
     * `0x04` 长帧的诊断信息（**只记录，不改变判定**）。
     *
     * 另一份公开实现（yy1643/baiyunkey--）在收到 0x04 时会解密 `[10..17]` 的密文，
     * 要求状态字节 `payload[2] == 0` 才算开锁成功；iOS 版（本项目的事实源）则把长帧直接当成功。
     * 两者对「门锁在握手阶段就执行开锁」这一行为理解不同，真机没验过之前不擅自改判定，
     * 但把长度字节、校验和、解密后的状态字节都记下来——真机跑一次就能看出该信谁。
     */
    private fun diagnose04(hex: String): ProtocolEvent.Log {
        val actualLen = hex.length / 2
        val declaredLen = Hex.hexSlice(hex, 2, 2).toIntOrNull(16)

        val checksumOk = runCatching {
            val bytes = Hex.bytesFromHex(hex)
            if (bytes.size < 2) return@runCatching false
            val sum = bytes.take(bytes.size - 2).sumOf { it.toInt() and 0xFF }
            ((sum.inv()) and 0xFF) == (bytes[bytes.size - 2].toInt() and 0xFF)
        }.getOrDefault(false)

        val status = runCatching {
            if (hex.length < 36) return@runCatching ""
            val plain = BleProtocol.decryptBody(Hex.hexSlice(hex, 20, 16), productKey)
            if (plain.length >= 6) Hex.hexSlice(plain, 4, 2) else ""
        }.getOrDefault("")

        val statusText = when (status) {
            "" -> "无法解密"
            "00" -> "0x00（成功）"
            "02" -> "0x02（门已打开）"
            else -> "0x$status（非 00/02）"
        }
        return ProtocolEvent.Log(
            "0x04 回执诊断：长度 $declaredLen/$actualLen 字节，" +
                "校验和${if (checksumOk) "正确" else "不符"}，状态字节 $statusText",
            LogLevel.DEBUG,
        )
    }
}
