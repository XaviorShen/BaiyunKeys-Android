package com.baiyunkeys.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 登录接口请求体里的设备描述对象（方案 §4.8）。
 *
 * `os` 是操作系统枚举，我们**无法确认**官方映射；iOS 客户端发 `0` 是已验证可用的，
 * 所以 Android 也默认发 `0`，把这个决定封装成常量，调试构建可通过
 * [copy] 切到 [OS_ANDROID] 做对照实验。
 *
 * `appVersion` 与 App 自身的 `MARKETING_VERSION` 无关 —— iOS 代码里就是硬编码 `1.3.6`，
 * 服务端多半按它识别客户端，所以这里也硬编码。
 */
data class DeviceProfile(
    val osVersion: String,
    val brand: String,
    val model: String,
    val udid: String,
    val appVersion: String = APP_VERSION,
    val imsi: String = "46015",
    val wifiMac: String = "02:00:00:00:00:00",
    val os: Int = OS_IOS,
) {

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("osVersion", osVersion)
        put("brand", brand)
        put("model", model)
        put("udid", udid)
        put("appVersion", appVersion)
        put("imsi", imsi)
        put("wifiMac", wifiMac)
        put("os", os)
    }

    companion object {
        /** 与 iOS 客户端完全一致的硬编码版本号。 */
        const val APP_VERSION = "1.3.6"

        /** 推测值：0 = iOS（已验证可用）。 */
        const val OS_IOS = 0

        /** 推测值：1 = Android（未验证，仅调试对照用）。 */
        const val OS_ANDROID = 1

        /** Android 侧默认画像：品牌 / 型号取 Build，os 仍发 0。 */
        fun android(
            osVersion: String,
            model: String,
            udid: String,
            brand: String = "Android",
        ): DeviceProfile = DeviceProfile(
            osVersion = osVersion,
            brand = brand,
            model = model,
            udid = udid,
            os = OS_IOS,
        )
    }
}
