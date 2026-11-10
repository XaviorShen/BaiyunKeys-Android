package com.xavior.baiyunkeys.security

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 生物识别锁屏（对应 iOS `BiometricGate` / `RootView` 的冷启动解锁）。
 *
 * 语义：**能用指纹/面容就用，没有就退回设备锁屏密码**，两者都没有才算不可用。
 *
 * ## 跨版本（`androidx.biometric` 的平台差异）
 * - **API 30+（Android 11 起）**：系统原生支持 `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` 组合，
 *   弹窗里能直接切「用密码」；
 * - **API 28/29（Android 9/10，也是本项目的地板）**：组合常量不被支持，必须用
 *   `setDeviceCredentialAllowed(true)` 表达同样的意思。注意这个旧 API **不区分强度**——
 *   库会按 `BIOMETRIC_WEAK` 接受，所以老系统上 2D 人脸也能用，新系统只认强生物识别。
 *   这是平台限制，不是可以靠代码抹平的差异，已在 README 记录。
 *
 * ## 跨品牌
 * 各家的指纹/人脸都通过同一套 `BiometricPrompt` 暴露，**不需要厂商适配代码**：
 * 小米、华为、OPPO/vivo、三星、Pixel 都走系统弹窗。差别只在「强度等级」——
 * 屏下/实体指纹与 3D 人脸（Pixel 4、华为 Mate 20 Pro 等）是 Class 3（`BIOMETRIC_STRONG`），
 * 绝大多数国产机的 2D 人脸是 Class 2（`BIOMETRIC_WEAK`）：在 Android 11+ 上不会被
 * `BIOMETRIC_STRONG` 接受，这类用户会退到指纹或设备密码。
 * 这是**有意为之**：门禁 Key 等同实体钥匙，2D 人脸能被照片骗过。
 *
 * 可用性判断也分开做：老版本 `canAuthenticate(DEVICE_CREDENTIAL)` 不可靠，
 * 设备凭据这一半用 `KeyguardManager.isDeviceSecure` 判断才是准的。
 */
class BiometricGate(private val activity: FragmentActivity) {

    /** 当前设备能否完成验证（生物识别 或 设备凭据，任一可用即可）。 */
    fun isAvailable(): Boolean {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        val deviceSecure = keyguard?.isDeviceSecure == true

        val manager = BiometricManager.from(activity)
        val biometricOk = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        ) == BiometricManager.BIOMETRIC_SUCCESS

        return biometricOk || deviceSecure
    }

    /**
     * 弹系统验证框。
     *
     * @param onResult true = 通过；false 时 message 给界面用的失败文案
     */
    fun authenticate(onResult: (success: Boolean, message: String?) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true, null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false, messageFor(errorCode, errString.toString()))
                }

                // 单次指纹不匹配不算结束，系统会继续让用户试
                override fun onAuthenticationFailed() = Unit
            },
        )

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁白云钥匙")
            .setSubtitle("验证后可访问本机门禁凭据")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(ALLOWED)
        } else {
            // Android 7–10：组合常量不生效，改用「允许设备凭据」的旧语义
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }

        prompt.authenticate(builder.build())
    }

    /** 错误码 → 人话（文案对齐 iOS 的失败映射）。 */
    fun messageFor(errorCode: Int, fallback: String): String = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> "已取消验证"

        BiometricPrompt.ERROR_LOCKOUT -> "尝试次数过多，请稍后再试"
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "生物识别已被锁定，请用设备密码解锁"
        BiometricPrompt.ERROR_NO_BIOMETRICS -> "本机未录入指纹或面容"
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        -> "当前设备不支持生物识别"

        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> "请先在系统设置里设置锁屏密码"

        else -> fallback
    }

    private companion object {
        const val ALLOWED =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
