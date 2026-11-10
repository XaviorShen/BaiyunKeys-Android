package com.baiyunkeys.core.crypto

import com.baiyunkeys.core.hex.Hex
import org.bouncycastle.crypto.engines.DESEngine
import org.bouncycastle.crypto.params.KeyParameter

/**
 * DES / ECB / NoPadding。
 *
 * 对照 `BaiyunKeys-iOS/BaiyunKeys/Core/DES.swift`：密钥只取前 16 个 hex 字符（8 字节）。
 *
 * ⚠️ 与 Swift 版有一处**有意的**差异：Swift 的 `DES.crypt` 要求输入恰好 16 个 hex 字符
 * （单块），而 `BLEProtocol.commKey` 却会传进 `hexSlice(24, 32)` —— 32 个 hex 字符（两块）。
 * 单块检查会抛错、被 `try?` 吞掉，导致 iOS 上通讯密钥协商恒失败。web 版 `des.js` 是多块循环，
 * 方案文档 §4.1 第 8 条也按多块描述，所以这里按**多块**实现：单块输入行为与 Swift 完全一致，
 * 多块输入则按 8 字节分组循环加解密。
 */
object DesBlock {

    /** 加密，返回与输入等长的 hex 串。 */
    fun encryptBlock(keyHex: String, blockHex: String): String = crypt(true, keyHex, blockHex)

    /** 解密，返回与输入等长的 hex 串。 */
    fun decryptBlock(keyHex: String, blockHex: String): String = crypt(false, keyHex, blockHex)

    private fun crypt(encrypt: Boolean, keyHex: String, blockHex: String): String {
        val key = normalizedKey(keyHex)
        val clean = Hex.sanitizeKey(blockHex)
        require(clean.isNotEmpty() && clean.length % 16 == 0) {
            "DES 数据必须是 8 字节的整数倍（16 个十六进制字符一组）"
        }

        val engine = DESEngine()
        engine.init(encrypt, KeyParameter(key))

        val input = Hex.bytesFromHex(clean)
        val out = ByteArray(input.size)
        var offset = 0
        while (offset < input.size) {
            engine.processBlock(input, offset, out, offset)
            offset += 8
        }
        return Hex.hexFromBytes(out)
    }

    private fun normalizedKey(keyHex: String): ByteArray {
        val clean = Hex.sanitizeKey(keyHex)
        require(clean.isNotEmpty()) { "缺少 DES 密钥" }
        require(clean.length >= 16) { "密钥长度不足 8 字节" }
        return Hex.bytesFromHex(clean.substring(0, 16))
    }
}
