package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.model.DoorConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态机测试：用假适配器脚本化「门锁的响应」，全程不碰蓝牙。
 *
 * 覆盖方案 §6 要求的六类场景：正常开锁 / 握手拒绝 000B / 协商拒绝 E36F / 分片 / 超时重试 / 静默无缓存。
 */
class UnlockOrchestratorTest {

    private val productKey = "00112233445566778899AABBCCDDEEFF"

    private fun door(
        id: String = "door_1",
        name: String = "一号楼大门",
        mac: String = "AA:BB:CC:DD:EE:FF",
        bluetoothName: String = "BY0AABBCCDD",
        cached: String? = null,
        key: String = productKey,
    ) = DoorConfig(
        id = id,
        name = name,
        mac = mac,
        key = key,
        bluetoothName = bluetoothName,
        cachedPeripheralId = cached,
    )

    private fun hit(deviceId: String = "AA:BB:CC:DD:EE:FF", name: String = "BY0AABBCCDD") =
        ScanHit(deviceId = deviceId, name = name, rssi = -52)

    private class Rig(
        val adapter: FakeBleAdapter,
        val history: FakeHistory,
        val cache: FakePeripheralCache,
        val haptics: RecordingHaptics,
        val orchestrator: UnlockOrchestrator,
    )

    private fun rig(
        hits: List<ScanHit>,
        session: FakeBleSession?,
        state: BleState = BleState.PoweredOn,
        timeouts: UnlockTimeouts = UnlockTimeouts(),
    ): Rig {
        val adapter = FakeBleAdapter(hits = hits, initialState = state).also { it.session = session }
        val history = FakeHistory()
        val cache = FakePeripheralCache()
        val haptics = RecordingHaptics()
        val orchestrator = UnlockOrchestrator(
            ble = adapter,
            history = history,
            peripheralCache = cache,
            haptics = haptics,
            timeouts = timeouts,
        )
        return Rig(adapter, history, cache, haptics, orchestrator)
    }

    @Test
    fun happyPathWritesHandshakeAndRecordsHistory() = runTest {
        val target = door()
        val session = FakeBleSession(
            deviceId = "AA:BB:CC:DD:EE:FF",
            responder = { listOf(TestFrames.handshakeOk()) },
        )
        val r = rig(hits = listOf(hit()), session = session)

        val result = r.orchestrator.unlock(target)

        assertTrue(result.message, result.success)
        assertEquals(UnlockPhase.Success, r.orchestrator.phase.value)
        assertEquals("握手成功，门锁已执行开锁", r.orchestrator.statusText.value)

        // 只有一条历史，且标记成功
        assertEquals(1, r.history.records.size)
        assertTrue(r.history.records.single().ok)
        assertEquals(target.id, r.history.records.single().doorId)
        assertEquals(target.displayName, r.history.records.single().doorName)

        // 成功后缓存设备，供下次直连
        assertEquals("AA:BB:CC:DD:EE:FF", r.cache.cached[target.id])
        assertEquals(1, r.haptics.successes)

        // 真的发了握手帧，且订阅了通知特征
        assertEquals(1, session.written.size)
        assertEquals(1, session.subscribed.size)
        assertTrue("会话应被关闭", session.closedTimes > 0)
    }

    @Test
    fun handshakeRejectEndsWithStatusCode() = runTest {
        val session = FakeBleSession(
            deviceId = "AA:BB:CC:DD:EE:FF",
            responder = { listOf(TestFrames.handshakeReject("000B")) },
        )
        val r = rig(hits = listOf(hit()), session = session)

        val result = r.orchestrator.unlock(door())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("0x000B"))
        assertEquals(UnlockPhase.Failure(result.message), r.orchestrator.phase.value)
        assertEquals(1, r.history.records.size)
        assertFalse(r.history.records.single().ok)
        assertEquals(1, r.haptics.failures)
        // 协议层失败不重试
        assertEquals(1, r.adapter.connectedTo.size)
    }

    @Test
    fun negotiationRejectIsProtocolFailure() = runTest {
        val session = FakeBleSession(
            deviceId = "AA:BB:CC:DD:EE:FF",
            responder = { listOf(TestFrames.negotiationReject("E36F")) },
        )
        val r = rig(hits = listOf(hit()), session = session)

        val result = r.orchestrator.unlock(door())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("0xE36F"))
        assertEquals(1, r.adapter.connectedTo.size)
    }

    @Test
    fun finalReceiptSuccessPath() = runTest {
        val session = FakeBleSession(
            deviceId = "AA:BB:CC:DD:EE:FF",
            responder = { listOf(TestFrames.receipt("00", productKey)) },
        )
        val r = rig(hits = listOf(hit()), session = session)

        val result = r.orchestrator.unlock(door())

        assertTrue(result.message, result.success)
        assertEquals("开门成功", result.message)
    }

    @Test
    fun noReceiptRetriesUpToMaxAttempts() = runTest {
        // 门锁收到指令但一声不吭 → 回执超时 → 重试
        val session = FakeBleSession(deviceId = "AA:BB:CC:DD:EE:FF", responder = { emptyList() })
        val r = rig(
            hits = listOf(hit()),
            session = session,
            timeouts = UnlockTimeouts(ackMs = 100),
        )

        val result = r.orchestrator.unlock(
            door(),
            UnlockOptions(autoRetry = true, autoRetryCount = 2),
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("等待门锁回执超时"))
        assertEquals(2, r.adapter.connectedTo.size)
        assertEquals(2, r.orchestrator.attempt.value)
        assertEquals(1, r.history.records.size)
    }

    @Test
    fun scanTimeoutWithoutHitsIsRetryable() = runTest {
        val r = rig(hits = emptyList(), session = null)

        val result = r.orchestrator.unlock(
            door(),
            UnlockOptions(scanTimeoutMs = 100),
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("扫描超时"))
        assertEquals(1, r.adapter.scanCalls)
        assertEquals(0, r.adapter.connectedTo.size)
    }

    @Test
    fun connectFailureClearsPeripheralCache() = runTest {
        val adapter = FakeBleAdapter(hits = listOf(hit()))
        adapter.connectError = IllegalStateException("GATT 133")
        val history = FakeHistory()
        val cache = FakePeripheralCache().apply { put("door_1", "AA:BB:CC:DD:EE:FF") }
        val orchestrator = UnlockOrchestrator(adapter, history, cache)

        val result = orchestrator.unlock(door())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("连接门锁失败"))
        assertTrue("失败后必须清缓存，否则下次还会拿坏 MAC 直连", cache.cleared.contains("door_1"))
    }

    @Test
    fun silentModeWithoutCacheDoesNothing() = runTest {
        val r = rig(hits = listOf(hit()), session = null)

        val result = r.orchestrator.unlockIfCached(door(cached = null))

        assertFalse(result.success)
        assertEquals(UnlockPhase.Idle, r.orchestrator.phase.value)
        assertEquals(0, r.adapter.scanCalls)
        assertTrue("静默模式不该写历史", r.history.records.isEmpty())
        assertTrue("静默模式不该留日志", r.orchestrator.logs.value.isEmpty())
    }

    @Test
    fun silentModeUsesCachedDeviceDirectly() = runTest {
        val session = FakeBleSession(
            deviceId = "AA:BB:CC:DD:EE:FF",
            responder = { listOf(TestFrames.handshakeOk()) },
        )
        val r = rig(hits = listOf(hit()), session = session)

        val result = r.orchestrator.unlockIfCached(door(cached = "AA:BB:CC:DD:EE:FF"))

        assertTrue(result.message, result.success)
        assertEquals(0, r.adapter.scanCalls) // 没有扫描，直接连缓存
        assertEquals(1, r.history.records.size)
    }

    @Test
    fun silentModeFailureStaysQuiet() = runTest {
        val session = FakeBleSession(
            deviceId = "AA:BB:CC:DD:EE:FF",
            responder = { listOf(TestFrames.handshakeReject("000B")) },
        )
        val r = rig(hits = listOf(hit()), session = session)

        val result = r.orchestrator.unlockIfCached(door(cached = "AA:BB:CC:DD:EE:FF"))

        assertFalse(result.success)
        assertEquals(UnlockPhase.Idle, r.orchestrator.phase.value)
        assertTrue("静默失败不写历史", r.history.records.isEmpty())
        assertEquals(0, r.haptics.failures)
    }

    @Test
    fun quickModePicksMatchingCandidate() = runTest {
        val second = door(id = "door_2", name = "二号楼侧门", mac = "11:22:33:44:55:66", bluetoothName = "BY112233445")
        val session = FakeBleSession(
            deviceId = "11:22:33:44:55:66",
            responder = { listOf(TestFrames.handshakeOk()) },
        )
        val r = rig(
            hits = listOf(ScanHit("11:22:33:44:55:66", "BY112233445", -60)),
            session = session,
        )

        val result = r.orchestrator.unlockAny(listOf(door(), second))

        assertTrue(result.message, result.success)
        assertEquals("door_2", r.history.records.single().doorId)
        assertEquals("11:22:33:44:55:66", r.cache.cached["door_2"])
    }

    @Test
    fun quickModeWithoutUsableDoorsFailsFast() = runTest {
        val r = rig(hits = emptyList(), session = null)

        val result = r.orchestrator.unlockAny(listOf(door(mac = "坏 MAC")))

        assertFalse(result.success)
        assertEquals("没有可用的门禁，无法自动开锁", result.message)
        assertEquals(0, r.adapter.scanCalls)
        assertTrue(r.history.records.isEmpty())
    }

    @Test
    fun bluetoothOffFailsWithoutScanning() = runTest {
        val r = rig(hits = listOf(hit()), session = null, state = BleState.PoweredOff)

        val result = r.orchestrator.unlock(door())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("蓝牙已关闭"))
        assertEquals(0, r.adapter.scanCalls)
        assertEquals("蓝牙已关闭，请在控制中心或设置中打开蓝牙", r.orchestrator.bluetoothIssue.value)
    }

    @Test
    fun unusableDoorFailsBeforeTouchingBluetooth() = runTest {
        val r = rig(hits = listOf(hit()), session = null)

        val result = r.orchestrator.unlock(door(key = "ABCD"))

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("门禁参数不完整"))
        assertEquals(0, r.adapter.scanCalls)
        assertTrue("前置校验失败不写历史", r.history.records.isEmpty())
    }

    @Test
    fun scanPermissionErrorEndsInsteadOfHanging() = runTest {
        // 真机回归：缺权限时 BleAdapterImpl.scan 会抛 SecurityException。
        // 之前这个异常会把协程打死，界面永远卡在「进行中」。
        val adapter = FakeBleAdapter(hits = emptyList())
        adapter.scanError = SecurityException("缺少蓝牙扫描权限")
        val history = FakeHistory()
        val orchestrator = UnlockOrchestrator(adapter, history, FakePeripheralCache())

        val result = orchestrator.unlock(door())

        assertFalse(result.success)
        assertTrue("失败文案要能透出原因：${result.message}", result.message.contains("缺少蓝牙扫描权限"))
        assertTrue("必须落到终态而不是卡在扫描中", orchestrator.phase.value is UnlockPhase.Failure)
        assertTrue("异常也要写进历史", history.records.isNotEmpty())
    }

    @Test
    fun connectExceptionEndsWithFailure() = runTest {
        val adapter = FakeBleAdapter(hits = listOf(hit()))
        adapter.connectError = IllegalStateException("蓝牙未开启")
        val orchestrator = UnlockOrchestrator(adapter, FakeHistory(), FakePeripheralCache())

        val result = orchestrator.unlock(door())

        assertFalse(result.success)
        assertTrue(orchestrator.phase.value is UnlockPhase.Failure)
    }

    @Test
    fun logsAreCappedAndFormatted() = runTest {
        val session = FakeBleSession(
            deviceId = "AA:BB:CC:DD:EE:FF",
            responder = { listOf(TestFrames.handshakeOk()) },
        )
        val r = rig(hits = listOf(hit()), session = session)

        r.orchestrator.unlock(door())

        val logs = r.orchestrator.logs.value
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.size <= UnlockLog.MAX_LINES)
        // 格式：HH:mm:ss.SSS [LEVEL] text
        assertTrue(logs.first().format(), Regex("""\d{2}:\d{2}:\d{2}\.\d{3} \[(INFO|WARN|ERROR|DEBUG)] .+""").matches(logs.first().format()))
        assertNotNull(logs.firstOrNull { it.text.startsWith("握手指令") })
    }
}
