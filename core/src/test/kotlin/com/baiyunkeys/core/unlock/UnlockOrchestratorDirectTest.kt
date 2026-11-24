package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.model.DoorConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「直连优先、失败回退扫描」的行为测试。
 *
 * 假适配器区分两类门锁：能直连的，和必须先被扫描发现才连得上的（`directUnsupported`）。
 */
class UnlockOrchestratorDirectTest {

    private val key = "00112233445566778899AABBCCDDEEFF"
    private val realMac = "C8:5C:AA:BB:CC:DD"

    private fun door(
        id: String = "door_1",
        mac: String = realMac,
        name: String = "BY0AABBCCDD",
        cached: String? = null,
    ) = DoorConfig(
        id = id,
        name = "一号楼大门",
        mac = mac,
        key = key,
        bluetoothName = name,
        cachedPeripheralId = cached,
    )

    private fun hit(deviceId: String = realMac, name: String = "BY0AABBCCDD") =
        ScanHit(deviceId = deviceId, name = name, rssi = -52)

    /** 可手动拨动的时钟：用来验证「不支持直连」的冷却期。 */
    private class StepClock(var nowMillis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
    }

    private class Rig(
        val adapter: FakeBleAdapter,
        val history: FakeHistory,
        val cache: FakePeripheralCache,
        val orchestrator: UnlockOrchestrator,
        val clock: StepClock,
    )

    private fun build(
        hits: List<ScanHit>,
        sessionDeviceId: String = realMac,
        timeouts: UnlockTimeouts = UnlockTimeouts(),
        configure: (FakeBleAdapter) -> Unit = {},
    ): Rig {
        val clock = StepClock(1_000_000L)
        val adapter = FakeBleAdapter(hits = hits).also {
            it.session = FakeBleSession(deviceId = sessionDeviceId, responder = { listOf(TestFrames.handshakeOk()) })
        }
        configure(adapter)
        val history = FakeHistory()
        val cache = FakePeripheralCache()
        val orchestrator = UnlockOrchestrator(adapter, history, cache, clock = clock, timeouts = timeouts)
        return Rig(adapter, history, cache, orchestrator, clock)
    }

    @Test
    fun realMacConnectsWithoutScanning() = runTest {
        val r = build(hits = listOf(hit()))

        val result = r.orchestrator.unlock(door())

        assertTrue(result.message, result.success)
        assertEquals(0, r.adapter.scanCalls)
        assertEquals(listOf(realMac), r.adapter.connectedTo)
        assertEquals(realMac, r.cache.cached["door_1"])
    }

    @Test
    fun derivedMacSkipsDirectConnect() = runTest {
        // 门禁的 MAC 是 iOS 编辑器按蓝牙名反推的；真实地址只有扫描才知道
        val r = build(hits = listOf(hit()))

        val result = r.orchestrator.unlock(door(mac = "00:00:AA:BB:CC:DD"))

        assertTrue(result.message, result.success)
        // 没有去连 00:00:AA:BB:CC:DD，只连了扫描到的真实地址
        assertEquals(listOf(realMac), r.adapter.connectedTo)
        assertEquals(1, r.adapter.scanCalls)
        assertEquals(realMac, r.cache.cached["door_1"])
        // 日志里说明了为什么没有直连，贴日志就能看出走的是哪条路
        assertTrue(r.orchestrator.logs.value.any { it.text.contains("按蓝牙名推算") })
    }

    @Test
    fun directFailureFallsBackToScanInTheSameAttempt() = runTest {
        val r = build(hits = listOf(hit()), configure = { it.directUnsupported = setOf(realMac) })

        val result = r.orchestrator.unlock(door())

        assertTrue(result.message, result.success)
        // 一次直连失败 + 一次扫描后连上
        assertEquals(listOf(realMac, realMac), r.adapter.connectedTo)
        assertEquals(1, r.adapter.scanCalls)
        assertTrue(r.orchestrator.logs.value.any { it.text.contains("改为扫描") })
    }

    @Test
    fun autoRetryDoesNotRepeatDirectConnectWithinTheSameRun() = runTest {
        // 门锁不支持直连，且回执一直不来：两次尝试都只能靠扫描
        val r = build(
            hits = listOf(hit()),
            timeouts = UnlockTimeouts(ackMs = 100),
            configure = {
                it.directUnsupported = setOf(realMac)
                it.session = FakeBleSession(deviceId = realMac, responder = { emptyList() })
            },
        )

        val result = r.orchestrator.unlock(door(), UnlockOptions(autoRetry = true, autoRetryCount = 2))

        assertFalse(result.success)
        assertEquals(2, r.adapter.scanCalls)
        // 第一次尝试：直连失败 1 次 + 扫描连上 1 次；第二次尝试：只有扫描连上 1 次
        assertEquals(listOf(realMac, realMac, realMac), r.adapter.connectedTo)
    }

    @Test
    fun scanSuccessAfterDirectFailureStartsACooldown() = runTest {
        val hits = mutableListOf(hit())
        val r = build(hits = hits, configure = { it.directUnsupported = setOf(realMac) })

        assertTrue(r.orchestrator.unlock(door()).success) // 第一次：直连失败 → 扫描连上
        assertTrue(r.orchestrator.unlock(door(cached = realMac)).success) // 冷却期内：不再先直连

        assertEquals(2, r.adapter.scanCalls)
        assertEquals(listOf(realMac, realMac, realMac), r.adapter.connectedTo)
    }

    @Test
    fun failedScanAfterDirectFailureDoesNotStartACooldown() = runTest {
        // 第一次：门锁不在门前，直连和扫描都失败；第二次：到了门前，应该仍然先直连
        val hits = mutableListOf<ScanHit>()
        val r = build(hits = hits, configure = { it.directUnsupported = setOf(realMac) })

        val first = r.orchestrator.unlock(door(), UnlockOptions(scanTimeoutMs = 100))
        assertFalse(first.success)

        hits += hit()
        r.adapter.directUnsupported = emptySet()
        val second = r.orchestrator.unlock(door())
        assertTrue(second.message, second.success)

        assertEquals(1, r.adapter.scanCalls) // 第二次没有扫描，直接连上了
        assertEquals(listOf(realMac, realMac), r.adapter.connectedTo)
    }

    @Test
    fun directConnectIsRetriedAfterTheCooldown() = runTest {
        val r = build(hits = listOf(hit()), configure = { it.directUnsupported = setOf(realMac) })

        assertTrue(r.orchestrator.unlock(door()).success) // 标记为不支持直连
        assertEquals(1, r.adapter.scanCalls)

        r.adapter.directUnsupported = emptySet() // 门锁恢复支持直连
        r.clock.nowMillis += UnlockOrchestrator.DIRECT_RETRY_COOLDOWN_MS + 1
        assertTrue(r.orchestrator.unlock(door()).success) // 冷却期过后重新直连

        assertEquals(1, r.adapter.scanCalls) // 没有再扫描
        assertEquals(listOf(realMac, realMac, realMac), r.adapter.connectedTo)
    }

    @Test
    fun cachedAddressIsPreferredOverMac() = runTest {
        val cachedMac = "AA:BB:CC:DD:EE:FF"
        val r = build(hits = listOf(hit(cachedMac), hit()), sessionDeviceId = cachedMac)

        assertTrue(r.orchestrator.unlock(door(cached = cachedMac)).success)

        assertEquals(listOf(cachedMac), r.adapter.connectedTo)
        assertEquals(0, r.adapter.scanCalls)
    }

    @Test
    fun cachedAddressKeepsTheExistingBudget() = runTest {
        val r = build(hits = listOf(hit()))

        r.orchestrator.unlock(door())
        assertEquals(listOf(6_000L), r.adapter.connectBudgets)

        r.orchestrator.unlock(door(cached = realMac))
        assertEquals(listOf(6_000L, 8_000L), r.adapter.connectBudgets)
    }

    @Test
    fun quickModeNeverConnectsDirectly() = runTest {
        val r = build(hits = listOf(hit()))

        assertTrue(r.orchestrator.unlockAny(listOf(door())).success)

        assertEquals(1, r.adapter.scanCalls)
        assertEquals(listOf(realMac), r.adapter.connectedTo)
    }

    @Test
    fun placeholderMacIsNeverUsedForDirectConnect() = runTest {
        val r = build(hits = listOf(hit()))

        assertTrue(r.orchestrator.unlock(door(mac = "02:00:00:00:00:00")).success)

        assertEquals(listOf(realMac), r.adapter.connectedTo)
        assertEquals(1, r.adapter.scanCalls)
    }

    @Test
    fun silentModeWithoutCacheNeitherConnectsNorScans() = runTest {
        val r = build(hits = listOf(hit()))

        val result = r.orchestrator.unlockIfCached(door())

        assertFalse(result.success)
        assertEquals(0, r.adapter.scanCalls)
        assertTrue(r.adapter.connectedTo.isEmpty())
        assertEquals(UnlockPhase.Idle, r.orchestrator.phase.value)
    }

    @Test
    fun silentModeStaysQuietWhenCachedConnectFails() = runTest {
        val r = build(hits = emptyList(), configure = { it.connectError = IllegalStateException("GATT 133") })

        val result = r.orchestrator.unlockIfCached(door(cached = realMac))

        assertFalse(result.success)
        assertEquals(0, r.adapter.scanCalls)
        assertTrue(r.history.records.isEmpty())
        assertEquals(UnlockPhase.Idle, r.orchestrator.phase.value)
    }
}
