package com.baiyunkeys.core.protocol

import com.baiyunkeys.core.TestVectors
import com.baiyunkeys.core.bool
import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.str
import com.google.gson.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** BLE 协议 golden vectors：握手帧 / 通讯密钥帧 / 时间同步帧 / 回执解析。 */
class BleProtocolGoldenTest {

    @Test
    fun deviceIdPartsAndDerivedMac() {
        TestVectors.array("deviceId").forEach { element ->
            val case = element.asJsonObject
            val name = case.str("name")
            assertEquals("deviceIdParts($name)", case.str("parts"), Hex.hexFromBytes(BleProtocol.deviceIdParts(name)))
            assertEquals("macFromBluetoothName($name)", case.str("derivedMac"), BleProtocol.macFromBluetoothName(name))
        }
    }

    @Test
    fun handshakeFrames() {
        TestVectors.array("frames", "handshake").forEach { element ->
            val case = element.asJsonObject
            val frame = BleProtocol.handshakeCommand(
                random = case.bytes("random"),
                header = BleProtocol.headerFromMac(case.str("mac")),
                productKey = case.str("productKey"),
            )
            assertEquals("handshake mac=${case.str("mac")}", case.str("out"), Hex.hexFromBytes(frame))
        }
    }

    @Test
    fun commKeyFrames() {
        TestVectors.array("frames", "commKey").forEach { element ->
            val case = element.asJsonObject
            val frame = BleProtocol.commKeyCommand(
                random = case.bytes("random"),
                bluetoothName = case.str("bluetoothName"),
                productKey = case.str("productKey"),
            )
            assertEquals(
                "commKey name=${case.str("bluetoothName")}",
                case.str("out"),
                Hex.hexFromBytes(frame),
            )
        }
    }

    @Test
    fun timeSyncFrames() {
        TestVectors.array("frames", "timeSync").forEach { element ->
            val case = element.asJsonObject
            val frame = BleProtocol.timeSyncCommand(
                timeBytes = case.bytes("timeBytes"),
                bluetoothName = case.str("bluetoothName"),
                sessionKey = case.str("sessionKey"),
            )
            assertEquals(
                "timeSync name=${case.str("bluetoothName")}",
                case.str("out"),
                Hex.hexFromBytes(frame),
            )
        }
    }

    @Test
    fun timeHex() {
        TestVectors.array("timeHex").forEach { element ->
            val case = element.asJsonObject
            val zone = ZoneId.of(case.str("zone"))
            val instant = LocalDateTime.parse(case.str("local")).atZone(zone).toInstant()
            assertEquals("timeHex(${case.str("local")} @ ${case.str("zone")})", case.str("hex"), BleProtocol.timeHex(instant, zone))
        }
    }

    @Test
    fun openResult() {
        TestVectors.array("openResult").forEach { element ->
            val case = element.asJsonObject
            val result = BleProtocol.openResult(case.str("frameHex"), case.str("productKey"))
            assertEquals("openResult code for ${case.str("frameHex").take(20)}…", case.str("code"), result.code)
            assertEquals("openResult message", case.str("message"), result.message)
            assertEquals("openResult isSuccess", case.bool("isSuccess"), result.isSuccess)
        }
    }

    @Test
    fun commKeyFromBody() {
        TestVectors.array("commKeyBody").forEach { element ->
            val case = element.asJsonObject
            assertEquals(
                "commKeyFromBody(${case.str("bodyHex")})",
                case.str("out"),
                BleProtocol.commKeyFromBody(case.str("bodyHex"), case.str("productKey")),
            )
        }
    }

    /** 校验和必须落在倒数第二字节：`~Σ & 0xFF`。 */
    @Test
    fun checksumIsInPenultimateByte() {
        val frame = BleProtocol.handshakeCommand(
            random = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            header = BleProtocol.headerFromMac("00:0A:AB:BC:CD:DD"),
            productKey = "00112233445566778899AABBCCDDEEFF",
        )
        assertEquals(0x5A, frame.last().toInt() and 0xFF)
        val sum = frame.sumOf { it.toInt() and 0xFF }
        assertEquals(0xFF, sum and 0xFF)
    }

    private fun JsonObject.bytes(key: String): ByteArray {
        val array = getAsJsonArray(key)
        return ByteArray(array.size()) { array[it].asInt.toByte() }
    }
}
