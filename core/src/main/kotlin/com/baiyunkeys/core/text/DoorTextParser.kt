package com.baiyunkeys.core.text

import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig

/**
 * 门禁参数文本解析 / 导入。
 *
 * 逐行对照 `BaiyunKeys-iOS/BaiyunKeys/Features/ConfigView.swift` 里的 `DoorTextParser`
 * （iOS 侧没有独立文件，这段就写在 ConfigView 内）。导出格式：
 *
 * ```
 * 门禁名称：一号楼大门
 * 门禁 MAC：AA:BB:CC:DD:EE:FF
 * 门禁 Key：00112233445566778899AABBCCDDEEFF
 * 蓝牙名称：BY0AABBCCDD
 * ```
 *
 * 空行分条；每条只要 MAC 与 Key 都非空就收下（不校验格式，与 iOS 一致）。
 */
object DoorTextParser {

    fun parse(text: String): List<DoorConfig> {
        val results = mutableListOf<DoorConfig>()
        var current = DoorConfig()
        var hasContent = false

        fun flush() {
            if (hasContent && current.mac.isNotEmpty() && current.key.isNotEmpty()) {
                results += current
            }
            current = DoorConfig()
            hasContent = false
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                flush()
                continue
            }
            val value = valueOf(line) ?: continue
            current = when {
                line.contains("名称") && line.contains("蓝牙") ->
                    current.copy(bluetoothName = value.uppercase())
                line.contains("名称") -> current.copy(name = value)
                line.uppercase().contains("MAC") -> current.copy(mac = Hex.formatMac(value))
                line.uppercase().contains("KEY") -> current.copy(key = Hex.sanitizeKey(value))
                else -> continue
            }
            hasContent = true
        }
        flush()
        return results
    }

    /** 冒号（全角 / 半角）→ 等号 → 空格，取第一个能切出非空值的分隔符右侧。 */
    private fun valueOf(line: String): String? {
        for (separator in SEPARATORS) {
            val index = line.indexOf(separator)
            if (index >= 0) {
                val value = line.substring(index + separator.length).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private val SEPARATORS = listOf("：", ":", "=", " ")
}
