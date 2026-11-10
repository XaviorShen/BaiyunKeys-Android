package com.baiyunkeys.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 隐藏入口的连点状态机 + 自动获取 24h TTL。 */
class HiddenEntryGateTest {

    @Test
    fun tenTapsAskForCode() {
        val gate = HiddenEntryGate()
        repeat(9) { assertEquals(HiddenEntryGate.Result.Ignored, gate.onTap(visibleNow = false)) }
        assertEquals(HiddenEntryGate.Result.AskForCode, gate.onTap(visibleNow = false))
    }

    @Test
    fun correctCodeReveals() {
        val gate = HiddenEntryGate()
        assertEquals(HiddenEntryGate.Result.Reveal, gate.onSubmitCode("123456789"))
        assertEquals(HiddenEntryGate.Result.Reveal, gate.onSubmitCode("  123456789 "))
    }

    @Test
    fun wrongCodeIsIgnored() {
        val gate = HiddenEntryGate()
        assertEquals(HiddenEntryGate.Result.Ignored, gate.onSubmitCode("0000"))
        assertEquals(HiddenEntryGate.Result.Ignored, gate.onSubmitCode(""))
    }

    @Test
    fun fiveTapsHideWhenVisible() {
        val gate = HiddenEntryGate()
        repeat(4) { assertEquals(HiddenEntryGate.Result.Ignored, gate.onTap(visibleNow = true)) }
        assertEquals(HiddenEntryGate.Result.Hide, gate.onTap(visibleNow = true))
    }

    @Test
    fun tapCounterResetsAfterTrigger() {
        val gate = HiddenEntryGate()
        repeat(10) { gate.onTap(visibleNow = false) }
        // 计数已归零，再点一次不该立刻又弹
        assertEquals(HiddenEntryGate.Result.Ignored, gate.onTap(visibleNow = false))
    }

    @Test
    fun autoFetchExpiresAfter24Hours() {
        val now = 1_700_000_000_000L
        val fresh = AppSettings(autoFetchVisible = true, autoFetchShownAt = now - 1000)
        assertTrue(fresh.isAutoFetchVisible(now))

        val stale = AppSettings(autoFetchVisible = true, autoFetchShownAt = now - AppSettings.AUTO_FETCH_TTL_MS - 1)
        assertFalse(stale.isAutoFetchVisible(now))

        val hidden = AppSettings(autoFetchVisible = false, autoFetchShownAt = now)
        assertFalse(hidden.isAutoFetchVisible(now))
    }

    @Test
    fun udidLooksLikeUuid() {
        val udid = HiddenEntryGate.newUdid()
        assertEquals(36, udid.length)
        assertEquals(4, udid.count { it == '-' })
    }
}
