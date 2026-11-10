package com.baiyunkeys.core.crypto

import com.baiyunkeys.core.hex.Hex
import org.bouncycastle.crypto.engines.SM2Engine
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.asn1.gm.GMNamedCurves
import java.math.BigInteger
import java.security.SecureRandom

/**
 * 国密 SM2（C1C3C2）。
 *
 * iOS 侧 `SM2.swift` 是手写大数实现（583 行），这里全部交给 BouncyCastle：
 * - 加密模式固定 `Mode.C1C3C2`（与服务端一致）；
 * - 密文**带不带 `04` 前缀都要能吃**：服务器下发的 112 字节 hex 有的带、有的不带，
 *   iOS 的做法是两种都试一遍，这里同样保留。
 *
 * 私钥 64 位 hex、公钥 130 位 hex（`04` + X + Y）。
 */
object Sm2Gm {

    private val curve = GMNamedCurves.getByName("sm2p256v1")
    val domain: ECDomainParameters = ECDomainParameters(
        curve.curve,
        curve.g,
        curve.n,
        curve.h,
    )

    private val random = SecureRandom()

    /** 由私钥推导公钥（130 位 hex，`04` 开头）。 */
    fun publicKeyFromPrivate(privateKeyHex: String): String {
        val d = parsePrivateKey(privateKeyHex)
        val point = domain.g.multiply(d).normalize()
        return Hex.hexFromBytes(point.getEncoded(false))
    }

    /** 加密，返回大写 hex（C1C3C2，带 `04` 前缀）。主要给测试与自检用。 */
    fun encrypt(plaintext: ByteArray, publicKeyHex: String): String {
        val point = curve.curve.decodePoint(Hex.bytesFromHex(normalizePublicKey(publicKeyHex)))
        val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
        engine.init(true, ParametersWithRandom(ECPublicKeyParameters(point, domain), random))
        return Hex.hexFromBytes(engine.processBlock(plaintext, 0, plaintext.size))
    }

    /**
     * 解密，返回**明文 hex**（服务端加密的是 hex 字符串本身）。
     *
     * 先按原样解；失败且密文不以 `04` 开头时，补上 `04` 再试一次。
     */
    fun decrypt(cipherHex: String, privateKeyHex: String): String {
        val d = parsePrivateKey(privateKeyHex)
        val key = ECPrivateKeyParameters(d, domain)
        val clean = Hex.sanitizeKey(cipherHex)
        require(clean.isNotEmpty()) { "密文为空" }

        val attempts = buildList {
            add(clean)
            if (!clean.startsWith("04") && clean.length >= 128) add("04$clean")
        }

        var lastError: Exception? = null
        for (candidate in attempts) {
            try {
                val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
                engine.init(false, key)
                val cipher = Hex.bytesFromHex(candidate)
                val plain = engine.processBlock(cipher, 0, cipher.size)
                return Hex.hexFromBytes(plain)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalArgumentException("SM2 解密失败：${lastError?.message ?: "未知错误"}")
    }

    private fun normalizePublicKey(publicKeyHex: String): String {
        val clean = Hex.sanitizeKey(publicKeyHex)
        return if (clean.startsWith("04")) clean else "04$clean"
    }

    private fun parsePrivateKey(privateKeyHex: String): BigInteger {
        val clean = Hex.sanitizeKey(privateKeyHex)
        require(clean.length == 64) { "SM2 私钥格式不对（应为 64 字符 hex）" }
        val d = BigInteger(clean, 16)
        require(d.signum() > 0 && d < domain.n) { "SM2 私钥超出曲线范围" }
        return d
    }

    /** 生成一对密钥（hex），调试与测试用。 */
    fun generateKeyPair(): Pair<String, String> {
        val d = BigInteger(domain.n.bitLength(), random)
            .mod(domain.n.subtract(BigInteger.ONE))
            .add(BigInteger.ONE)

        // 私钥固定编码成 32 字节（`toByteArray` 可能带一个前导 0 变成 33 字节）
        val raw = d.toByteArray()
        val fixed = ByteArray(32)
        val source = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        source.copyInto(fixed, 32 - source.size)

        val publicKey = Hex.hexFromBytes(domain.g.multiply(d).normalize().getEncoded(false))
        return Hex.hexFromBytes(fixed) to publicKey
    }
}
