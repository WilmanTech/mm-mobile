package com.wtm.musicmanager.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Android actual: uses Ktor's `OkHttp` engine (already pulled in transitively
 * by the Android dependency graph).
 *
 * See `HttpClientFactory.kt` in commonMain for the rationale.
 */
actual object HttpClientFactory {
    actual fun make(baseUrl: String): MusicManagerApi {
        val client = HttpClient(OkHttp) {
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
            authStorage = null,
        )
    }
}
