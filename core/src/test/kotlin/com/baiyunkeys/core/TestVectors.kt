package com.baiyunkeys.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * golden vectors 读取器。
 *
 * `tools/vectors/vectors.json` 由 `tools/vectors/gen.mjs` 生成（参考实现 = iOS 仓库的 web 版），
 * 通过 core/build.gradle.kts 里的 `sourceSets.test.resources.srcDir("tools/vectors")` 挂到测试 classpath 根。
 */
object TestVectors {

    val root: JsonObject by lazy {
        val stream = TestVectors::class.java.getResourceAsStream("/vectors.json")
            ?: error("找不到 /vectors.json —— 先跑 `node tools/vectors/gen.mjs`")
        JsonParser.parseString(stream.bufferedReader().readText()).asJsonObject
    }

    fun array(vararg path: String): JsonArray {
        var node: JsonObject = root
        for (i in 0 until path.size - 1) {
            node = node.getAsJsonObject(path[i])
        }
        return node.getAsJsonArray(path.last())
    }

    fun obj(vararg path: String): JsonObject {
        var node: JsonObject = root
        for (p in path) {
            node = node.getAsJsonObject(p)
        }
        return node
    }
}

/** 小工具：把 JsonObject 当轻量 map 用。 */
internal fun JsonObject.str(key: String): String = get(key).asString
internal fun JsonObject.int(key: String): Int = get(key).asInt
internal fun JsonObject.bool(key: String): Boolean = get(key).asBoolean
