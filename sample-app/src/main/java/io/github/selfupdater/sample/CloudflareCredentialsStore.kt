package io.github.selfupdater.sample

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit

/** Credentials deliberately have no value-based toString implementation. */
internal class CloudflareAccessCredentials(
    val clientId: String,
    val clientSecret: String,
)

/** Persists Cloudflare Access credentials as AES-GCM ciphertext backed by Android Keystore. */
internal class CloudflareCredentialsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(credentials: CloudflareAccessCredentials) {
        runCatching { persist(credentials) }.getOrElse {
            clear()
            persist(credentials)
        }
    }

    private fun persist(credentials: CloudflareAccessCredentials) {
        preferences.edit(commit = true) {
            putString(CLIENT_ID, encrypt(CLIENT_ID, credentials.clientId))
            putString(CLIENT_SECRET, encrypt(CLIENT_SECRET, credentials.clientSecret))
        }
    }

    fun load(): CloudflareAccessCredentials? {
        val encryptedId = preferences.getString(CLIENT_ID, null)
        val encryptedSecret = preferences.getString(CLIENT_SECRET, null)
        if (encryptedId == null || encryptedSecret == null) {
            if (encryptedId != null || encryptedSecret != null) clear()
            return null
        }
        return runCatching {
            CloudflareAccessCredentials(
                clientId = decrypt(CLIENT_ID, encryptedId),
                clientSecret = decrypt(CLIENT_SECRET, encryptedSecret),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun clear() {
        preferences.edit { clear() }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun encrypt(field: String, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(field: String, encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.isNotEmpty()) { "Encrypted credential is empty" }
        val ivLength = payload[0].toInt() and 0xff
        require(ivLength in 12..16 && payload.size > 1 + ivLength) {
            "Encrypted credential is malformed"
        }
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "io.github.selfupdater.sample.cloudflare-access"
        const val PREFERENCES_NAME = "cloudflare_access_credentials"
        const val CLIENT_ID = "client_id"
        const val CLIENT_SECRET = "client_secret"
    }
}
