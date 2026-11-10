package com.baiyunkeys.core.net

import com.baiyunkeys.core.crypto.RsaChunk
import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.protocol.BleProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 接口层错误。文案尽量与 iOS `BaiyunAPI.Failure` 一致，方便两端对照。 */
class BaiyunException(message: String) : Exception(message)

/**
 * 「自动获取配置」的三条通道，逐行对照 `BaiyunKeys-iOS/BaiyunKeys/Core/BaiyunAPI.swift`。
 *
 * | 通道 | 入口 | 说明 |
 * |---|---|---|
 * | 手机号 + 身份证 | [login] + [entranceGuards] | 老接口，官方端已停用，保留 |
 * | 手机号 + OpenID | [guardsByOpenId] | 官方 RSA 公钥加密手机号，三步 |
 * | 手机号 + OpenID + SM2 | [checkState] + [goHomeServiceLogin] + [encryptedGuards] | 加密下发 |
 *
 * 传输层用 [HttpTransport] 注入：生产是 OkHttp，测试是假的，所以整个协议可以离线断言。
 */
class BaiyunApi(
    private val http: HttpTransport,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val baseXcx: String = DEFAULT_BASE_XCX,
) {

    data class Auth(val token: String, val loginUser: String, val phone: String)

    /** 明文门禁条目。 */
    data class Entrance(
        val name: String,
        val mac: String,
        val key: String,
        val bluetoothName: String,
    ) {
        fun asDoorConfig(): DoorConfig = DoorConfig(
            name = name,
            mac = Hex.formatMac(mac),
            key = Hex.sanitizeKey(key),
            bluetoothName = bluetoothName.uppercase(),
        )
    }

    data class GoHomeLoginResult(
        val newLoginToken: String,
        val phone: String,
        val name: String,
        val idCard: String,
    )

    /** 加密版条目：[encryptedKey] 需要用对应 SM2 私钥解完才是明文。 */
    data class EncryptedEntrance(
        val name: String,
        val mac: String,
        val bluetoothName: String,
        val encryptedKey: String,
    )

    // MARK: - 老接口：手机号 + 身份证

    suspend fun login(phone: String, idCard: String, profile: DeviceProfile): Auth {
        val normalizedPhone = phone.trim()
        val payload = buildJsonObject {
            put("sex", 0)
            put("idcardNo", idCard.trim().uppercase())
            put("deviceInfo", profile.toJsonObject())
            put("faceUploadCount", 0)
            put("isreal", 0)
            put("age", 0)
            put("appVersion", DeviceProfile.APP_VERSION)
            put("phone", normalizedPhone)
        }

        val response = requestObject("$baseUrl/baiyunuser/account/login/v1", payload)
        val state = (response["state"] as? JsonPrimitive)?.booleanOrNull == true
        if (!state || response.stringOrEmpty("code") != "0000") {
            throw BaiyunException(response.stringOrEmpty("msg").ifEmpty { "登录失败" })
        }

        val token = response.stringOrEmpty("extension")
        val loginUser = (response["obj"] as? JsonObject)?.let { it.stringOrEmpty("id") }.orEmpty()
        if (token.isEmpty() || loginUser.isEmpty()) {
            throw BaiyunException("登录返回数据缺失，请稍后重试")
        }
        return Auth(token = token, loginUser = loginUser, phone = normalizedPhone)
    }

    suspend fun entranceGuards(auth: Auth): List<Entrance> {
        val response = requestObject(
            url = "$baseUrl/baiyunuser/entranceguard/getList",
            body = buildJsonObject {
                put("pageNum", 0)
                put("pages", 0)
                put("pageSize", 0)
            },
            headers = mapOf("TOKEN" to auth.token, "LOGIN_USER" to auth.loginUser),
        )

        val collected = mutableListOf<JsonObject>()
        collect(response, collected)
        if (collected.isEmpty()) {
            (response["obj"] as? JsonArray)?.forEach { item ->
                (item as? JsonObject)?.let { collected += it }
            }
        }
        return collected.mapNotNull(::mapGuard)
    }

    suspend fun logout(auth: Auth) {
        runCatching {
            requestObject(
                url = "$baseUrl/baiyunuser/account/loginOut",
                body = buildJsonObject { put("phone", auth.phone) },
                headers = mapOf("TOKEN" to auth.token, "LOGIN_USER" to auth.loginUser),
            )
        }
    }

    // MARK: - 新接口：OpenID

    /** ① OpenID 换 `cloud_shield_token`。 */
    suspend fun checkState(openId: String): String {
        val resp = requestArray(
            url = "$baseXcx/mini_program/api_01/check_state",
            body = buildJsonObject {
                put("openId", openId.trim())
                put("oper_type", "INDEX")
            },
        )
        val token = resp.firstOrNull()?.stringOrEmpty("access_token").orEmpty()
        if (token.isEmpty()) {
            throw BaiyunException(
                resp.firstOrNull()?.stringOrEmpty("resp_msg")
                    .orEmpty()
                    .ifEmpty { "OpenID 换取令牌失败，请确认 OpenID 正确且未过期" },
            )
        }
        return token
    }

    /** 手机号 + OpenID：三步走完直接拿到明文门禁列表。 */
    suspend fun guardsByOpenId(phone: String, openId: String): List<Entrance> {
        val accessToken = checkState(openId)
        val pem = rsaPublicKey(accessToken)
        val list = requestGuards(phone, accessToken, pem, ::mapGuard)
        return list
    }

    /** 加密版：把本地 SM2 公钥告诉服务器，后续 productKey 会被加密下发。 */
    suspend fun goHomeServiceLogin(
        openId: String,
        loginToken: String,
        smPublicKey: String,
        accessToken: String,
    ): GoHomeLoginResult {
        val resp = requestArray(
            url = "$baseXcx/p_021_health_passport/api_007_wbyw_002/go_home_service_login",
            body = buildJsonObject {
                put("openId", openId.trim())
                put("login_token", loginToken)
                put("sm_publickey", smPublicKey.trim())
            },
            headers = shieldHeaders(accessToken),
        )
        val first = resp.firstOrNull() ?: throw BaiyunException("激活握手失败")
        val code = first.stringOrEmpty("resp_code")
        if (code.isNotEmpty() && code != SUCCESS_CODE) {
            throw BaiyunException(first.stringOrEmpty("resp_msg").ifEmpty { "激活握手失败" })
        }
        return GoHomeLoginResult(
            newLoginToken = first.stringOrEmpty("token"),
            phone = first.stringOrEmpty("phone"),
            name = first.stringOrEmpty("name"),
            idCard = first.stringOrEmpty("id_card"),
        )
    }

    /** 拉门禁列表，返回**不解密**的条目（保留 112 字节密文）。 */
    suspend fun encryptedGuards(phone: String, accessToken: String): List<EncryptedEntrance> {
        val pem = rsaPublicKey(accessToken)
        return requestGuards(phone, accessToken, pem, ::mapEncryptedGuard)
    }

    // MARK: - 内部：请求

    private suspend fun <T> requestGuards(
        phone: String,
        accessToken: String,
        pem: String,
        mapper: (JsonObject) -> T?,
    ): List<T> {
        val plaintext = """{"phone":"${phone.trim()}"}"""
        val encryptedKey = RsaChunk.encrypt(plaintext, pem)
        val listResp = requestArray(
            url = "$baseXcx/p_021_health_passport/api_007_wbyw_002/get_guard_list_by_phone",
            body = buildJsonObject { put("key", encryptedKey) },
            headers = shieldHeaders(accessToken),
        )
        val first = listResp.firstOrNull() ?: throw BaiyunException("获取门禁列表失败")
        val code = first.stringOrEmpty("resp_code")
        if (code.isNotEmpty() && code != SUCCESS_CODE) {
            throw BaiyunException(first.stringOrEmpty("resp_msg").ifEmpty { "获取门禁列表失败" })
        }
        val dataList = first["data_list"] as? JsonArray ?: JsonArray(emptyList())
        return dataList.mapNotNull { item -> (item as? JsonObject)?.let(mapper) }
    }

    private suspend fun rsaPublicKey(accessToken: String): String {
        val resp = requestArray(
            url = "$baseXcx/health_passport/api_001_health_passport/get_rsa_public_key",
            method = "GET",
            headers = shieldHeaders(accessToken),
        )
        val pem = resp.firstOrNull()?.stringOrEmpty("result").orEmpty()
        if (pem.isEmpty()) {
            throw BaiyunException(
                resp.firstOrNull()?.stringOrEmpty("resp_msg").orEmpty().ifEmpty { "获取 RSA 公钥失败" },
            )
        }
        return pem
    }

    private suspend fun requestObject(
        url: String,
        body: JsonObject? = null,
        method: String = "POST",
        headers: Map<String, String> = emptyMap(),
    ): JsonObject {
        val element = requestElement(url, body, method, headers)
        return element as? JsonObject ?: throw BaiyunException("返回数据解析失败")
    }

    /** iOS 的 `requestArray`：根节点是数组就用数组，是单对象就包成一条。 */
    private suspend fun requestArray(
        url: String,
        body: JsonObject? = null,
        method: String = "POST",
        headers: Map<String, String> = emptyMap(),
    ): List<JsonObject> {
        return when (val element = requestElement(url, body, method, headers)) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> throw BaiyunException("返回数据解析失败")
        }
    }

    private suspend fun requestElement(
        url: String,
        body: JsonObject?,
        method: String,
        headers: Map<String, String>,
    ): JsonElement {
        val response = http.execute(
            HttpRequest(
                method = method,
                url = url,
                body = if (method == "GET") null else body?.toString(),
                headers = headers,
            ),
        )
        if (response.code !in 200..299) throw BaiyunException("请求失败：HTTP ${response.code}")
        if (response.body.isEmpty()) throw BaiyunException("返回数据为空")
        return try {
            Json.parseToJsonElement(response.body)
        } catch (_: Exception) {
            throw BaiyunException("返回数据解析失败")
        }
    }

    // MARK: - 内部：字段映射

    /**
     * 两条通道下发字段一致（对应 iOS `mapGuards`）：
     * 名称 address / name / doorName 三选一；MAC 取 macNum / mac，缺失时由蓝牙名反推；
     * Key 取 productKey / key；蓝牙名转大写；MAC 或 Key 为空的条目丢掉。
     */
    private fun mapGuard(item: JsonObject): Entrance? {
        val bluetoothName = item.stringOrEmpty("bluetoothName").uppercase()
        val rawMac = item.stringOrEmpty("macNum").ifEmpty { item.stringOrEmpty("mac") }
        var mac = Hex.formatMac(rawMac)
        if (!Hex.isValidMac(mac) && bluetoothName.isNotEmpty()) {
            BleProtocol.macFromBluetoothName(bluetoothName)?.let { mac = it }
        }
        val key = Hex.sanitizeKey(
            item.stringOrEmpty("productKey").ifEmpty { item.stringOrEmpty("key") },
        )
        if (mac.isEmpty() || key.isEmpty()) return null

        return Entrance(
            name = pickName(item),
            mac = mac,
            key = key,
            bluetoothName = bluetoothName,
        )
    }

    /** 加密版不做 isValidKey 过滤，保留 112 字节密文。 */
    private fun mapEncryptedGuard(item: JsonObject): EncryptedEntrance? {
        val bluetoothName = item.stringOrEmpty("bluetoothName").uppercase()
        val rawMac = item.stringOrEmpty("macNum").ifEmpty { item.stringOrEmpty("mac") }
        var mac = Hex.formatMac(rawMac)
        if (!Hex.isValidMac(mac) && bluetoothName.isNotEmpty()) {
            BleProtocol.macFromBluetoothName(bluetoothName)?.let { mac = it }
        }
        val encrypted = item.stringOrEmpty("productKey").ifEmpty { item.stringOrEmpty("key") }
        if (mac.isEmpty() || encrypted.isEmpty()) return null

        return EncryptedEntrance(
            name = pickName(item),
            mac = mac,
            bluetoothName = bluetoothName,
            encryptedKey = encrypted,
        )
    }

    private fun pickName(item: JsonObject): String =
        listOf("address", "name", "doorName")
            .map { item.stringOrEmpty(it) }
            .firstOrNull { it.isNotEmpty() }
            ?: "导入门禁"

    /**
     * 接口把门禁数组藏在嵌套的 `obj.data_list` 里（对应 iOS `collect`），递归收集。
     */
    private fun collect(node: JsonElement?, out: MutableList<JsonObject>) {
        when (node) {
            is JsonArray -> node.forEach { collect(it, out) }
            is JsonObject -> {
                (node["data_list"] as? JsonArray)?.forEach { item ->
                    (item as? JsonObject)?.let { out += it }
                }
                node["obj"]?.let { collect(it, out) }
            }

            else -> Unit
        }
    }

    private fun shieldHeaders(accessToken: String): Map<String, String> =
        mapOf("cloud_shield_token" to accessToken)

    private fun JsonObject.stringOrEmpty(key: String): String {
        val element = this[key] ?: return ""
        if (element is JsonNull) return ""
        return (element as? JsonPrimitive)?.content?.trim().orEmpty()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.pinganbaiyun.cn"
        const val DEFAULT_BASE_XCX = "https://xcx.pinganbaiyun.cn"
        const val SUCCESS_CODE = "000000000000"

        @Suppress("unused")
        private fun emptyArray(): JsonArray = buildJsonArray { }
    }
}
