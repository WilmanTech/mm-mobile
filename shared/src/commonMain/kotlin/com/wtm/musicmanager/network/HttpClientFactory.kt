package com.wtm.musicmanager.network

import com.wtm.musicmanager.pairing.PairingRepository
import com.wtm.musicmanager.pairing.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * iOS-callable factory for the shared HttpClient.
 *
 * Why this lives in shared/commonMain and not in iosMain: Ktor's Darwin
 * engine is the iOS-specific implementation, but the configuration
 * (ContentNegotiation + JSON + timeouts) is platform-agnostic. Putting
 * the factory here lets Swift call it without needing to instantiate
 * any Ktor types directly.
 *
 * Usage from Swift:
 *   `let client = HttpClientFactory.make(baseUrl: "http://127.0.0.1:8765")`
 *
 * NOTE: the returned `MusicManagerApi` is configured WITHOUT an
 * `AuthStorage` parameter — bearer tokens are added to requests via
 * the per-call `bearerAuth(...)` helper in the iOS wrapper, not via
 * an HttpClient plugin. This avoids coupling the shared module to the
 * iOS-side persistence layer.
 */
object HttpClientFactory {

    fun make(baseUrl: String): MusicManagerApi {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val client = HttpClient(Darwin) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis  = 30_000
            }
        }
        return MusicManagerApi(
            client = client,
            baseUrl = baseUrl,
            authStorage = null,
        )
    }
}

/**
 * Single iOS-callable entry point for the pairing layer.
 *
 * Hides Ktor types (HttpClient, MusicManagerApi, AuthStorage) behind
 * one Swift-callable function so the SwiftUI side only deals with
 * PairingRepository. The `object` Kotlin construct compiles to a
 * singleton accessible from Swift as `PairingEntry.shared`.
 *
 * Why a companion factory instead of letting Swift wire it manually?
 * Three reasons:
 *   1. Ktor's `HttpClient` is JVM/iOS-specific — exposing it to Swift
 *      drags Ktor types into the iOS target's module map.
 *   2. AuthStorage is an `expect/actual` class; Swift can't easily
 *      instantiate the iOS actual (it's an Objective-C NSUserDefaults
 *      wrapper).
 *   3. PairingRepository's constructor signature will keep growing
 *      (clock, http client factory, retry policy, etc.); this factory
 *      lets us add parameters without breaking the Swift bridge.
 */
object PairingEntry {

    /**
     * Build a fully-configured `PairingRepository` against `host:port`.
     * Swift equivalent: `PairingEntry.shared.make(host:port:)`.
     */
    fun make(host: String, port: String): PairingRepository {
        val baseUrl = "http://$host:$port"
        val api = HttpClientFactory.make(baseUrl)
        val tokenStore = TokenStore.from(AuthStorage())
        return PairingRepository(api = api, tokenStore = tokenStore)
    }
}
