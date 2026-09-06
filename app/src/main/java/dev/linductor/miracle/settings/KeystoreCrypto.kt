package dev.linductor.miracle.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key 加密（AndroidKeyStore AES-256-GCM；安全底线：凭据只以密文持久化）。
 *
 * 抽象 [AesGcmEngine] 以便 JVM 单测注入纯 SecretKeySpec 引擎（AndroidKeyStore
 * 不可用于 JVM）；blob 布局＝IV(12B) || ciphertext+tag(16B)。
 */
interface AesGcmEngine {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

class AndroidKeystoreEngine(private val alias: String = "miracle.model.key") : AesGcmEngine {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return iv + encrypted
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LENGTH + TAG_LENGTH) { "encrypted blob too short" }
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val encrypted = blob.copyOfRange(IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH * 8, iv))
        return cipher.doFinal(encrypted)
    }

    private companion object {
        const val ANDROID_STORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 16
    }
}

/** JVM 单测引擎：直接持钥（与生产引擎同布局/同算法）。 */
class SymmetricEngine(private val key: SecretKey) : AesGcmEngine {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 12 + 16) { "encrypted blob too short" }
        val iv = blob.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }
}
