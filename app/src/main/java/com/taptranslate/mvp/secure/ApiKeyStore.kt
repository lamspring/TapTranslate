package com.taptranslate.mvp.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用户 LLM API Key 的本地安全存储。
 *
 *  - 主密钥由 Android Keystore 保管（硬件级，不可导出，AES-256/GCM）
 *  - API Key 加密后（12 字节 IV + 密文，Base64）存入 App 私有 SharedPreferences
 *  - 解密只发生在内存，绝不写日志、绝不外传
 *
 * 第二阶段可选加固：KeyGenParameterSpec.Builder.setUserAuthenticationRequired(true)，
 * 开启后每次使用 Key 需指纹/人脸验证。
 */
class ApiKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // .setUserAuthenticationRequired(true)  // 生物识别保护（第二阶段）
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    fun hasApiKey(): Boolean = prefs.contains(PREF_KEY)

    fun saveApiKey(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(ciphertext, 0, packed, iv.size, ciphertext.size)
        prefs.edit().putString(PREF_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun getApiKey(): String? {
        val encoded = prefs.getString(PREF_KEY, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = packed.copyOfRange(GCM_IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    fun clear() {
        prefs.edit().remove(PREF_KEY).apply()
    }

    private companion object {
        const val PREFS_NAME = "secure_storage"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "taptranslate_llm_key_master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val PREF_KEY = "llm_api_key_enc"
    }
}
