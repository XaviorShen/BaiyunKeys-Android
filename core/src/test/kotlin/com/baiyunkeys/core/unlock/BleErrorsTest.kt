package com.baiyunkeys.core.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleErrorsTest {

    @Test
    fun knownCodesAreTranslated() {
        for (code in 1..6) {
            val text = BleErrors.describeScanFailure(code)
            assertTrue(text, text.contains("错误码 $code"))
            assertFalse("code $code 应该有自己的说明", text.startsWith("扫描启动失败（"))
        }
    }

    @Test
    fun unknownCodeKeepsItsNumber() {
        assertEquals("扫描启动失败（错误码 99）", BleErrors.describeScanFailure(99))
    }

    @Test
    fun tooFrequentSaysWhatToDo() {
        val text = BleErrors.describeScanFailure(6)

        assertTrue(text, text.contains("等半分钟"))
        assertEquals(text, ScanStartFailedException(6).message)
        assertEquals(6, ScanStartFailedException(6).code)
    }
}
