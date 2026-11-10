package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.model.DoorConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 广播匹配：MAC 优先，其次蓝牙名，再看厂商数据。 */
class DeviceMatcherTest {

    private val door = DoorConfig(
        id = "door_1",
        name = "一号楼大门",
        mac = "AA:BB:CC:DD:EE:FF",
        key = "00112233445566778899AABBCCDDEEFF",
        bluetoothName = "BY0AABBCCDD",
    )

    @Test
    fun matchesByMacEvenWithoutName() {
        assertTrue(DeviceMatcher.matches(ScanHit("AA:BB:CC:DD:EE:FF", null, -50), door))
        // 大小写与分隔符无关
        assertTrue(DeviceMatcher.matches(ScanHit("aa-bb-cc-dd-ee-ff", null, -50), door))
    }

    @Test
    fun matchesByBluetoothName() {
        assertTrue(DeviceMatcher.matches(ScanHit("00:11:22:33:44:55", "by0aabbccdd", -50), door))
    }

    @Test
    fun ignoresUnrelatedDevices() {
        assertFalse(DeviceMatcher.matches(ScanHit("00:11:22:33:44:55", "MIBAND-9", -50), door))
        assertFalse(DeviceMatcher.matches(ScanHit("00:11:22:33:44:55", null, -50), door))
    }

    @Test
    fun matchesByManufacturerData() {
        val macBytes = byteArrayOf(
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        assertTrue(DeviceMatcher.matches(ScanHit("00:11:22:33:44:55", null, -50, macBytes), door))

        val reversed = byteArrayOf(
            0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(),
            0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte(),
        )
        assertTrue(DeviceMatcher.matches(ScanHit("00:11:22:33:44:55", null, -50, reversed), door))
    }

    @Test
    fun doesNotMatchOnGarbageManufacturerData() {
        assertFalse(
            DeviceMatcher.matches(
                ScanHit("00:11:22:33:44:55", null, -50, byteArrayOf(0x01, 0x02, 0x03)),
                door,
            ),
        )
    }
}
