package com.baiyunkeys.core.crypto

import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RSA 分块加密：结构必须对（每块 30 字节一加密 + 每块后都带分隔符），并有可逆性证明。 */
class RsaChunkTest {

    private fun pemOfPublicKey(): String {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val encoded = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return buildString {
            appendLine("-----BEGIN PUBLIC KEY-----")
            encoded.chunked(64).forEach { appendLine(it) }
            append("-----END PUBLIC KEY-----")
        }
    }

    /** 用 JDK 的 PKCS#1 v1.5 解回来，验证分块拼接是「原样可逆」的。 */
    private fun decryptBlocks(encrypted: String, keyPair: java.security.KeyPair): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        val blocks = encrypted.split(RsaChunk.SUFFIX).filter { it.isNotEmpty() }
        val out = StringBuilder()
        blocks.forEach { hex ->
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            out.append(String(cipher.doFinal(bytes), Charsets.UTF_8))
        }
        return out.toString()
    }

    @Test
    fun singleChunkHasTrailingSuffix() {
        val pem = pemOfPublicKey()
        val plaintext = """{"phone":"13800138000"}""" // 23 字节，一块
        val encrypted = RsaChunk.encrypt(plaintext, pem)

        assertTrue("每块后面都要带分隔符", encrypted.endsWith(RsaChunk.SUFFIX))
        assertEquals(1, encrypted.split(RsaChunk.SUFFIX).count { it.isNotEmpty() })
        // 2048 位密钥 → 256 字节密文 → 512 个 hex 字符
        assertEquals(512, encrypted.removeSuffix(RsaChunk.SUFFIX).length)
    }

    @Test
    fun longPlaintextIsChunkedAndReversible() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val pem = buildString {
            appendLine("-----BEGIN PUBLIC KEY-----")
            Base64.getEncoder().encodeToString(keyPair.public.encoded).chunked(64).forEach { appendLine(it) }
            append("-----END PUBLIC KEY-----")
        }

        // 75 字节 → 30 + 30 + 15，三块
        val plaintext = "0123456789".repeat(7) + "ABCDE"
        assertEquals(75, plaintext.toByteArray().size)

        val encrypted = RsaChunk.encrypt(plaintext, pem)
        assertEquals(3, encrypted.split(RsaChunk.SUFFIX).count { it.isNotEmpty() })
        assertEquals(plaintext, decryptBlocks(encrypted, keyPair))
    }

    @Test
    fun acceptsRawBase64WithoutHeaders() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val raw = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        val encrypted = RsaChunk.encrypt("hello", raw)
        assertEquals("hello", decryptBlocks(encrypted, keyPair))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGarbagePem() {
        RsaChunk.encrypt("x", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----")
    }
}
