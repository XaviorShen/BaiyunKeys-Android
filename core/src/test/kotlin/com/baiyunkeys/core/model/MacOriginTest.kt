package com.baiyunkeys.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 推导值识别：形如 `00:0X:` + 蓝牙名第 4~11 位的 MAC 不能拿去直连。 */
class MacOriginTest {

    @Test
    fun macDerivedFromNameIsRecognised() {
        assertTrue(MacOrigin.looksDerivedFromName("00:00:AA:BB:CC:DD", "BY0AABBCCDD"))
        assertTrue(MacOrigin.looksDerivedFromName("00:0E:12:34:56:78", "BLE123456789"))
    }

    @Test
    fun realMacIsNotTreatedAsDerived() {
        assertFalse(MacOrigin.looksDerivedFromName("C8:5C:AA:BB:CC:DD", "BY0AABBCCDD"))
        assertFalse(MacOrigin.looksDerivedFromName("C8:5C:AA:BB:CC:DD", ""))
    }

    @Test
    fun samePrefixButDifferentTailIsNotDerived() {
        assertFalse(MacOrigin.looksDerivedFromName("00:0C:76:AA:BB:CC", "BY0AABBCCDD"))
        assertFalse(MacOrigin.looksDerivedFromName("00:00:AA:BB:CC:DD", "BY0AABBCCDE"))
    }

    @Test
    fun shortNameOrBadMacIsNotDerived() {
        assertFalse(MacOrigin.looksDerivedFromName("00:00:AA:BB:CC:DD", "BY0AAB"))
        assertFalse(MacOrigin.looksDerivedFromName("不是 MAC", "BY0AABBCCDD"))
    }
}
