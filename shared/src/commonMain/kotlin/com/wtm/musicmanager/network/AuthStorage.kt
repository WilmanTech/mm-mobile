package com.wtm.musicmanager.network

/**
 * Storage interface for the pairing token + server label.
 *
 * expect/actual over Android (EncryptedSharedPreferences), iOS (Keychain),
 * and JVM (file-based for tests + dev). The token is what the auth
 * interceptor reads on every authenticated request.
 *
 * Production builds should never use the JVM actual — Koin wires the
 * Android/iOS one based on the host platform.
 */
expect class AuthStorage {
    fun loadToken(): String?
    fun saveToken(token: String, serverLabel: String?)
    fun clearToken()
}
