package com.wim4you.intervene.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts sensitive Room fields at rest using AES-256-GCM via Android Keystore.
 * Legacy plaintext values are returned as-is until migrated.
 */
object SensitiveFieldProtector {

    private const val KEY_ALIAS = "intervene_sensitive_fields"
    private const val PREFIX = "enc1:"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty() || isEncrypted(plaintext)) return plaintext
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        if (combined.size <= GCM_IV_LENGTH) return stored
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
}
