package com.wtm.musicmanager.network

/**
 * iOS-callable factory for the shared HttpClient + MusicManagerApi.
 *
 * Lives in shared/commonMain because the configuration (ContentNegotiation +
 * JSON + timeouts) is platform-agnostic even though the engine differs
 * per target (Darwin on iOS, OkHttp on Android, CIO on JVM).
 *
 * Swift calls `HttpClientFactory.shared.make(baseUrl:)` and never sees the
 * Ktor engine type.
 *
 * NOTE: Kotlin Multiplatform's expect object does NOT support member-
 * function overloading (KT-77906). The two factory flavours are exposed
 * as distinct names instead.
 */
expect object HttpClientFactory {
    fun make(baseUrl: String): MusicManagerApi
    fun makeAuthenticated(baseUrl: String, authStorage: AuthStorage?): MusicManagerApi
}