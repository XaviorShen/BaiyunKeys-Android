package com.xavior.baiyunkeys.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 里的 AES-256-GCM 加解密盒子，对应 iOS 的 Keychain 语义。
 *
 * 方案 §4.5：门禁 JSON 用 Keystore 密钥加密后 Base64 落盘；密钥不出安全硬件，
 * `setUserAuthenticationRequired(false)`（与 iOS 的 AfterFirstUnlock 对齐，开机即可读）。
 *
 * 密钥失效（换机、部分 ROM 恢复、卸载重装）时 [decrypt] 会抛异常 —— 调用方必须
 * **提示重新导入**，不能静默丢数据。
 */
class KeystoreBox(private val alias: String = DEFAULT_ALIAS) {

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)

        // 落盘格式：[1 字节 iv 长度][iv][密文]，Base64 后存 SharedPreferences
        val combined = ByteArray(1 + iv.size + body.size)
        combined[0] = iv.size.toByte()
        iv.copyInto(combined, destinationOffset = 1)
        body.copyInto(combined, destinationOffset = 1 + iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > 1) { "密文长度非法" }
        val ivSize = combined[0].toInt() and 0xFF
        require(combined.size > 1 + ivSize) { "密文长度非法" }
        val iv = combined.copyOfRange(1, 1 + ivSize)
        val body = combined.copyOfRange(1 + ivSize, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    /** 删掉密钥（调试用；删了就解不开旧数据）。 */
    fun reset() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun secretKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "baiyun.doors.v1"
    }
}
