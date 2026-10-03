package com.totaliptv.pro.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class SavedLogin(val baseUrl: String, val username: String, val password: String) {
    override fun toString(): String = "SavedLogin(<hidden>)"
}

/**
 * The last Xtream sign-in on this device: server URL, username and password.
 * Kept after sign-out or a failed try so the login form stays filled in, until
 * the user taps "Forget saved sign-in".
 *
 * Stored as one AES-256-GCM blob in app-private SharedPreferences. The key is
 * generated in the Android Keystore and never leaves it. Nothing here is ever
 * logged. Public builds start empty; the store only fills from what the user
 * types (or an existing saved source).
 */
class SavedLoginStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): SavedLogin? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val json = JSONObject(decrypt(blob))
            SavedLogin(json.optString("u"), json.optString("n"), json.optString("p"))
        }.getOrNull()?.takeIf { it.baseUrl.isNotBlank() }
    }

    /**
     * Saves the URL and username. A null [password] keeps the stored one, so a failed
     * try with the same account does not wipe it.
     */
    fun save(baseUrl: String, username: String, password: String?): Boolean {
        if (baseUrl.isBlank()) return false
        return runCatching {
            val keep = password ?: load()?.takeIf { it.username == username }?.password.orEmpty()
            val json = JSONObject()
                .put("u", baseUrl)
                .put("n", username)
                .put("p", keep)
            prefs.edit().putString(KEY_BLOB, encrypt(json.toString())).apply()
            true
        }.getOrDefault(false)
    }

    /** Forget: removes the saved sign-in and stops the one-time upgrade seed from refilling it. */
    fun clear() {
        prefs.edit().remove(KEY_BLOB).putBoolean(KEY_SEEDED, true).apply()
    }

    /** One-time upgrade path: an account signed in before 1.4.83 becomes the saved sign-in. */
    fun seedOnce(baseUrl: String, username: String, password: String) {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        if (load() == null) save(baseUrl, username, password)
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    /** True once the upgrade seed ran or the user chose Forget. */
    fun seeded(): Boolean = prefs.getBoolean(KEY_SEEDED, false)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(sealed)
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(":")
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0])))
        return String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val PREFS = "saved_login"
        private const val KEY_BLOB = "login"
        private const val KEY_SEEDED = "seeded"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "tip_saved_login"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
