package com.baiyunkeys.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 门禁模型的展示 / 可用性语义（脱敏是全角星号，必须与 iOS 视觉一致）。 */
class DoorConfigTest {

    private val door = DoorConfig(
        id = "door_test0001",
        name = "一号楼大门",
        mac = "00:0A:AB:BC:CD:DD",
        key = "00112233445566778899AABBCCDDEEFF",
        bluetoothName = "BY0AABBCCDD",
    )

    @Test
    fun displayNameFallsBackWhenBlank() {
        assertEquals("未命名门禁", DoorConfig(name = "   ").displayName)
        assertEquals("一号楼大门", door.displayName)
    }

    @Test
    fun maskedMacUsesFullWidthAsterisks() {
        assertEquals("00:0A:＊＊:＊＊:＊＊:DD", door.maskedMac(hidden = true))
        assertEquals("00:0A:AB:BC:CD:DD", door.maskedMac(hidden = false))
        assertEquals("未填写", DoorConfig().maskedMac(hidden = true))
    }

    @Test
    fun maskedKeyKeepsHeadAndTail() {
        assertEquals("0011" + "＊".repeat(24) + "EEFF", door.maskedKey(hidden = true))
        assertEquals(door.key, door.maskedKey(hidden = false))
        assertEquals("未填写", DoorConfig().maskedKey(hidden = true))
    }

    @Test
    fun usableNeedsMacAndKeyOnly() {
        assertTrue(door.isUsable)
        // Android 能直接读 MAC，蓝牙名缺失（iOS 必填）也应可用
        assertTrue(door.copy(bluetoothName = "").isUsable)
        assertFalse(door.copy(mac = "00:0A").isUsable)
        assertFalse(door.copy(key = "ABCD").isUsable)
    }

    @Test
    fun newIdHasExpectedShape() {
        val id = DoorConfig.newId()
        assertTrue(id, id.startsWith("door_") && id.length == 13)
        assertTrue(id, id.drop(5).all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun doorStateCurrentResolution() {
        val state = DoorState(currentId = "door_b", items = listOf(door.copy(id = "door_a"), door.copy(id = "door_b")))
        assertEquals("door_b", state.current?.id)
        // currentId 指向不存在的 id 时退回第一条（与 iOS DoorState.current 一致）
        assertEquals("door_a", state.copy(currentId = "door_x").current?.id)
        assertEquals("door_a", state.copy(currentId = null).current?.id)
    }
}
