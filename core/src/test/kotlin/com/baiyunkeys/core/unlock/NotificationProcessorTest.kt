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
