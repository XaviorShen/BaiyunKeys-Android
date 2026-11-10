package com.baiyunkeys.core.hex

import com.baiyunkeys.core.TestVectors
import com.baiyunkeys.core.bool
import com.baiyunkeys.core.int
import com.baiyunkeys.core.str
import org.junit.Assert.assertEquals
import org.junit.Test

/** Hex golden vectors：与 iOS / web 实现逐条相等。 */
class HexGoldenTest {

    @Test
    fun normalize() {
        TestVectors.array("hex", "normalize").forEach { element ->
            val case = element.asJsonObject
            assertEquals("normalize(${case.str("in")})", case.str("out"), Hex.normalize(case.str("in")))
        }
    }

    @Test
    fun bytesFromHex() {
        TestVectors.array("hex", "bytesFromHex").forEach { element ->
            val case = element.asJsonObject
            assertEquals(
                "bytesFromHex(${case.str("in")})",
                case.str("out"),
                Hex.hexFromBytes(Hex.bytesFromHex(case.str("in"))),
            )
        }
    }

    @Test
    fun formatMac() {
        TestVectors.array("hex", "formatMac").forEach { element ->
            val case = element.asJsonObject
            assertEquals("formatMac(${case.str("in")})", case.str("out"), Hex.formatMac(case.str("in")))
        }
    }

    @Test
    fun isValidMac() {
        TestVectors.array("hex", "isValidMac").forEach { element ->
            val case = element.asJsonObject
            assertEquals("isValidMac(${case.str("in")})", case.bool("out"), Hex.isValidMac(case.str("in")))
        }
    }

    @Test
    fun isValidKey() {
        TestVectors.array("hex", "isValidKey").forEach { element ->
            val case = element.asJsonObject
            assertEquals("isValidKey(${case.str("in")})", case.bool("out"), Hex.isValidKey(case.str("in")))
        }
    }

    @Test
    fun sanitizeKey() {
        TestVectors.array("hex", "sanitizeKey").forEach { element ->
            val case = element.asJsonObject
            assertEquals("sanitizeKey(${case.str("in")})", case.str("out"), Hex.sanitizeKey(case.str("in")))
        }
    }

    @Test
    fun reversedPairs() {
        TestVectors.array("hex", "reversedPairs").forEach { element ->
            val case = element.asJsonObject
            assertEquals("reversedPairs(${case.str("in")})", case.str("out"), Hex.reversedPairs(case.str("in")))
        }
    }

    @Test
    fun ascii() {
        TestVectors.array("hex", "ascii").forEach { element ->
            val case = element.asJsonObject
            assertEquals("ascii(${case.str("in")})", case.str("out"), Hex.ascii(case.str("in")))
        }
    }

    @Test
    fun hexSlice() {
        TestVectors.array("hex", "hexSlice").forEach { element ->
            val case = element.asJsonObject
            val input = case.str("in")
            val start = case.int("start")
            val len = case.int("len")
            assertEquals(
                "hexSlice($input, $start, $len)",
                case.str("out"),
                Hex.hexSlice(input, start, len),
            )
        }
    }
}
