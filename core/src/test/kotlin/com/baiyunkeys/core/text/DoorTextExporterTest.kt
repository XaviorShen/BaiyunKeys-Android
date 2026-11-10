package com.baiyunkeys.core.text

import com.baiyunkeys.core.model.DoorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导出格式必须能被 [DoorTextParser] 原样读回（跨设备搬运的唯一验收标准）。 */
class DoorTextExporterTest {

    private val door = DoorConfig(
        id = "door_1",
        name = "一号楼大门",
        mac = "00:0A:AB:BC:CD:DD",
        key = "00112233445566778899AABBCCDDEEFF",
        bluetoothName = "BY0AABBCCDD",
    )

    @Test
    fun singleDoorFormatMatchesIos() {
        val expected = """
            门禁名称：一号楼大门
            门禁 MAC：00:0A:AB:BC:CD:DD
            门禁 Key：00112233445566778899AABBCCDDEEFF
            蓝牙名称：BY0AABBCCDD
        """.trimIndent()
        assertEquals(expected, DoorTextExporter.export(door))
    }

    @Test
    fun roundTripThroughParser() {
        val doors = listOf(
            door,
            door.copy(id = "door_2", name = "二号楼侧门", mac = "11:22:33:44:55:66", bluetoothName = "BY112233445"),
            door.copy(id = "door_3", name = "", mac = "AA:BB:CC:DD:EE:FF", bluetoothName = ""),
        )
        val text = DoorTextExporter.exportAll(doors)
        val parsed = DoorTextParser.parse(text)

        assertEquals(doors.size, parsed.size)
        doors.forEachIndexed { index, original ->
            val restored = parsed[index]
            assertEquals(original.mac, restored.mac)
            assertEquals(original.key, restored.key)
            assertEquals(original.bluetoothName, restored.bluetoothName)
            // 空名字会变成「未命名门禁」，这是 iOS 的既有行为
            assertEquals(original.displayName, restored.name)
        }
    }

    @Test
    fun entriesAreSeparatedByBlankLine() {
        val text = DoorTextExporter.exportAll(listOf(door, door.copy(id = "door_2")))
        assertTrue(text.contains("\n\n"))
        assertEquals(2, DoorTextParser.parse(text).size)
    }

    @Test
    fun emptyListExportsEmptyString() {
        assertEquals("", DoorTextExporter.exportAll(emptyList()))
        assertTrue(DoorTextParser.parse("").isEmpty())
    }
}
