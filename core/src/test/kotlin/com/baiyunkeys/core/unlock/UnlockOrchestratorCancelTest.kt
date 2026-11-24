package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.model.DoorConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「取消」按钮要能停掉正在跑的流程，哪怕期间又有人点过一次开锁。 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockOrchestratorCancelTest {

    private fun door() = DoorConfig(
        id = "door_1",
        name = "一号楼大门",
        mac = "C8:5C:AA:BB:CC:DD",
        key = "00112233445566778899AABBCCDDEEFF",
        bluetoothName = "BY0AABBCCDD",
    )

    @Test
    fun cancelStopsTheRunningFlowEvenIfAnotherUnlockWasRequestedMeanwhile() = runTest {
        // 门锁不在广播里：直连失败后流程停在扫描里，直到超时
        val orchestrator = UnlockOrchestrator(
            ble = FakeBleAdapter(hits = emptyList()),
            history = FakeHistory(),
            peripheralCache = FakePeripheralCache(),
            scope = backgroundScope,
        )

        val running = orchestrator.unlockAsync(door())
        runCurrent()
        assertEquals(UnlockPhase.Scanning, orchestrator.phase.value)

        // 流程正忙：这一次一启动就返回「已在进行中」
        val rejected = orchestrator.unlockAsync(door())
        runCurrent()
        assertTrue(rejected.isCompleted)

        orchestrator.cancel()
        runCurrent()

        assertEquals("已中断", orchestrator.statusText.value)
        assertEquals(UnlockPhase.Idle, orchestrator.phase.value)
        assertTrue("真正在跑的那个流程要被取消", running.isCancelled)
    }
}
