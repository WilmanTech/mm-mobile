package com.wtm.musicmanager.network

import platform.Foundation.NSUserDefaults

/**
 * iOS actual: stores the pairing token in NSUserDefaults under a single key.
 *
 * NSUserDefaults is the standard iOS key/value store. For higher-security
 * contexts, Keychain would be the right choice, but pairing tokens are
 * device-scoped and revocable from the backend, so the convenience wins.
 *
 * iOS app sandbox clears UserDefaults on app uninstall, so no manual cleanup
 * is needed on unpair beyond [clearToken].
 */
actual class AuthStorage {
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    actual fun loadToken(): String? = defaults.stringForKey(KEY_TOKEN)

    actual fun saveToken(token: String) {
        defaults.setObject(token, forKey = KEY_TOKEN)
    }

    actual fun clearToken() {
        defaults.removeObjectForKey(KEY_TOKEN)
    }

    private companion object {
        const val KEY_TOKEN = "pairing_token"
    }
}
