package com.baiyunkeys.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 门禁 JSON 编解码的兼容性：老数据能读、未知字段能忽略、坏数据不崩。 */
class DoorCodecTest {

    private val door = DoorConfig(
        id = "door_abc12345",
        name = "一号楼大门",
        mac = "00:0A:AB:BC:CD:DD",
        key = "00112233445566778899AABBCCDDEEFF",
        bluetoothName = "BY0AABBCCDD",
        cachedPeripheralId = "00:0A:AB:BC:CD:DD",
    )

    @Test
    fun roundTrip() {
        val state = DoorState(version = 2, currentId = door.id, items = listOf(door))
        val restored = DoorCodec.decode(DoorCodec.encode(state))
        assertEquals(state, restored)
    }

    @Test
    fun emptyStateRoundTrip() {
        assertEquals(DoorState(), DoorCodec.decode(DoorCodec.encode(DoorState())))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val json = """
            {
              "version": 2,
              "currentId": "door_abc12345",
              "futureField": {"nested": true},
              "items": [
                {
                  "id": "door_abc12345",
                  "name": "一号楼大门",
                  "mac": "00:0A:AB:BC:CD:DD",
                  "key": "00112233445566778899AABBCCDDEEFF",
                  "bluetoothName": "BY0AABBCCDD",
                  "brandNewField": 42
                }
              ]
            }
        """.trimIndent()

        val state = DoorCodec.decode(json)
        assertEquals(1, state.items.size)
        assertEquals("一号楼大门", state.items.single().name)
        assertNull(state.items.single().cachedPeripheralId)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val restored = DoorCodec.decode("""{"items":[{"mac":"AA:BB:CC:DD:EE:FF"}]}""")
        assertEquals(1, restored.items.size)
        assertEquals("AA:BB:CC:DD:EE:FF", restored.items.single().mac)
        assertEquals("未命名门禁", restored.items.single().displayName)
        assertEquals(2, restored.version)
    }

    @Test
    fun garbageDecodesToEmptyState() {
        assertEquals(DoorState(), DoorCodec.decode("{not json"))
        assertEquals(DoorState(), DoorCodec.decode(""))
        assertEquals(DoorState(), DoorCodec.decode("   "))
        assertEquals(DoorState(), DoorCodec.decode("[1,2,3]"))
    }

    @Test
    fun nullCachedPeripheralIsOmitted() {
        val encoded = DoorCodec.encode(DoorState(items = listOf(door.copy(cachedPeripheralId = null))))
        assertTrue("explicitNulls=false 时不应写出 null", !encoded.contains("cachedPeripheralId"))
    }
}
