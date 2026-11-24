package com.baiyunkeys.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 一次解码的结果：读出的状态、一共几条、几条没读出来。 */
data class DecodeReport(val state: DoorState, val total: Int, val failed: Int)

/**
 * 门禁列表的 JSON 编解码。
 *
 * 放在 core 里是为了**能脱离 Android 单测**：app 层只负责把这段 JSON 用 Keystore 加密后落盘
 * （见 `app/storage/DoorStore.kt`），编解码本身的兼容性在这里用测试锁住。
 *
 * 兼容策略：未知字段忽略（将来加字段不会让老数据解不出来）、缺字段走默认值、
 * JSON 里的 null 按没写处理。**坏一条只丢一条**：某条门禁解不出来时不牵连其余条目，
 * 调用方通过 [decodeWithReport] 得知丢了几条。只有整份文档都不是 JSON 时才返回空列表。
 */
object DoorCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        explicitNulls = false
        // null 和不认识的枚举值按「没写」处理，走默认值
        coerceInputValues = true
    }

    fun encode(state: DoorState): String = json.encodeToString(DoorState.serializer(), state)

    fun decode(text: String): DoorState = decodeWithReport(text).state

    fun decodeWithReport(text: String): DecodeReport {
        val empty = DoorState()
        if (text.isBlank()) return DecodeReport(empty, total = 0, failed = 0)

        // 整份文档不是 JSON 对象，或者 items 不是数组：一条都读不出来
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return DecodeReport(empty, total = 0, failed = 1)
        val itemsElement = root["items"]
        if (itemsElement == null || itemsElement is JsonNull) return DecodeReport(empty, total = 0, failed = 0)
        val rawItems = itemsElement as? JsonArray
            ?: return DecodeReport(empty, total = 0, failed = 1)

        val items = ArrayList<DoorConfig>(rawItems.size)
        var failed = 0
        for (element in rawItems) {
            val item = runCatching { json.decodeFromJsonElement(DoorConfig.serializer(), element) }.getOrNull()
            if (item == null) failed++ else items += item
        }

        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 2
        val requestedId = (root["currentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val currentId = when {
            requestedId == null -> null
            items.any { it.id == requestedId } -> requestedId
            // 选中的那条被丢掉了：回落到第一条，免得列表里有门禁却没有选中项
            else -> items.firstOrNull()?.id
        }

        return DecodeReport(DoorState(version = version, currentId = currentId, items = items), total = rawItems.size, failed = failed)
    }
}
