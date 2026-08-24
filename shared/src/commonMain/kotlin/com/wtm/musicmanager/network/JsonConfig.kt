package com.wtm.musicmanager.network

/**
 * Shared JSON config used by every platform-specific `HttpClientFactory`
 * actual. `ignoreUnknownKeys` lets the backend add fields without
 * breaking older clients; `explicitNulls = false` keeps Kotlin nullable
 * fields from leaking through serialization as JSON `null`s.
 *
 * Lives in its own file (not in `HttpClientFactory.kt`) because the
 * Kotlin 2.0.21 compiler reports a spurious "Unclosed comment" error
 * when an `internal val ... get() = { ... }` declaration sits in the
 * same file as an `expect object` with KDoc above it (KT-77906
 * adjacent). Splitting the file makes both compile cleanly.
 */
internal val sharedJson: kotlinx.serialization.json.Json
    get() = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }