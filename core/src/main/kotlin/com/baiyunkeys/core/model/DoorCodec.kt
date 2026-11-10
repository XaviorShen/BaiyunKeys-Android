package com.baiyunkeys.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 门禁列表的 JSON 编解码。
 *
 * 放在 core 里是为了**能脱离 Android 单测**：app 层只负责把这段 JSON 用 Keystore 加密后落盘
 * （见 `app/storage/DoorStore.kt`），编解码本身的兼容性在这里用测试锁住。
 *
 * 兼容策略：未知字段忽略（将来加字段不会让老数据解不出来）、缺字段走默认值、
 * 解不出来时返回空列表而不是让整个 App 起不来。
 */
object DoorCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        explicitNulls = false
    }

    fun encode(state: DoorState): String = json.encodeToString(DoorState.serializer(), state)

    fun decode(text: String): DoorState {
        if (text.isBlank()) return DoorState()
        return try {
            json.decodeFromString(DoorState.serializer(), text)
        } catch (_: SerializationException) {
            DoorState()
        } catch (_: IllegalArgumentException) {
            DoorState()
        }
    }
}
