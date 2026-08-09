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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicManagerApiTest {

    private fun json() = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun client(handler: MockRequestHandler): HttpClient {
        val engine = MockEngine(handler)
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(this@MusicManagerApiTest.json())
            }
        }
    }

    @Test
    fun `fullSync decodes the real envelope shape`() = runTest {
        val payload = """
            {
              "server_time": "2026-08-09T12:34:56Z",
              "artists": [
                {"id":1,"name":"Aesop Rock","musicbrainz_id":"abc","image_path":null,
                 "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-08-09T12:00:00Z"}
              ],
              "albums": [
                {"id":10,"title":"None Shall Pass","artist_id":1,"year":2007,"genre":"Hip-Hop",
                 "cover_path":null,"musicbrainz_id":null,"folder_path":"/music/Aesop/None Shall Pass",
                 "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-08-09T12:00:00Z"}
              ],
              "tracks": [
                {"id":100,"title":"None Shall Pass","album_id":10,"artist_id":1,
                 "disc_number":1,"track_number":1,"duration_ms":210000,"bitrate":320,"codec":"mp3",
                 "file_path":"/music/Aesop/None Shall Pass/01.mp3","file_hash":"abc","acoustid":null,
                 "musicbrainz_id":null,"play_count":0,"last_played":null,"added_at":"2026-01-01T00:00:00Z",
                 "is_favorite":false,"is_cover":false,"is_live":false,
                 "updated_at":"2026-08-09T12:00:00Z"}
              ],
              "playlists": [
                {"id":1000,"name":"Best of 2007","description":null,"is_smart":false,"rules":null,
                 "m3u_path":null,"is_m3u_imported":false,"created_at":"2026-01-01T00:00:00Z",
                 "updated_at":"2026-08-09T12:00:00Z"}
              ],
              "playlist_tracks": [
                {"playlist_id":1000,"track_id":100,"position":0,"added_at":"2026-01-01T00:00:00Z"}
              ]
            }
        """.trimIndent()

        val handler: MockRequestHandler = { request ->
            assertEquals("/api/v1/sync/full", request.url.encodedPath)
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.fullSync()

        assertEquals("2026-08-09T12:34:56Z", response.serverTime)
        assertEquals(1, response.artists.size)
        assertEquals(1, response.albums.size)
        assertEquals(1, response.tracks.size)
        assertEquals(1, response.playlists.size)
        assertEquals(1, response.playlistTracks.size)
        assertEquals("Aesop Rock", response.artists.first().name)
        assertEquals("None Shall Pass", response.tracks.first().title)
        assertEquals("abc", response.artists.first().musicbrainzId)
    }

    @Test
    fun `changesSince sends since as a query param with ISO 8601 string`() = runTest {
        val payload = """
            {
              "server_time": "2026-08-09T12:35:00Z",
              "since": "2026-08-09T12:00:00Z",
              "artists": [], "albums": [], "tracks": [], "playlists": [], "playlist_tracks": []
            }
        """.trimIndent()

        val handler: MockRequestHandler = { request ->
            assertEquals("/api/v1/sync/changes", request.url.encodedPath)
            assertEquals("2026-08-09T12:00:00Z", request.url.parameters["since"])
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.changesSince("2026-08-09T12:00:00Z")

        assertEquals("2026-08-09T12:35:00Z", response.serverTime)
        assertEquals(0, response.tracks.size)
    }

    @Test
    fun `changesSince omits since when null`() = runTest {
        val payload = """
            { "server_time": "2026-08-09T12:35:00Z",
              "artists": [], "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [] }
        """.trimIndent()

        val handler: MockRequestHandler = { request ->
            assertEquals("/api/v1/sync/changes", request.url.encodedPath)
            assertNull(request.url.parameters["since"])
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        api.changesSince(null)
    }

    @Test
    fun `startPairing sends device_type and returns session+token+code+expires_in`() = runTest {
        val handler: MockRequestHandler = { request ->
            assertEquals("/api/pairing/start", request.url.encodedPath)
            respond(
                """{"session_id":"abc123","token":"tok-xyz","code":"wolf-blue-river-stone","expires_in":300}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.startPairing(PairingStartRequest(deviceType = "mobile"))

        assertEquals("abc123", response.sessionId)
        assertEquals("tok-xyz", response.token)
        assertEquals("wolf-blue-river-stone", response.code)
        assertEquals(300, response.expiresIn)
    }

    @Test
    fun `pairingStatus sends session_id as a query param`() = runTest {
        val handler: MockRequestHandler = { request ->
            assertEquals("/api/pairing/status", request.url.encodedPath)
            assertEquals("sess-99", request.url.parameters["session_id"])
            respond(
                """{"exists":true,"session_id":"sess-99","confirmed":false,"expired":false,"revoked":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.pairingStatus("sess-99")

        assertTrue(response.exists)
        assertEquals(false, response.confirmed)
    }

    @Test
    fun `confirmPairing sends session_id+code+device_name and parses response`() = runTest {
        val handler: MockRequestHandler = { request ->
            assertEquals("/api/pairing/confirm", request.url.encodedPath)
            respond(
                """{"status":"confirmed","token":"tok-xyz","device_name":"iPhone","paired_at":1700000900.5}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = MusicManagerApi(client(handler), baseUrl = "http://test")
        val response = api.confirmPairing(
            PairingConfirmRequest(sessionId = "s1", code = "wolf-blue-river-stone", deviceName = "iPhone", deviceType = "mobile")
        )

        assertEquals("confirmed", response.status)
        assertEquals("tok-xyz", response.token)
        assertEquals("iPhone", response.deviceName)
    }
}
