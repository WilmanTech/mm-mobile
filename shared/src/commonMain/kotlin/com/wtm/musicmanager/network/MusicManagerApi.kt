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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MusicManagerApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val authStorage: AuthStorage? = null,
) {
    /**
     * Bootstrap snapshot of the entire library. Use on first sync or to
     * recover from a corrupted local cache. Returns artists/albums/tracks/
     * playlists in one envelope.
     *
     * Throws [SyncServerError] if the backend returns an error envelope
     * (e.g. `{"detail": "No library opened"}`). This is the only sync
     * error the UI needs to surface verbatim — everything else funnels
     * through [SyncCoordinator.syncFull]'s generic catch block.
     */
    suspend fun fullSync(): SyncResponse = parseSync("$baseUrl/api/v1/sync/full")

    /**
     * Incremental deltas. `since` is an ISO 8601 string like
     * "2026-08-09T12:00:00Z". If null/empty the server returns the same as
     * /sync/full.
     */
    suspend fun changesSince(since: String?): SyncResponse {
        val url = "$baseUrl/api/v1/sync/changes"
        return if (since.isNullOrEmpty()) {
            parseSync(url)
        } else {
            parseSync(url, queryParams = mapOf("since" to since))
        }
    }

    /**
     * Common GET-then-parse logic for the two sync endpoints. The error
     * envelope shape (`{"detail": "..."}`) is from FastAPI/HTTPException
     * and is parsed before the success-shape parse so we can throw a
     * typed exception instead of a generic
     * `kotlinx.serialization.SerializationException` when the server
     * replies with an error.
     */
    private suspend fun parseSync(
        url: String,
        queryParams: Map<String, String> = emptyMap(),
    ): SyncResponse {
        val response: HttpResponse = client.get(url) {
            queryParams.forEach { (k, v) -> parameter(k, v) }
            withBearer()
        }
        val text = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            // Parse the FastAPI error envelope. If it doesn't have a
            // `detail` field, fall back to the status code text.
            val detail = try {
                JSON.decodeFromString<FastApiError>(text).detail
            } catch (_: Throwable) {
                text.take(200)
            }
            throw SyncServerError(
                httpStatus = response.status.value,
                detail = detail,
            )
        }
        return try {
            JSON.decodeFromString<SyncResponse>(text)
        } catch (e: Throwable) {
            // Couldn't parse a 200 OK body as SyncResponse. Surface as
            // a SyncServerError so SyncCoordinator can show it instead
            // of a generic "Sync failed" with the raw exception.
            throw SyncServerError(
                httpStatus = response.status.value,
                detail = "Unparseable sync response: ${e.message}",
            )
        }
    }

    /**
     * Build the absolute streaming URL for a track. Used by the
     * download worker (Phase 3.A) and the in-app player (Phase 3.B).
     * The backend's `/api/stream/{track_id}` endpoint serves Range
     * requests, so the same URL works for both:
     *  - ExoPlayer stream (no download, partial reads as user scrubs)
     *  - WorkManager full-file download (one GET, write to disk)
     *
     * Phase 3.A callers: see `download/DownloadWorker.kt`.
     */
    fun streamUrl(trackId: Long): String = "$baseUrl/api/stream/$trackId"

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

    /**
     * Library-wide stats from `/api/library/stats`. Used by the iOS
     * Home screen summary card. Backend returns the totals + a
     * codec/quality breakdown. We don't currently render the
     * breakdown but the data is here so future tiles can use it
     * without re-shaping.
     */
    suspend fun libraryStats(): LibraryStatsDto =
        client.get("$baseUrl/api/library/stats") {
            withBearer()
        }.body()

    /**
     * Recently-played tracks from `/api/library/recent/tracks`.
     * The endpoint is ordered server-side by `last_played DESC` and
     * typically capped at 20 rows — we don't re-sort in Kotlin to
     * stay consistent with the desktop's "Recent" view.
     */
    suspend fun recentTracks(): List<RecentTrackDto> =
        client.get("$baseUrl/api/library/recent/tracks") {
            withBearer()
        }.body()

    /**
     * Recently-played albums from `/api/library/recent/albums`.
     */
    suspend fun recentAlbums(): List<RecentAlbumDto> =
        client.get("$baseUrl/api/library/recent/albums") {
            withBearer()
        }.body()

    private fun HttpRequestBuilder.withBearer() {
        authStorage?.loadToken()?.let { token -> bearerAuth(token) }
    }
}

/**
 * Thrown by [MusicManagerApi.fullSync] / [changesSince] when the
 * backend returns an HTTP error envelope. Lets SyncCoordinator surface
 * a meaningful message to the UI instead of the generic
 * `kotlinx.serialization.SerializationException` (which is what
 * would happen if we tried to `.body()` the FastAPI error envelope
 * as `SyncResponse`).
 *
 * Carries both the HTTP status and the human-readable `detail` field
 * from the error envelope. The UI can show the detail directly
 * ("No library opened", "Invalid or revoked pairing token", etc.).
 */
class SyncServerError(
    val httpStatus: Int,
    val detail: String,
) : RuntimeException("HTTP $httpStatus: $detail") {
    /**
     * True when the error means the user needs to take action on the
     * MusicManager desktop before sync can succeed. The UI shows a
     * targeted hint instead of a generic "sync failed".
     */
    val isNoLibrary: Boolean get() = detail.contains("No library", ignoreCase = true)
}

@Serializable
private data class FastApiError(
    val detail: String = "",
)

private val JSON = Json { ignoreUnknownKeys = true }
