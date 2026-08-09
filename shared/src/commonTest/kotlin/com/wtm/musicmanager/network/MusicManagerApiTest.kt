package com.wtm.musicmanager.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MusicManagerApiTest {

    private fun json() = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun client(handler: MockRequestHandler): HttpClient {
        val engine = MockEngine(handler)
        return HttpClient(engine) {
            install(ContentNegotiation) { json(this@MusicManagerApiTest.json()) }
        }
    }

    @Test
    fun `fullSync decodes all five lists from the server payload`() = runTest {
        val payload = """
            {
              "artists": [{"id":1,"name":"Aesop Rock","album_count":3,"track_count":42,"cover_url":null,"updated_at":100}],
              "albums":  [{"id":10,"title":"None Shall Pass","artist_id":1,"artist_name":"Aesop Rock","year":2007,"track_count":15,"duration_ms":3600000,"cover_url":null,"updated_at":100}],
              "tracks":  [{"id":100,"title":"None Shall Pass","album_id":10,"album_title":"None Shall Pass","artist_id":1,"artist_name":"Aesop Rock","duration_ms":210000,"track_number":1,"bitrate":320,"codec":"mp3","stream_url":null,"updated_at":100}],
              "playlists":[{"id":1000,"name":"Best of 2007","track_count":15,"cover_url":null,"is_smart":false,"is_m3u_imported":false,"updated_at":100}],
              "playlist_tracks":[{"playlist_id":1000,"track_id":100,"position":0,"added_at":1234}],
              "server_time": 1700000000000
            }
        """.trimIndent()

        val handler: MockRequestHandler = { request ->
            assertEquals("/api/v1/sync/full", request.url.encodedPath)
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.fullSync()

        assertEquals(1, response.artists.size)
        assertEquals(1, response.albums.size)
        assertEquals(1, response.tracks.size)
        assertEquals(1, response.playlists.size)
        assertEquals(1, response.playlistTracks.size)
        assertEquals(1700000000000L, response.serverTime)
        assertEquals("Aesop Rock", response.artists.first().name)
        assertEquals("None Shall Pass", response.tracks.first().title)
    }

    @Test
    fun `changesSince sends X-Since header and parses response`() = runTest {
        val payload = """
            {
              "artists": [],
              "albums":  [],
              "tracks":  [],
              "playlists": [],
              "playlist_tracks": [],
              "server_time": 1700000005000,
              "has_more": false
            }
        """.trimIndent()

        val handler: MockRequestHandler = { request ->
            assertEquals("/api/v1/sync/changes", request.url.encodedPath)
            assertEquals("1700000000000", request.headers["X-Since"])
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.changesSince(1700000000000L)

        assertEquals(1700000005000L, response.serverTime)
        assertEquals(false, response.hasMore)
    }

    @Test
    fun `startPairing sends device_type and returns session_id + code`() = runTest {
        val handler: MockRequestHandler = { request ->
            assertEquals("/api/pairing/start", request.url.encodedPath)
            respond(
                """{"session_id":"abc123","code":"wolf-blue-river-stone","expires_at":1700000900000}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.startPairing(StartPairingRequest())

        assertEquals("abc123", response.sessionId)
        assertEquals("wolf-blue-river-stone", response.code)
        assertEquals(1700000900000L, response.expiresAt)
    }

    @Test
    fun `pairingStatus sends X-Session-Id header`() = runTest {
        val handler: MockRequestHandler = { request ->
            assertEquals("/api/pairing/status", request.url.encodedPath)
            assertEquals("sess-99", request.headers["X-Session-Id"])
            respond(
                """{"status":"pending"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.pairingStatus("sess-99")

        assertEquals("pending", response.status)
    }

    @Test
    fun `fullSync propagates HTTP 500 as HttpResponseException`() = runTest {
        val handler: MockRequestHandler = { _ ->
            respond("""{"detail":"db down"}""", HttpStatusCode.InternalServerError)
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")

        // Ktor client with expectSuccess=false returns the response without
        // throwing; we wrap the call to assert non-200 status propagates.
        val engine = MockEngine(handler)
        val rawClient = HttpClient(engine) {
            install(ContentNegotiation) { json(this@MusicManagerApiTest.json()) }
            expectSuccess = true
        }
        val rawApi = MusicManagerApi(rawClient, baseUrl = "http://test")
        try {
            rawApi.fullSync()
            kotlin.test.fail("Expected exception on 500")
        } catch (e: Exception) {
            // expected — exact type is Ktor's ClientRequestException / ServerResponseException
        }
    }
}
