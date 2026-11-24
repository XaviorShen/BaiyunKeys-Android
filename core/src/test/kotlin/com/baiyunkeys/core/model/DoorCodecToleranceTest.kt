package com.baiyunkeys.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 解码容错：一条门禁的数据坏了，只丢那一条，其余照常读出。 */
class DoorCodecToleranceTest {

    private val first = DoorConfig(
        id = "door_first001",
        name = "一号楼大门",
        mac = "C8:5C:AA:BB:CC:DD",
        key = "00112233445566778899AABBCCDDEEFF",
        bluetoothName = "BY0AABBCCDD",
    )

    private val second = first.copy(id = "door_second01", name = "二号楼侧门", mac = "C8:5C:11:22:33:44")

    /** 一条解不出来的门禁：mac 是数字而不是字符串。 */
    private val broken =
        """{"id":"door_broken001","name":"坏数据","mac":123,"key":"0011223344556677","bluetoothName":"BY0AABBCCDD"}"""

    @Test
    fun oneBadItemOnlyDropsItself() {
        val json = """{"version":2,"currentId":"${first.id}","items":[${item(first)},$broken,${item(second)}]}"""

        val report = DoorCodec.decodeWithReport(json)

        assertEquals(3, report.total)
        assertEquals(1, report.failed)
        assertEquals(listOf(first, second), report.state.items)
        assertEquals(first.id, report.state.currentId)
    }

    @Test
    fun selectionFallsBackToTheFirstSurvivingDoor() {
        val json = """{"currentId":"door_broken001","items":[$broken,${item(first)}]}"""

        val report = DoorCodec.decodeWithReport(json)

        assertEquals(1, report.failed)
        // 选中的那条被丢掉了：回落到剩下的第一条
        assertEquals(first.id, report.state.currentId)
    }

    @Test
    fun selectionIsKeptWhenItStillExists() {
        val json = """{"currentId":"${second.id}","items":[${item(first)},${item(second)}]}"""

        assertEquals(second.id, DoorCodec.decodeWithReport(json).state.currentId)
    }

    @Test
    fun nullFieldsFallBackToDefaults() {
        val json = """{"items":[{"id":"door_null00001","mac":null,"key":null,"name":null,"bluetoothName":null}]}"""

        val report = DoorCodec.decodeWithReport(json)

        assertEquals(0, report.failed)
        val item = report.state.items.single()
        assertEquals("", item.mac)
        assertEquals("", item.key)
        assertEquals("未命名门禁", item.displayName)
    }

    @Test
    fun unreadableDocumentReportsFailureWhileBlankTextIsJustEmpty() {
        assertEquals(1, DoorCodec.decodeWithReport("{not json").failed)
        assertEquals(1, DoorCodec.decodeWithReport("[1,2,3]").failed)
        assertEquals(1, DoorCodec.decodeWithReport("""{"items":{"a":1}}""").failed)
        assertEquals(0, DoorCodec.decodeWithReport("").failed)
        assertEquals(DoorState(), DoorCodec.decodeWithReport("   ").state)
    }

    @Test
    fun oldSignatureReturnsJustTheState() {
        val state = DoorState(currentId = second.id, items = listOf(first, second))

        assertEquals(state, DoorCodec.decode(DoorCodec.encode(state)))
        assertEquals(0, DoorCodec.decodeWithReport(DoorCodec.encode(state)).failed)
    }

    private fun item(door: DoorConfig): String = buildString {
        append("{\"id\":\"").append(door.id).append("\",\"name\":\"").append(door.name)
        append("\",\"mac\":\"").append(door.mac).append("\",\"key\":\"").append(door.key)
        append("\",\"bluetoothName\":\"").append(door.bluetoothName).append("\"}")
    }
}
