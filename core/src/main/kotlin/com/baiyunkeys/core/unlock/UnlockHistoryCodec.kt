package com.baiyunkeys.core.unlock

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 开锁历史的 JSON 编解码。
 *
 * 与门禁列表一样放在 core 里，是为了**脱离 Android 单测**：app 层只负责把这段 JSON 存到
 * DataStore，序列化兼容性在这里用测试锁住。
 *
 * 列表顺序：**最新的在前**（与 iOS 一致，UI 直接顺序渲染）。
 */
object UnlockHistoryCodec {

    /** 历史最多保留 50 条（对应 `AppStore.historyLimit`）。 */
    const val LIMIT = 50

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val serializer = ListSerializer(UnlockRecord.serializer())

    fun encode(records: List<UnlockRecord>): String =
        json.encodeToString(serializer, records.take(LIMIT))

    fun decode(text: String): List<UnlockRecord> {
        if (text.isBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text).take(LIMIT)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 追加一条：放最前，超出上限就把最老的丢掉。 */
    fun add(existing: List<UnlockRecord>, record: UnlockRecord): List<UnlockRecord> =
        (listOf(record) + existing).take(LIMIT)
}
