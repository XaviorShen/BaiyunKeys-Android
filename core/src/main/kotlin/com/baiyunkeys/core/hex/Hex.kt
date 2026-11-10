package com.baiyunkeys.core.hex

/**
 * 十六进制 / MAC 地址工具。
 *
 * 逐函数对照 `BaiyunKeys-iOS/BaiyunKeys/Core/Hex.swift` 与 `web/js/core/hex.js` 移植，
 * 三条实现必须给出完全相同的输出（见 tools/vectors/vectors.json）。
 */
object Hex {

    /** 只保留十六进制字符并转大写。 */
    private fun onlyHex(value: String): String =
        value.uppercase().filter { it.isHex() }

    /** 去掉非法字符并补齐奇数长度（**前补 0**，不是后补）。 */
    fun normalize(hex: String): String {
        val clean = onlyHex(hex)
        return if (clean.length % 2 == 0) clean else "0$clean"
    }

    fun bytesFromHex(hex: String): ByteArray {
        val clean = normalize(hex)
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun hexFromBytes(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_DIGITS[v ushr 4])
            append(HEX_DIGITS[v and 0x0F])
        }
    }

    /**
     * 逐 Unicode 码点取低 8 位转 hex。
     *
     * 注意：按**码点**而非 UTF-16 code unit 迭代，与 Swift 的 `unicodeScalars`
     * 和 JS 的 `for...of` 一致（非 BMP 字符只产生一个字节）。
     */
    fun ascii(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val v = cp and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** 按字节倒序，用于匹配部分设备反序广播的 MAC。奇数长度时保留首个半字节（与 web 实现一致）。 */
    fun reversedPairs(hex: String): String {
        val sb = StringBuilder(hex.length)
        var end = hex.length
        while (end > 0) {
            val start = maxOf(0, end - 2)
            sb.append(hex, start, end)
            end -= 2
        }
        return sb.toString()
    }

    /**
     * 按十六进制字符位取子串，等价于 JS 的 `substr`。
     * 起始位越界返回空串；长度会自动截到字符串末尾。
     */
    fun hexSlice(str: String, start: Int, length: Int): String {
        if (start < 0 || start >= str.length) return ""
        val end = start + minOf(length, str.length - start)
        return str.substring(start, end)
    }

    fun sanitizeMacInput(value: String): String =
        value.uppercase().filter { it.isHex() || it == ':' }

    fun isValidMac(value: String): Boolean {
        val parts = value.trim().uppercase().split(":")
        if (parts.size != 6) return false
        return parts.all { it.length == 2 && it[0].isHex() && it[1].isHex() }
    }

    fun sanitizeKey(value: String): String = onlyHex(value)

    /** 门禁 Key：16 ~ 32 位 hex，且长度为偶数。 */
    fun isValidKey(value: String): Boolean {
        val s = sanitizeKey(value)
        return s.length in 16..32 && s.length % 2 == 0
    }

    /** 自动为纯十六进制串补上冒号；不是 12 位 hex 时退回 [sanitizeMacInput]。 */
    fun formatMac(value: String): String {
        val compact = onlyHex(value)
        if (compact.length != 12) return sanitizeMacInput(value)
        return (0 until 12 step 2).joinToString(":") { compact.substring(it, it + 2) }
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'A'..'F'
}
