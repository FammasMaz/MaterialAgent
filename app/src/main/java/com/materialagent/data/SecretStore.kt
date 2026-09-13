package com.materialagent.data

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
 * Small Keystore-backed secret vault.
 *
 * Credentials never touch plain storage: an AES-256-GCM key is generated
 * inside the Android Keystore (non-exportable) and only the ciphertext is
 * persisted. If the key is lost — a device restore, a Keystore reset — every
 * secret decrypts to null and the UI asks the user to sign in again rather
 * than silently sending a garbage token.
 *
 * Deliberately tiny: no third-party crypto dependency, no `EncryptedSharedPreferences`
 * (deprecated upstream), and no plaintext fallback.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val key: SecretKey? by lazy { loadOrCreateKey() }

    fun put(id: String, secret: String) {
        val k = key ?: return
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + ciphertext
            prefs.edit().putString(keyFor(id), Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }
    }

    fun get(id: String): String? {
        val k = key ?: return null
        val encoded = prefs.getString(keyFor(id), null) ?: return null
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            if (blob.size <= IV_LENGTH) return null
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val body = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(id: String) {
        prefs.edit().remove(keyFor(id)).apply()
    }

    private fun keyFor(id: String) = "secret_$id"

    private fun loadOrCreateKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey()
    }.getOrNull()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "materialagent_secrets"
        const val ALIAS = "materialagent.secret.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
