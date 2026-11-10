package com.baiyunkeys.core.crypto

import com.baiyunkeys.core.hex.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SM2 往返 + `04` 前缀兼容（服务端有的带、有的不带）。 */
class Sm2GmTest {

    @Test
    fun roundTrip() {
        val (privateKey, publicKey) = Sm2Gm.generateKeyPair()
        val plaintext = "00112233445566778899AABBCCDDEEFF".toByteArray()

        val cipher = Sm2Gm.encrypt(plaintext, publicKey)
        val decrypted = Sm2Gm.decrypt(cipher, privateKey)

        assertEquals(Hex.hexFromBytes(plaintext), decrypted)
    }

    @Test
    fun javaStringRoundTrip() {
        val (privateKey, publicKey) = Sm2Gm.generateKeyPair()
        val cipher = Sm2Gm.encrypt("A1B2C3D4E5F60718".toByteArray(), publicKey)
        // 解出来是 hex 文本（服务端加密的就是 hex 字符串本身）
        assertEquals("41314232433344344535463630373138", Sm2Gm.decrypt(cipher, privateKey))
    }

    @Test
    fun acceptsCiphertextWithout04Prefix() {
        val (privateKey, publicKey) = Sm2Gm.generateKeyPair()
        val cipher = Sm2Gm.encrypt("hello".toByteArray(), publicKey)
        assertTrue("BC 输出的 C1 带 04 前缀", cipher.startsWith("04"))

        val stripped = cipher.removePrefix("04")
        val decrypted = Sm2Gm.decrypt(stripped, privateKey)
        assertEquals(Hex.hexFromBytes("hello".toByteArray()), decrypted)
    }

    @Test
    fun publicKeyDerivesFromPrivateKey() {
        val (privateKey, publicKey) = Sm2Gm.generateKeyPair()
        assertEquals(publicKey, Sm2Gm.publicKeyFromPrivate(privateKey))
        assertEquals(130, publicKey.length)
        assertTrue(publicKey.startsWith("04"))
        assertEquals(64, privateKey.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortPrivateKey() {
        Sm2Gm.decrypt("04AABB", "1234")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongPrivateKey() {
        val (_, publicKey) = Sm2Gm.generateKeyPair()
        val (otherPrivate, _) = Sm2Gm.generateKeyPair()
        val cipher = Sm2Gm.encrypt("secret".toByteArray(), publicKey)
        Sm2Gm.decrypt(cipher, otherPrivate)
    }
}
