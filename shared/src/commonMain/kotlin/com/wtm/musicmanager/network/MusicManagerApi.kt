package com.wtm.musicmanager.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

class MusicManagerApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val authStorage: AuthStorage? = null,
) {
    /**
     * Bootstrap snapshot of the entire library. Use on first sync or to
     * recover from a corrupted local cache. Returns artists/albums/tracks/
     * playlists in one envelope.
     */
    suspend fun fullSync(): SyncResponse =
        client.get("$baseUrl/api/v1/sync/full") {
            withBearer()
        }.body()

    /**
     * Incremental deltas. `since` is an ISO 8601 string like
     * "2026-08-09T12:00:00Z". If null/empty the server returns the same as
     * /sync/full.
     */
    suspend fun changesSince(since: String?): SyncResponse =
        client.get("$baseUrl/api/v1/sync/changes") {
            if (!since.isNullOrEmpty()) parameter("since", since)
            withBearer()
        }.body()

    suspend fun startPairing(request: PairingStartRequest): PairingStartResponse =
        client.post("$baseUrl/api/pairing/start") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /**
     * Status is a GET with `session_id` as a query param (NOT a header —
     * the backend reads it from `?session_id=`).
     */
    suspend fun pairingStatus(sessionId: String): PairingStatusResponse =
        client.get("$baseUrl/api/pairing/status") {
            parameter("session_id", sessionId)
        }.body()

    suspend fun confirmPairing(request: PairingConfirmRequest): PairingConfirmResponse =
        client.post("$baseUrl/api/pairing/confirm") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /**
     * Authenticated ping. Returns 200 if the bearer is valid, 401 otherwise.
     * Used by the app on launch to detect a stale token after a desktop-side
     * revoke.
     */
    suspend fun ping(): HttpResponse =
        client.get("$baseUrl/api/v1/ping") { withBearer() }

    /**
     * Authenticated whoami. Returns 200 if the bearer is valid.
     */
    suspend fun whoami(): HttpResponse =
        client.get("$baseUrl/api/v1/whoami") { withBearer() }

    private fun HttpRequestBuilder.withBearer() {
        authStorage?.loadToken()?.let { token -> bearerAuth(token) }
    }
}
