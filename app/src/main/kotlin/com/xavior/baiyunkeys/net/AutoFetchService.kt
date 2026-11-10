package com.xavior.baiyunkeys.net

import android.os.Build
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.net.Activation
import com.baiyunkeys.core.net.BaiyunApi
import com.baiyunkeys.core.net.DeviceProfile
import com.baiyunkeys.core.settings.AppSettings
import com.baiyunkeys.core.settings.FetchMethod

/**
 * 「自动获取配置」的编排：把偏好里的通道选择 / 凭据拼成一次调用。
 *
 * 三条通道的分发逻辑照 `ConfigView.fetchConfigs`：
 * - [FetchMethod.IdCard]：账号登录 → 拉列表 → 登出
 * - [FetchMethod.OpenId]：手机号 + OpenID 三步
 * - [FetchMethod.OpenIdSm2]：换 token → 送 SM2 公钥 → 拉列表 → 本地解密
 */
class AutoFetchService(private val api: BaiyunApi) {

    suspend fun fetch(
        settings: AppSettings,
        phone: String,
        idCard: String,
        udid: String,
    ): List<DoorConfig> {
        val profile = profileOf(udid)
        return when (settings.fetchMethod) {
            FetchMethod.IdCard -> Activation.fetchByIdCard(api, phone, idCard, profile)
            FetchMethod.OpenId -> Activation.fetchByOpenId(api, phone, settings.openId)
            FetchMethod.OpenIdSm2 -> Activation.fetchEncrypted(
                api = api,
                phone = phone,
                openId = settings.openId,
                smPublicKey = settings.smPublicKey,
                smPrivateKey = settings.smPrivateKey,
            )
        }
    }

    /** Android 的设备画像。`os` 仍发 0（与 iOS 一致），见方案 §4.8。 */
    fun profileOf(udid: String): DeviceProfile = DeviceProfile.android(
        osVersion = Build.VERSION.RELEASE,
        model = Build.MODEL,
        udid = udid,
        brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
    )
}
