package com.wtm.musicmanager.network

import com.wtm.musicmanager.pairing.PairingRepository
import com.wtm.musicmanager.pairing.TokenStore

/**
 * iOS-callable factory for the shared HttpClient + MusicManagerApi.
 *
 * Why this lives in shared/commonMain even though the engine is platform-
 * specific: Ktor's `HttpClient` engine differs per target (Darwin on iOS,
 * OkHttp on Android, CIO on JVM), but the *configuration* (ContentNegotiation +
 * JSON + timeouts) is platform-agnostic. We split it into:
 *
 *   - commonMain: the `expect fun makeHttpClient()` declaration + the shared
 *     `Json` instance (the JSON config is the same on every target).
 *   - iosMain: actual — uses `Darwin` engine.
 *   - androidMain: actual — uses `OkHttp` engine.
 *   - jvmMain: actual — uses `CIO` engine.
 *
 * Swift calls `HttpClientFactory.shared.make(baseUrl:)` and never sees the
 * Ktor engine type.
 *
 * NOTE: the returned `MusicManagerApi` is configured WITHOUT an
 * `AuthStorage` parameter — bearer tokens are added to requests via the
 * per-call `bearerAuth(...)` helper in the iOS wrapper, not via an
 * HttpClient plugin. This avoids coupling the shared module to the
 * iOS-side persistence layer.
 */
expect object HttpClientFactory {
    fun make(baseUrl: String): MusicManagerApi
}

/**
 * Shared JSON config — `ignoreUnknownKeys` so backend can add fields
 * without breaking older clients, `explicitNulls = false` so Kotlin
 * nullables don't leak through serialization.
 */
internal val sharedJson: kotlinx.serialization.json.Json
    get() = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
