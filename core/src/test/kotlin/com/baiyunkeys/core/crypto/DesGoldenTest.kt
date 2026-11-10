package com.baiyunkeys.core.crypto

import com.baiyunkeys.core.TestVectors
import com.baiyunkeys.core.str
import org.junit.Assert.assertEquals
import org.junit.Test

/** DES golden vectors。 */
class DesGoldenTest {

    @Test
    fun encryptMatchesReference() {
        TestVectors.array("des").forEach { element ->
            val case = element.asJsonObject
            assertEquals(
                "DES encrypt key=${case.str("keyHex")} block=${case.str("blockHex")}",
                case.str("enc"),
                DesBlock.encryptBlock(case.str("keyHex"), case.str("blockHex")),
            )
        }
    }

    @Test
    fun decryptMatchesReference() {
        TestVectors.array("des").forEach { element ->
            val case = element.asJsonObject
            assertEquals(
                "DES decrypt key=${case.str("keyHex")}",
                case.str("dec"),
                DesBlock.decryptBlock(case.str("keyHex"), case.str("enc")),
            )
        }
    }

    /** 多块 ECB：两块输入必须等于两个单块结果首尾相接（通讯密钥协商用到）。 */
    @Test
    fun multiBlockEqualsConcatenatedSingleBlocks() {
        val key = "00112233445566778899AABBCCDDEEFF"
        val first = "0001020304050607"
        val second = "08090A0B0C0D0E0F"
        assertEquals(
            DesBlock.encryptBlock(key, first) + DesBlock.encryptBlock(key, second),
            DesBlock.encryptBlock(key, first + second),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonBlockAlignedInput() {
        DesBlock.encryptBlock("0011223344556677", "000102")
    }
}
