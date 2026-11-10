package com.baiyunkeys.core.crypto

import com.baiyunkeys.core.hex.Hex
import java.util.Base64
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.crypto.encodings.PKCS1Encoding
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.util.PublicKeyFactory

/**
 * 官方 `get_rsa_public_key` 下发的 PEM 公钥，用它加密手机号。
 *
 * 逐条对照 `BaiyunKeys-iOS/BaiyunKeys/Core/RSA.swift`：
 * - 明文按 **30 字节**分块；
 * - 每块 RSA / PKCS#1 v1.5 加密，密文转 hex；
 * - **每块后面**都追加 `RSA_STRTOK@@@`（不是只加在末尾 —— 服务端靠它分块解密）。
 *
 * iOS 用 Security 框架并手剥 SPKI 头；这里用 BouncyCastle：`PublicKeyFactory` 直接吃
 * SubjectPublicKeyInfo，遇到 PKCS#1 裸公钥再走 `RSAPublicKey` 分支。
 */
object RsaChunk {

    const val SUFFIX = "RSA_STRTOK@@@"
    const val CHUNK_SIZE = 30

    /** 加密：`hex(块1)+SUFFIX+hex(块2)+SUFFIX+…`。 */
    fun encrypt(plaintext: String, pem: String): String {
        val key = importPublicKey(pem)
        val cipher = PKCS1Encoding(RSAEngine()).apply { init(true, key) }

        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val end = minOf(index + CHUNK_SIZE, bytes.size)
            val encrypted = cipher.processBlock(bytes, index, end - index)
            out.append(Hex.hexFromBytes(encrypted)).append(SUFFIX)
            index = end
        }
        return out.toString()
    }

    /** 解析 PEM 公钥，兼容 SPKI（`BEGIN PUBLIC KEY`）与 PKCS#1（`BEGIN RSA PUBLIC KEY`）。 */
    fun importPublicKey(pem: String): RSAKeyParameters {
        val der = decodePem(pem)
        return try {
            PublicKeyFactory.createKey(der) as RSAKeyParameters
        } catch (_: Exception) {
            // PKCS#1：SEQUENCE { INTEGER modulus, INTEGER publicExponent }
            val key = RSAPublicKey.getInstance(der)
            RSAKeyParameters(false, key.modulus, key.publicExponent)
        }
    }

    private fun decodePem(pem: String): ByteArray {
        val base64 = pem
            .replace(HEADER, "")
            .replace(FOOTER, "")
            .replace(WHITESPACE, "")
        require(base64.isNotEmpty()) { "官方公钥格式无法解析" }
        return try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("官方公钥格式无法解析")
        }
    }

    private val HEADER = Regex("-----BEGIN [A-Z ]+-----")
    private val FOOTER = Regex("-----END [A-Z ]+-----")
    private val WHITESPACE = Regex("\\s")
}
