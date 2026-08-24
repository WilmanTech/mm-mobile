package com.wtm.musicmanager.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object AuthStorageFactory {

    fun create(
        authStorage: AuthStorage,
        engineFactory: HttpClientEngineFactory<*>,
        enableLogging: Boolean = false,
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
        }
    }
}
