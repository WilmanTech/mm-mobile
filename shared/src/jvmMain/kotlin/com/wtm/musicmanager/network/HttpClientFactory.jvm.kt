package com.wtm.musicmanager.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * JVM actual: uses Ktor's `CIO` engine (pure Kotlin, no platform deps).
 *
 * Used by `:shared:jvmTest` and the desktop dev shell. Production Android
 * always uses the Android actual above; iOS uses the iOS actual.
 */
actual object HttpClientFactory {
    actual fun make(baseUrl: String): MusicManagerApi =
        makeAuthenticated(baseUrl, authStorage = null)

    actual fun makeAuthenticated(baseUrl: String, authStorage: AuthStorage?): MusicManagerApi {
        val client = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) { json(sharedJson) }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 30_000
            }
        }
        return MusicManagerApi(
            client = client,
            baseUrl = baseUrl,
            authStorage = authStorage,
        )
    }
}