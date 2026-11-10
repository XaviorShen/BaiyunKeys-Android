package com.baiyunkeys.core.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通知帧解析：纯函数，按 iOS `processNotification` 的分支逐条断言。 */
class NotificationProcessorTest {

    private val productKey = "00112233445566778899AABBCCDDEEFF"

    private fun NotificationProcessor.terminalOf(hex: String): ProtocolEvent.Terminal? =
        process(hex).filterIsInstance<ProtocolEvent.Terminal>().firstOrNull()

    @Test
    fun handshakeLongFrameMeansUnlocked() {
        val processor = NotificationProcessor(productKey)
        val terminal = processor.terminalOf(TestFrames.handshakeOk())
        assertTrue(processor.handshakeOk)
        assertEquals(true, terminal?.success)
        assertEquals("握手成功，门锁已执行开锁", terminal?.message)
    }

    /**
     * 0x04 长帧要带诊断日志（长度 / 校验和 / 解密后的状态字节）。
     *
     * 判定本身仍按 iOS 的「长帧即成功」，这里只保证真机排查时能看到状态字节。
     */
    @Test
    fun handshakeLongFrameLogsDiagnostics() {
        val processor = NotificationProcessor(productKey)
        val logs = processor.process(TestFrames.handshakeOk())
            .filterIsInstance<ProtocolEvent.Log>()
            .joinToString("\n") { it.text }
        assertTrue("应记录 0x04 诊断：$logs", logs.contains("0x04 回执诊断"))
        assertTrue("应记录校验和结论：$logs", logs.contains("校验和"))
        assertTrue("应记录状态字节：$logs", logs.contains("状态字节"))
    }

    /** 构造一个 20 字节、状态字节为 00 的 0x04 回执，诊断应解出「0x00（成功）」。 */
    @Test
    fun handshakeLongFrameDecryptsStatusByte() {
        val plain = "0000" + "00" + "0".repeat(10) // 16 hex = 8 字节，第 3 字节 = 00
        val enc = com.baiyunkeys.core.crypto.DesBlock.encryptBlock(productKey, plain)
        val head = com.baiyunkeys.core.hex.Hex.bytesFromHex("A51404AABBCCDD000000" + enc)
        val sum = head.sumOf { it.toInt() and 0xFF }
        val frame = head + byteArrayOf(((sum.inv()) and 0xFF).toByte(), 0x5A)
        val hex = com.baiyunkeys.core.hex.Hex.hexFromBytes(frame)

        val processor = NotificationProcessor(productKey)
        val logs = processor.process(hex)
            .filterIsInstance<ProtocolEvent.Log>()
            .joinToString("\n") { it.text }

        assertTrue("长度应为 20 字节：$logs", logs.contains("长度 20/20 字节"))
        assertTrue("校验和应正确：$logs", logs.contains("校验和正确"))
        assertTrue("状态字节应解出 00：$logs", logs.contains("0x00（成功）"))
    }

    @Test
    fun handshakeShortFrameCarriesStatusCode() {
        val processor = NotificationProcessor(productKey)
        val terminal = processor.terminalOf(TestFrames.handshakeReject("000B"))
        assertFalse(processor.handshakeOk)
        assertEquals(false, terminal?.success)
        assertTrue(
            "应带 000B 与提示：${terminal?.message}",
            terminal?.message?.contains("0x000B") == true && terminal.message.contains("Key"),
        )
    }

    @Test
    fun negotiationRejectCarriesE36F() {
        val processor = NotificationProcessor(productKey)
        val terminal = processor.terminalOf(TestFrames.negotiationReject())
        assertEquals(false, terminal?.success)
        assertTrue(terminal?.message?.contains("0xE36F") == true)
    }

    @Test
    fun finalReceiptCodeDrivesSuccess() {
        val processor = NotificationProcessor(productKey)
        assertEquals(true, processor.terminalOf(TestFrames.receipt("00", productKey))?.success)
        assertEquals(true, processor.terminalOf(TestFrames.receipt("02", productKey))?.success)

        val failed = processor.terminalOf(TestFrames.receipt("0F", productKey))
        assertEquals(false, failed?.success)
        assertEquals("开门失败，密码无效", failed?.message)
    }

    @Test
    fun foreignHeaderIsIgnored() {
        val processor = NotificationProcessor(productKey)
        val events = processor.process("B50004000000000000000000000000")
        assertTrue(events.any { it is ProtocolEvent.Log })
        assertNull(events.filterIsInstance<ProtocolEvent.Terminal>().firstOrNull())
    }

    @Test
    fun fragmentsAreIgnoredBeforeHandshake() {
        val processor = NotificationProcessor(productKey)
        val events = processor.process(TestFrames.commKeyFragment())
        assertNull(events.filterIsInstance<ProtocolEvent.Terminal>().firstOrNull())
        // 未握手时只留一条收到帧的 debug 日志
        assertEquals(1, events.size)
    }

    @Test
    fun fragmentsAreCachedAfterHandshake() {
        val processor = NotificationProcessor(productKey)
        processor.process(TestFrames.handshakeOk())

        val commKey = processor.process(TestFrames.commKeyFragment())
        assertTrue(commKey.any { it is ProtocolEvent.Log && it.text == "门锁开始下发通讯密钥片段" })

        val timeSync = processor.process(TestFrames.timeSyncFragment())
        assertTrue(timeSync.any { it is ProtocolEvent.Log && it.text == "门锁返回时间同步片段" })

        val blacklist = processor.process(TestFrames.blacklistFragment())
        assertTrue(blacklist.any { it is ProtocolEvent.Log && it.text == "门锁返回黑名单同步片段" })
    }

    @Test
    fun resetClearsSessionState() {
        val processor = NotificationProcessor(productKey)
        processor.process(TestFrames.handshakeOk())
        assertTrue(processor.handshakeOk)
        processor.reset()
        assertFalse(processor.handshakeOk)
        assertEquals("", processor.sessionKey)
    }
}
