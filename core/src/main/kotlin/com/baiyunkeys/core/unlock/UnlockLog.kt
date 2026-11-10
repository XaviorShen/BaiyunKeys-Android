package com.baiyunkeys.core.unlock

import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel(val tag: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    DEBUG("DEBUG"),
}

/** 一行运行日志。环形缓冲最多保留 200 行（见 [MAX_LINES]）。 */
data class UnlockLog(
    val time: String,
    val level: LogLevel,
    val text: String,
) {
    /** `HH:mm:ss.SSS [LEVEL] text` —— 与 iOS 的日志格式逐字一致，方便对拍。 */
    fun format(): String = "$time [${level.tag}] $text"

    companion object {
        const val MAX_LINES = 200

        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun now(clock: Clock, zone: ZoneId, level: LogLevel, text: String): UnlockLog =
            UnlockLog(
                time = TIME_FORMAT.format(clock.instant().atZone(zone)),
                level = level,
                text = text,
            )
    }
}
