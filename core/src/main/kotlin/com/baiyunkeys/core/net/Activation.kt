package com.baiyunkeys.core.net

import com.baiyunkeys.core.crypto.Sm2Gm
import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig

/**
 * 「手机号 + OpenID + SM2 加密版」完整流程。
 *
 * 对照 `BaiyunKeys-iOS/BaiyunKeys/Core/Activation.swift`：
 * 换 token → 送公钥握手 → 拉门禁列表 → 本地按需 SM2 解。
 * 服务器按 payload 是否带 `xx` 字段决定返明文还是密文，**两种都要兼容**。
 *
 * iOS 用 `Sm2DecryptHook` 注入解密函数；Kotlin 直接调 [Sm2Gm]，不需要钩子。
 */
object Activation {

    /** 服务器返回的 productKey 是明文（可直接用）还是密文（需要 SM2 解）。 */
    fun isPlainKey(hex: String): Boolean {
        val clean = Hex.sanitizeKey(hex)
        return clean.length in 16..32 && clean.length % 2 == 0
    }

    /** 通道二：手机号 + OpenID。 */
    suspend fun fetchByOpenId(api: BaiyunApi, phone: String, openId: String): List<DoorConfig> {
        val guards = api.guardsByOpenId(phone, openId)
        if (guards.isEmpty()) throw BaiyunException("获取失败，服务器未返回任何可用门禁")
        return guards.map { it.asDoorConfig() }
    }

    /** 通道一：手机号 + 身份证（老接口，官方端已停用）。 */
    suspend fun fetchByIdCard(
        api: BaiyunApi,
        phone: String,
        idCard: String,
        profile: DeviceProfile,
    ): List<DoorConfig> {
        val auth = api.login(phone, idCard, profile)
        val guards = try {
            api.entranceGuards(auth)
        } finally {
            api.logout(auth)
        }
        if (guards.isEmpty()) throw BaiyunException("获取失败，服务器未返回任何可用门禁")
        return guards.map { it.asDoorConfig() }
    }

    /** 通道三：手机号 + OpenID + SM2。 */
    suspend fun fetchEncrypted(
        api: BaiyunApi,
        phone: String,
        openId: String,
        smPublicKey: String,
        smPrivateKey: String,
    ): List<DoorConfig> {
        val pub = smPublicKey.trim()
        val priv = Hex.sanitizeKey(smPrivateKey.trim()).lowercase()
        require(pub.length == 130 && pub.startsWith("04", ignoreCase = true)) {
            "SM2 公钥格式不对（应为 130 字符 hex，04 开头）"
        }
        require(priv.length == 64) { "SM2 私钥格式不对（应为 64 字符 hex）" }

        val accessToken = api.checkState(openId)
        api.goHomeServiceLogin(
            openId = openId,
            loginToken = "",
            smPublicKey = pub,
            accessToken = accessToken,
        )
        val encrypted = api.encryptedGuards(phone, accessToken)

        val results = mutableListOf<DoorConfig>()
        var encryptedCount = 0

        for (item in encrypted) {
            if (isPlainKey(item.encryptedKey)) {
                // 明文：直接当门禁 key 用，SM2 用不上
                results += DoorConfig(
                    name = item.name,
                    mac = Hex.formatMac(item.mac),
                    key = Hex.sanitizeKey(item.encryptedKey).uppercase(),
                    bluetoothName = item.bluetoothName.uppercase(),
                )
                continue
            }

            encryptedCount++
            val plaintext = try {
                Sm2Gm.decrypt(item.encryptedKey, priv)
            } catch (_: Exception) {
                continue
            }
            val key = Hex.sanitizeKey(plaintext).uppercase()
            if (key.isEmpty()) continue

            results += DoorConfig(
                name = item.name,
                mac = Hex.formatMac(item.mac),
                key = key,
                bluetoothName = item.bluetoothName.uppercase(),
            )
        }

        if (encrypted.isNotEmpty() && results.isEmpty()) {
            throw BaiyunException(
                if (encryptedCount > 0) "全部密文解密失败，公/私钥可能对不上" else "获取失败，服务器未返回任何可用门禁",
            )
        }
        return results
    }
}
