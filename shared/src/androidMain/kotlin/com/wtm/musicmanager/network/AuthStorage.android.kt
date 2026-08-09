package com.wtm.musicmanager.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android actual: stores the pairing token in EncryptedSharedPreferences
 * (AES-256, key in Android Keystore). Survives app updates, cleared on
 * app data clear.
 */
actual class AuthStorage(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual fun loadToken(): String? = prefs.getString(KEY_TOKEN, null)

    actual fun saveToken(token: String, serverLabel: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .apply {
                if (serverLabel != null) putString(KEY_SERVER_LABEL, serverLabel)
            }
            .apply()
    }

    actual fun clearToken() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_SERVER_LABEL)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "musicmanager_secure_prefs"
        const val KEY_TOKEN = "pairing_token"
        const val KEY_SERVER_LABEL = "server_label"
    }
}
