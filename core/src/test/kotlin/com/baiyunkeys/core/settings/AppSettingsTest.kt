package com.baiyunkeys.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 偏好取值收敛 + 换算成开锁选项。 */
class AppSettingsTest {

    @Test
    fun defaultsMatchIos() {
        val settings = AppSettings()
        assertTrue("掩码默认开（iOS 默认 hideSensitive=true）", settings.hideSensitive)
        assertTrue("触感默认开", settings.haptics)
        assertFalse("自动重试默认关", settings.autoRetry)
        assertEquals(3, settings.autoRetryCount)
        assertEquals(10, settings.scanTimeoutSeconds)
        assertEquals(ThemeMode.Auto, settings.theme)
    }

    @Test
    fun dirtyValuesAreClamped() {
        val dirty = AppSettings(autoRetryCount = 999, scanTimeoutSeconds = -5).sanitized()
        assertEquals(20, dirty.autoRetryCount)
        assertEquals(3, dirty.scanTimeoutSeconds)

        val tooSmall = AppSettings(autoRetryCount = 0, scanTimeoutSeconds = 0).sanitized()
        assertEquals(1, tooSmall.autoRetryCount)
        assertEquals(3, tooSmall.scanTimeoutSeconds)

        val tooBig = AppSettings(scanTimeoutSeconds = 120).sanitized()
        assertEquals(60, tooBig.scanTimeoutSeconds)
    }

    @Test
    fun unlockOptionsFollowSettings() {
        val options = AppSettings(
            autoRetry = true,
            autoRetryCount = 5,
            scanTimeoutSeconds = 25,
        ).toUnlockOptions()

        assertTrue(options.autoRetry)
        assertEquals(25_000L, options.scanTimeoutMs)
        assertEquals(5, options.maxAttempts)
        assertFalse(options.silent)
    }

    @Test
    fun retryDisabledMeansSingleAttempt() {
        val options = AppSettings(autoRetry = false, autoRetryCount = 10).toUnlockOptions()
        assertEquals(1, options.maxAttempts)
    }

    @Test
    fun silentFlagPassesThrough() {
        assertTrue(AppSettings().toUnlockOptions(silent = true).silent)
    }

    @Test
    fun themeParsingFallsBackToAuto() {
        assertEquals(ThemeMode.Auto, ThemeMode.of(null))
        assertEquals(ThemeMode.Auto, ThemeMode.of("nonsense"))
        assertEquals(ThemeMode.Dark, ThemeMode.of("dark"))
        assertEquals(ThemeMode.Light, ThemeMode.of("light"))
    }
}
