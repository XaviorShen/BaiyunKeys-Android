package com.baiyunkeys.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入去重：同 MAC + 同 Key 视为同一把门禁。 */
class DoorMergeTest {

    private fun door(
        id: String = "door_1",
        name: String = "",
        mac: String = "AA:BB:CC:DD:EE:FF",
        key: String = "00112233445566778899AABBCCDDEEFF",
        bluetoothName: String = "",
    ) = DoorConfig(id = id, name = name, mac = mac, key = key, bluetoothName = bluetoothName)

    @Test
    fun addsNewDoors() {
        val result = DoorMerge.merge(DoorState(), listOf(door(), door(id = "door_2", mac = "11:22:33:44:55:66")))
        assertEquals(2, result.added)
        assertEquals(2, result.state.items.size)
        // 没有选中项时选第一条
        assertEquals("door_1", result.state.currentId)
    }

    @Test
    fun duplicateMacAndKeyIsMergedNotDuplicated() {
        val existing = DoorState(currentId = "door_1", items = listOf(door(id = "door_1", name = "老名字")))
        val result = DoorMerge.merge(
            existing,
            listOf(door(id = "door_new", name = "新名字", bluetoothName = "BY0AABBCCDD")),
        )

        assertEquals(0, result.added)
        assertEquals(1, result.state.items.size)
        val merged = result.state.items.single()
        assertEquals("老名字不会被覆盖", "老名字", merged.name)
        assertEquals("缺失的蓝牙名要补齐", "BY0AABBCCDD", merged.bluetoothName)
    }

    @Test
    fun sameMacDifferentKeyIsDifferentDoor() {
        val result = DoorMerge.merge(
            DoorState(items = listOf(door())),
            listOf(door(id = "door_2", key = "A1B2C3D4E5F60718")),
        )
        assertEquals(1, result.added)
        assertEquals(2, result.state.items.size)
    }

    @Test
    fun invalidEntriesAreSkippedSilently() {
        val result = DoorMerge.merge(
            DoorState(),
            listOf(
                door(mac = "坏 MAC"),
                door(key = "ABCD"),
                door(id = "door_3"),
            ),
        )
        assertEquals(1, result.added)
        assertEquals(1, result.state.items.size)
    }

    @Test
    fun macAndKeyAreNormalizedOnImport() {
        val result = DoorMerge.merge(
            DoorState(),
            listOf(door(mac = "aabbccddeeff", key = "00:11-22 334455667788 99AABBCCDDEEFF")),
        )
        val saved = result.state.items.single()
        assertEquals("AA:BB:CC:DD:EE:FF", saved.mac)
        assertEquals("00112233445566778899AABBCCDDEEFF", saved.key)
    }

    @Test
    fun keepsExistingSelection() {
        val existing = DoorState(currentId = "door_x", items = listOf(door(id = "door_x", mac = "11:22:33:44:55:66")))
        val result = DoorMerge.merge(existing, listOf(door()))
        assertEquals("door_x", result.state.currentId)
        assertTrue(result.state.items.any { it.id == "door_x" })
    }

    @Test
    fun emptyInputKeepsState() {
        val existing = DoorState(currentId = "door_1", items = listOf(door()))
        val result = DoorMerge.merge(existing, emptyList())
        assertEquals(0, result.added)
        assertEquals(existing, result.state)
    }
}
