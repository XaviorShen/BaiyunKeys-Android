package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.model.DoorConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败原因分流：「去设置」的引导、按扫描结果给的文案、日志防刷屏。
 *
 * issue #5 的报告人只说了一句「扫描不到门锁」，从界面上分不清是没有权限、
 * 定位没开，还是扫到了但认不出来。这些测试盯的就是这几种情况各自给出不同的提示。
 */
class UnlockOrchestratorDiagnosticsTest {

    private val key = "00112233445566778899AABBCCDDEEFF"
    private val realMac = "C8:5C:AA:BB:CC:DD"

    private fun door(mac: String = realMac) =
        DoorConfig(id = "door_1", name = "一号楼大门", mac = mac, key = key, bluetoothName = "BY0AABBCCDD")

    private fun stranger(index: Int) =
        ScanHit(deviceId = "11:22:33:44:55:%02X".format(index), name = "别的设备 $index", rssi = -70 - index)

    private class Rig(val adapter: FakeBleAdapter, val orchestrator: UnlockOrchestrator)

    private fun rig(hits: List<ScanHit> = emptyList(), configure: (FakeBleAdapter) -> Unit = {}): Rig {
        val adapter = FakeBleAdapter(hits = hits)
        configure(adapter)
        val orchestrator = UnlockOrchestrator(adapter, FakeHistory(), FakePeripheralCache())
        return Rig(adapter, orchestrator)
    }

    @Test
    fun scanSawNoDeviceSaysSo() = runTest {
        val r = rig()

        val result = r.orchestrator.unlock(door(), UnlockOptions(scanTimeoutMs = 100))

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("没有发现任何蓝牙设备"))
        assertNull(r.orchestrator.settingsNeeded.value)
    }

    @Test
    fun scanSawDevicesButNoneMatchedSaysHowMany() = runTest {
        val r = rig(hits = listOf(stranger(1), stranger(2), stranger(3)))

        val result = r.orchestrator.unlock(door(), UnlockOptions(scanTimeoutMs = 100))

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("发现 3 台蓝牙设备"))
        assertTrue(result.message, result.message.contains("没有一台与这条门禁匹配"))
    }

    @Test
    fun quickModeMissTalksAboutTheCandidatePoolInsteadOfOneDoor() = runTest {
        val r = rig(hits = listOf(stranger(1)))

        val result = r.orchestrator.unlockAny(listOf(door()), UnlockOptions(scanTimeoutMs = 100))

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("候选里的任何一条门禁"))
        assertTrue(result.message, result.message.contains("发现 1 台蓝牙设备"))
    }

    @Test
    fun missingPermissionOffersTheSettingsPage() = runTest {
        val r = rig(configure = { it.scanError = ScanPermissionMissingException() })

        val result = r.orchestrator.unlock(door(), UnlockOptions(scanTimeoutMs = 100))

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("蓝牙扫描权限"))
        assertEquals(SettingsTarget.BluetoothPermission, r.orchestrator.settingsNeeded.value)
    }

    @Test
    fun locationOffWithDerivedMacSuggestsTheRealMac() = runTest {
        val r = rig(configure = { it.scanError = LocationServiceDisabledException() })

        val result = r.orchestrator.unlock(door(mac = "00:00:AA:BB:CC:DD"))

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("补填真实 MAC"))
        assertEquals(SettingsTarget.LocationService, r.orchestrator.settingsNeeded.value)
    }

    @Test
    fun locationOffWithRealMacKeepsTheShortMessage() = runTest {
        val r = rig(configure = { it.scanError = LocationServiceDisabledException() })

        val result = r.orchestrator.unlock(door())

        assertEquals(LocationServiceDisabledException.MESSAGE, result.message)
    }

    @Test
    fun scanStartFailureIsNotRetried() = runTest {
        val r = rig(configure = { it.scanError = ScanStartFailedException(6) })

        val result = r.orchestrator.unlock(
            door(),
            UnlockOptions(autoRetry = true, autoRetryCount = 3, scanTimeoutMs = 100),
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("错误码 6"))
        // 系统已经拒绝了扫描，自动重试只会撞得更狠
        assertEquals(1, r.adapter.scanCalls)
    }

    @Test
    fun settingsHintIsClearedByTheNextUnlock() = runTest {
        val hits = mutableListOf<ScanHit>()
        val r = rig(hits = hits, configure = { it.scanError = ScanPermissionMissingException() })
        r.orchestrator.unlock(door(), UnlockOptions(scanTimeoutMs = 100))
        assertEquals(SettingsTarget.BluetoothPermission, r.orchestrator.settingsNeeded.value)

        // 用户去设置里开了权限，门锁也在广播了
        hits += ScanHit(realMac, "BY0AABBCCDD", -50)
        r.adapter.scanError = null
        r.adapter.session = FakeBleSession(realMac) { listOf(TestFrames.handshakeOk()) }

        assertTrue(r.orchestrator.unlock(door()).success)
        assertNull(r.orchestrator.settingsNeeded.value)
    }

    @Test
    fun perDeviceLogLinesAreCappedAndSummarised() = runTest {
        val r = rig(hits = List(12) { stranger(it) })

        r.orchestrator.unlock(door(), UnlockOptions(scanTimeoutMs = 100))

        val lines = r.orchestrator.logs.value.map { it.text }
        assertEquals(8, lines.count { it.startsWith("发现设备") })
        assertTrue(lines.toString(), lines.any { it.contains("扫描结束：发现 12 台设备") })
    }
}
