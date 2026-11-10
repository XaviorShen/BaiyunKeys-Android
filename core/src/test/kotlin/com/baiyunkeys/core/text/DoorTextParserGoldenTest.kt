package com.baiyunkeys.core.text

import com.baiyunkeys.core.TestVectors
import com.baiyunkeys.core.str
import org.junit.Assert.assertEquals
import org.junit.Test

/** 文本导入 golden vectors（与 iOS 导出格式逐字互通）。 */
class DoorTextParserGoldenTest {

    @Test
    fun parseMatchesReference() {
        TestVectors.array("textParser").forEach { element ->
            val case = element.asJsonObject
            val text = case.str("text")
            val expected = case.getAsJsonArray("doors")
            val actual = DoorTextParser.parse(text)

            assertEquals("条数不符，文本=${text.replace("\n", "\\n")}", expected.size(), actual.size)
            for (i in 0 until expected.size()) {
                val want = expected[i].asJsonObject
                val got = actual[i]
                assertEquals("第 ${i + 1} 条 name", want.str("name"), got.name)
                assertEquals("第 ${i + 1} 条 mac", want.str("mac"), got.mac)
                assertEquals("第 ${i + 1} 条 key", want.str("key"), got.key)
                assertEquals("第 ${i + 1} 条 bluetoothName", want.str("bluetoothName"), got.bluetoothName)
            }
        }
    }
}
