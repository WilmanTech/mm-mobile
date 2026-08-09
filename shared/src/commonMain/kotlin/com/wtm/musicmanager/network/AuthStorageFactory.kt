package com.wtm.musicmanager.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Wires the Ktor HttpClient with the JSON plugin + auth header injection.
 *
 * In production builds, `engineFactory` is the platform's HttpClientEngine:
 *   - Android → OkHttp
 *   - iOS     → Darwin
 *
 * In tests, `engineFactory` is MockEngine (ktor-client-mock) and the test
 * installs a per-test `addHandler { request -> ... }` to assert behaviour.
 *
 * The auth header is injected via `defaultRequest` so it lands on every
 * call automatically — callers don't need to remember to attach it.
 */
object AuthStorageFactory {

    fun create(
        authStorage: AuthStorage,
        engineFactory: HttpClientEngineFactory<*>,
        enableLogging: Boolean = false,
        baseUrl: String = "",
    ): HttpClient {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        return HttpClient(engineFactory) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            if (enableLogging) {
                install(Logging) { level = LogLevel.INFO }
            }
            defaultRequest {
                authStorage.loadToken()?.let { token ->
                    header("X-Pairing-Token", token)
                }
                if (baseUrl.isNotEmpty()) {
                    url(baseUrl)
                }
            }
        }
    }
}
