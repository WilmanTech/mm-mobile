package com.wtm.musicmanager.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * iOS actual: uses Ktor's `Darwin` engine.
 *
 * See `HttpClientFactory.kt` in commonMain for the rationale.
 */
actual object HttpClientFactory {
    actual fun make(baseUrl: String): MusicManagerApi =
        makeAuthenticated(baseUrl, authStorage = null)

    actual fun makeAuthenticated(baseUrl: String, authStorage: AuthStorage?): MusicManagerApi {
        val client = HttpClient(Darwin) {
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