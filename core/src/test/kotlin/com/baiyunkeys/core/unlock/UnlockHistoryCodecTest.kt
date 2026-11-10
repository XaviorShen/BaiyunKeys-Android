package com.baiyunkeys.core.unlock

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史记录编解码：往返、上限 50、坏数据不崩。 */
class UnlockHistoryCodecTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-10T13:20:30Z"), ZoneOffset.UTC)

    private fun record(ok: Boolean = true, name: String = "一号楼大门", message: String = "开门成功") =
        UnlockRecord.now(
            clock = clock,
            doorId = "door_1",
            doorName = name,
            ok = ok,
            message = message,
        )

    @Test
    fun roundTrip() {
        val records = listOf(record(), record(ok = false, message = "握手失败，设备返回码 0x000B"))
        assertEquals(records, UnlockHistoryCodec.decode(UnlockHistoryCodec.encode(records)))
    }

    @Test
    fun timestampIsPreserved() {
        val restored = UnlockHistoryCodec.decode(UnlockHistoryCodec.encode(listOf(record()))).single()
        assertEquals(clock.millis(), restored.atEpochMillis)
        assertEquals(Instant.parse("2026-11-10T13:20:30Z"), restored.at)
    }

    @Test
    fun addPrependsAndTrims() {
        var records = emptyList<UnlockRecord>()
        repeat(UnlockHistoryCodec.LIMIT + 5) { index ->
            records = UnlockHistoryCodec.add(records, record(name = "门禁 $index"))
        }
        assertEquals(UnlockHistoryCodec.LIMIT, records.size)
        assertEquals("最新的排最前", "门禁 54", records.first().doorName)
    }

    @Test
    fun encodeAlsoTrims() {
        val tooMany = (0 until 80).map { record(name = "门禁 $it") }
        assertEquals(UnlockHistoryCodec.LIMIT, UnlockHistoryCodec.decode(UnlockHistoryCodec.encode(tooMany)).size)
    }

    @Test
    fun garbageDecodesToEmpty() {
        assertTrue(UnlockHistoryCodec.decode("").isEmpty())
        assertTrue(UnlockHistoryCodec.decode("[broken").isEmpty())
        assertTrue(UnlockHistoryCodec.decode("{}").isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val json = """[{"atEpochMillis":1,"doorId":"d","doorName":"n","ok":true,"message":"m","future":9}]"""
        assertEquals(1, UnlockHistoryCodec.decode(json).size)
    }
}
