package com.baiyunkeys.core.deeplink

/**
 * `baiyunkeys://` 深链要做什么。
 *
 * - [Default]：`baiyunkeys://unlock` —— 开当前选中的门禁（与 iOS 「立即开锁」一致）
 * - [Door]：`baiyunkeys://unlock?door=<id>` —— 开指定门禁（id 只在生成它的那台设备上有效）
 * - [Quick]：`baiyunkeys://unlock?quick=1` —— 快速模式，扫到附近任一门禁就开
 */
sealed interface UnlockCommand {
    data object Default : UnlockCommand
    data object Quick : UnlockCommand
    data class Door(val id: String) : UnlockCommand
}

/**
 * 深链解析（纯字符串处理，不依赖 `android.net.Uri`，所以能单测）。
 *
 * 对应 iOS 的 URL scheme：`baiyunkeys://unlock`、`baiyunkeys://unlock?door=<id>`。
 * Android 这边多一个 `quick=1`，给静态快捷方式「快速模式」用。
 */
object UnlockLink {

    const val SCHEME = "baiyunkeys"
    const val HOST = "unlock"
    private const val PREFIX = "$SCHEME://$HOST"

    /** 生成「开这条门禁」的深链，对应 iOS 「复制直达链接」。 */
    fun build(doorId: String): String = "$PREFIX?door=$doorId"

    /** 生成快速模式深链。 */
    fun buildQuick(): String = "$PREFIX?quick=1"

    /** 解析；不是本 App 的链接就返回 null（交给系统或忽略）。 */
    fun parse(raw: String?): UnlockCommand? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        if (!url.startsWith("$SCHEME://", ignoreCase = true)) return null

        val rest = url.substring(SCHEME.length + 3)
        val pathPart = rest.substringBefore('?')
        // host 允许大小写差异，也允许末尾多余的斜杠
        if (pathPart.trimEnd('/').lowercase() != HOST) return null

        val query = if ('?' in rest) rest.substringAfter('?').substringBefore('#') else ""
        val params = parseQuery(query)

        if (!params["quick"].isNullOrBlank()) return UnlockCommand.Quick
        val door = params["door"]?.takeIf { it.isNotBlank() }
        if (door != null) return UnlockCommand.Door(door)
        return UnlockCommand.Default
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val key = pair.substringBefore('=').trim()
                val value = if ('=' in pair) pair.substringAfter('=').trim() else ""
                if (key.isEmpty()) null else key.lowercase() to value
            }
            .toMap()
    }
}
