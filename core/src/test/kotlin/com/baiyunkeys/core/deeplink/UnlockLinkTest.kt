package com.baiyunkeys.core.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 深链解析：只认 `baiyunkeys://unlock`，别的一律交回系统。 */
class UnlockLinkTest {

    @Test
    fun defaultUnlock() {
        assertEquals(UnlockCommand.Default, UnlockLink.parse("baiyunkeys://unlock"))
        assertEquals(UnlockCommand.Default, UnlockLink.parse("BAIYUNKEYS://UNLOCK"))
        assertEquals(UnlockCommand.Default, UnlockLink.parse("  baiyunkeys://unlock/  "))
    }

    @Test
    fun specificDoor() {
        assertEquals(
            UnlockCommand.Door("door_abc123"),
            UnlockLink.parse("baiyunkeys://unlock?door=door_abc123"),
        )
        // 多余参数不影响
        assertEquals(
            UnlockCommand.Door("door_abc123"),
            UnlockLink.parse("baiyunkeys://unlock?from=nfc&door=door_abc123"),
        )
    }

    @Test
    fun quickMode() {
        assertEquals(UnlockCommand.Quick, UnlockLink.parse("baiyunkeys://unlock?quick=1"))
        // quick 优先于 door
        assertEquals(
            UnlockCommand.Quick,
            UnlockLink.parse("baiyunkeys://unlock?door=door_abc123&quick=1"),
        )
    }

    @Test
    fun rejectsForeignLinks() {
        assertNull(UnlockLink.parse(null))
        assertNull(UnlockLink.parse(""))
        assertNull(UnlockLink.parse("   "))
        assertNull(UnlockLink.parse("https://example.com/unlock"))
        assertNull(UnlockLink.parse("baiyunkeys://other"))
        assertNull(UnlockLink.parse("baiyunkeys://"))
        assertNull(UnlockLink.parse("baiyunkeys://unlock/extra"))
    }

    @Test
    fun emptyDoorParamFallsBackToDefault() {
        assertEquals(UnlockCommand.Default, UnlockLink.parse("baiyunkeys://unlock?door="))
        assertEquals(UnlockCommand.Default, UnlockLink.parse("baiyunkeys://unlock?door=   "))
    }

    @Test
    fun buildMatchesParse() {
        assertEquals("baiyunkeys://unlock?door=door_x1", UnlockLink.build("door_x1"))
        assertEquals(UnlockCommand.Door("door_x1"), UnlockLink.parse(UnlockLink.build("door_x1")))
        assertEquals(UnlockCommand.Quick, UnlockLink.parse(UnlockLink.buildQuick()))
    }
}
