package com.wtm.musicmanager.pairing

import com.wtm.musicmanager.network.AuthStorage
import com.wtm.musicmanager.network.HttpClientFactory

/**
 * Single iOS-callable entry point for the pairing layer.
 *
 * Lives in commonMain because the only types it depends on (HttpClientFactory,
 * PairingRepository, TokenStore) already have implementations on every target.
 * The iOS Swift bridge calls `PairingEntry.shared.make(host:port:)` and never
 * sees Ktor / NSUserDefaults / EncryptedSharedPreferences.
 *
 * Why this is the only iOS-facing factory: Kotlin/Native exposes an `object`
 * as a Swift class with a static `shared` instance, and `fun make(host, port)`
 * becomes `PairingEntry.shared.make(host:port:)` directly. Keeping the surface
 * to one call site means a future change to PairingRepository's constructor
 * signature (clock, retry policy, etc.) doesn't ripple into Swift.
 *
 * AuthStorage construction is delegated to the top-level
 * `expect fun defaultAuthStorage()` factory — see PairingEntryAuthStorage.kt
 * for each platform's `actual`. Each target wires its own backend
 * (EncryptedSharedPreferences on Android, NSUserDefaults on iOS, file-based
 * on JVM for tests).
 */
object PairingEntry {

    fun make(host: String, port: String): PairingRepository {
        val baseUrl = "http://$host:$port"
        val api = HttpClientFactory.make(baseUrl)
        val tokenStore = TokenStore.from(defaultAuthStorage())
        return PairingRepository(api = api, tokenStore = tokenStore)
    }
}
