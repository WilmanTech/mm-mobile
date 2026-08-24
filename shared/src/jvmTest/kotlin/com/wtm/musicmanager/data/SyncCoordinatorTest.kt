package com.wtm.musicmanager.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.wtm.musicmanager.domain.model.Album
import com.wtm.musicmanager.domain.model.Artist
import com.wtm.musicmanager.domain.model.Playlist
import com.wtm.musicmanager.domain.model.PlaylistTrack
import com.wtm.musicmanager.domain.model.Track
import com.wtm.musicmanager.network.MusicManagerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncCoordinatorTest {

    private lateinit var driver: SqlDriver
    private lateinit var queries: TestSyncQueries

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        listOf(
            """
            CREATE TABLE artist (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                musicbrainz_id TEXT,
                image_path TEXT,
                updated_at TEXT,
                synced_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE album (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                artist_id INTEGER NOT NULL,
                year INTEGER,
                genre TEXT,
                cover_path TEXT,
                musicbrainz_id TEXT,
                folder_path TEXT,
                updated_at TEXT,
                synced_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE track (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                album_id INTEGER NOT NULL,
                artist_id INTEGER NOT NULL,
                disc_number INTEGER,
                track_number INTEGER,
                duration_ms INTEGER,
                bitrate INTEGER,
                codec TEXT,
                acoust_id TEXT,
                musicbrainz_id TEXT,
                play_count INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                is_live INTEGER NOT NULL DEFAULT 0,
                added_at TEXT,
                updated_at TEXT,
                local_path TEXT,
                download_state TEXT NOT NULL DEFAULT 'NotDownloaded',
                synced_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE playlist (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                is_smart INTEGER NOT NULL DEFAULT 0,
                rules TEXT,
                m3u_path TEXT,
                is_m3u_imported INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT,
                synced_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE playlist_track (
                playlist_id INTEGER NOT NULL,
                track_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                added_at TEXT,
                PRIMARY KEY (playlist_id, track_id)
            )
            """.trimIndent(),
        ).forEach { driver.execute(null, it, 0) }

        queries = TestSyncQueries(driver)
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private fun mockApi(payload: String): MusicManagerApi {
        val engine = MockEngine { _ ->
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        return MusicManagerApi(client, baseUrl = "http://test")
    }

    @Test
    fun `syncFull upserts all five lists and stores server time as ISO string`() = runTest {
        val payload = """
            {
              "server_time": "2026-08-09T12:34:56Z",
              "artists": [
                {"id":1,"name":"Aesop Rock","musicbrainz_id":"abc","image_path":null,
                 "updated_at":"2026-08-09T12:00:00Z"}
              ],
              "albums": [
                {"id":10,"title":"None Shall Pass","artist_id":1,"year":2007,"genre":"Hip-Hop",
                 "cover_path":null,"musicbrainz_id":null,"folder_path":"/music/Aesop/None Shall Pass",
                 "updated_at":"2026-08-09T12:00:00Z"}
              ],
              "tracks": [
                {"id":100,"title":"None Shall Pass","album_id":10,"artist_id":1,
                 "disc_number":1,"track_number":1,"duration_ms":210000,"bitrate":320,"codec":"mp3",
                 "acoustid":null,"musicbrainz_id":null,"play_count":0,"is_favorite":false,
                 "is_cover":false,"is_live":false,"added_at":"2026-01-01T00:00:00Z",
                 "updated_at":"2026-08-09T12:00:00Z"}
              ],
              "playlists": [
                {"id":1000,"name":"Best","description":null,"is_smart":false,"rules":null,
                 "m3u_path":null,"is_m3u_imported":false,"updated_at":"2026-08-09T12:00:00Z"}
              ],
              "playlist_tracks": [
                {"playlist_id":1000,"track_id":100,"position":0,"added_at":"2026-01-01T00:00:00Z"}
              ]
            }
        """.trimIndent()

        val coordinator = SyncCoordinator(mockApi(payload), queries)
        val state = coordinator.syncFull()

        assertTrue(state is SyncState.Completed, "expected Completed, got $state")
        assertEquals(1, (state as SyncState.Completed).tracks)
        assertEquals("2026-08-09T12:34:56Z", coordinator.lastServerTime.value)

        assertEquals(1, queries.count("artist"))
        assertEquals(1, queries.count("album"))
        assertEquals(1, queries.count("track"))
        assertEquals(1, queries.count("playlist"))
        assertEquals(1, queries.count("playlist_track"))
    }

    @Test
    fun `syncFull wipes previous rows before applying new payload`() = runTest {
        val firstPayload = """
            { "server_time": "2026-08-09T11:00:00Z",
              "artists": [{"id":1,"name":"Old","updated_at":"2026-08-09T11:00:00Z"}],
              "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [] }
        """.trimIndent()
        val secondPayload = """
            { "server_time": "2026-08-09T12:00:00Z",
              "artists": [{"id":2,"name":"New","updated_at":"2026-08-09T12:00:00Z"}],
              "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [] }
        """.trimIndent()

        val coordinator = SyncCoordinator(mockApi(firstPayload), queries)
        coordinator.syncFull()
        assertEquals(1, queries.count("artist"))

        val second = SyncCoordinator(mockApi(secondPayload), queries)
        second.syncFull()

        assertEquals(1, queries.count("artist"))
        val rows = queries.selectArtists()
        assertEquals("New", rows.first().name)
    }

    @Test
    fun `syncChanges sends since as a query param and merges without wiping`() = runTest {
        val initialPayload = """
            { "server_time": "2026-08-09T11:00:00Z",
              "artists": [{"id":1,"name":"A","updated_at":"2026-08-09T11:00:00Z"}],
              "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [] }
        """.trimIndent()
        val deltaPayload = """
            { "server_time": "2026-08-09T12:00:00Z",
              "since": "2026-08-09T11:00:00Z",
              "artists": [{"id":2,"name":"B","updated_at":"2026-08-09T12:00:00Z"}],
              "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [] }
        """.trimIndent()

        val engine = MockEngine { request ->
            val since = request.url.parameters["since"]
            when (request.url.encodedPath) {
                "/api/v1/sync/full" ->
                    respond(initialPayload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                "/api/v1/sync/changes" -> {
                    assertEquals("2026-08-09T11:00:00Z", since)
                    respond(deltaPayload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = MusicManagerApi(client, baseUrl = "http://test")
        val coordinator = SyncCoordinator(api, queries)

        coordinator.syncFull()
        assertEquals(1, queries.count("artist"))

        coordinator.syncChanges()
        assertEquals(2, queries.count("artist"))
        assertEquals("2026-08-09T12:00:00Z", coordinator.lastServerTime.value)
    }

    @Test
    fun `syncFull handles empty payload gracefully`() = runTest {
        val emptyPayload = """{"server_time":"2026-08-09T12:34:56Z"}"""
        val coordinator = SyncCoordinator(mockApi(emptyPayload), queries)
        val state = coordinator.syncFull()

        assertTrue(state is SyncState.Completed)
        assertEquals(0, (state as SyncState.Completed).artists)
        assertEquals("2026-08-09T12:34:56Z", coordinator.lastServerTime.value)
    }
}

private class TestSyncQueries(private val driver: SqlDriver) : SyncUpsertQueries {

    private val txnLock = Any()

    override fun transaction(block: () -> Unit) {
        synchronized(txnLock) { block() }
    }

    override fun upsertArtist(artist: Artist, syncedAt: String) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO artist(id, name, musicbrainz_id, image_path, updated_at, synced_at) VALUES (?, ?, ?, ?, ?, ?)",
            6,
        ) {
            bindLong(0, artist.id)
            bindString(1, artist.name)
            artist.musicbrainzId?.let { bindString(2, it) } ?: bindString(2, null)
            artist.imagePath?.let { bindString(3, it) } ?: bindString(3, null)
            artist.updatedAt?.let { bindString(4, it) } ?: bindString(4, null)
            bindString(5, syncedAt)
        }
    }

    override fun upsertAlbum(album: Album, syncedAt: String) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO album(id, title, artist_id, year, genre, cover_path, musicbrainz_id, folder_path, updated_at, synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            10,
        ) {
            bindLong(0, album.id)
            bindString(1, album.title)
            bindLong(2, album.artistId)
            album.year?.let { bindLong(3, it.toLong()) } ?: bindLong(3, null)
            album.genre?.let { bindString(4, it) } ?: bindString(4, null)
            album.coverPath?.let { bindString(5, it) } ?: bindString(5, null)
            album.musicbrainzId?.let { bindString(6, it) } ?: bindString(6, null)
            album.folderPath?.let { bindString(7, it) } ?: bindString(7, null)
            album.updatedAt?.let { bindString(8, it) } ?: bindString(8, null)
            bindString(9, syncedAt)
        }
    }

    override fun upsertTrack(track: Track, syncedAt: String) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO track(id, title, album_id, artist_id, disc_number, track_number, duration_ms, bitrate, codec, acoust_id, musicbrainz_id, play_count, is_favorite, is_live, added_at, updated_at, local_path, download_state, synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            19,
        ) {
            bindLong(0, track.id)
            bindString(1, track.title)
            bindLong(2, track.albumId)
            bindLong(3, track.artistId)
            track.discNumber?.let { bindLong(4, it.toLong()) } ?: bindLong(4, null)
            track.trackNumber?.let { bindLong(5, it.toLong()) } ?: bindLong(5, null)
            track.durationMs?.let { bindLong(6, it) } ?: bindLong(6, null)
            track.bitrate?.let { bindLong(7, it.toLong()) } ?: bindLong(7, null)
            track.codec?.let { bindString(8, it) } ?: bindString(8, null)
            track.acoustId?.let { bindString(9, it) } ?: bindString(9, null)
            track.musicbrainzId?.let { bindString(10, it) } ?: bindString(10, null)
            bindLong(11, track.playCount.toLong())
            bindLong(12, if (track.isFavorite) 1L else 0L)
            bindLong(13, if (track.isLive) 1L else 0L)
            track.addedAt?.let { bindString(14, it) } ?: bindString(14, null)
            track.updatedAt?.let { bindString(15, it) } ?: bindString(15, null)
            track.localPath?.let { bindString(16, it) } ?: bindString(16, null)
            bindString(17, track.downloadState.name)
            bindString(18, syncedAt)
        }
    }

    override fun upsertPlaylist(playlist: Playlist, syncedAt: String) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO playlist(id, name, description, is_smart, rules, m3u_path, is_m3u_imported, updated_at, synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9,
        ) {
            bindLong(0, playlist.id)
            bindString(1, playlist.name)
            playlist.description?.let { bindString(2, it) } ?: bindString(2, null)
            bindLong(3, if (playlist.isSmart) 1L else 0L)
            playlist.rules?.let { bindString(4, it) } ?: bindString(4, null)
            playlist.m3uPath?.let { bindString(5, it) } ?: bindString(5, null)
            bindLong(6, if (playlist.isM3uImported) 1L else 0L)
            playlist.updatedAt?.let { bindString(7, it) } ?: bindString(7, null)
            bindString(8, syncedAt)
        }
    }

    override fun upsertPlaylistTrack(playlistTrack: PlaylistTrack, syncedAt: String) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO playlist_track(playlist_id, track_id, position, added_at) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindLong(0, playlistTrack.playlistId)
            bindLong(1, playlistTrack.trackId)
            bindLong(2, playlistTrack.position.toLong())
            playlistTrack.addedAt?.let { bindString(3, it) } ?: bindString(3, null)
        }
    }

    override fun deleteAllArtists() { driver.execute(null, "DELETE FROM artist", 0) }
    override fun deleteAllAlbums() { driver.execute(null, "DELETE FROM album", 0) }
    override fun deleteAllTracks() { driver.execute(null, "DELETE FROM track", 0) }
    override fun deleteAllPlaylists() { driver.execute(null, "DELETE FROM playlist", 0) }
    override fun deleteAllPlaylistTracks() { driver.execute(null, "DELETE FROM playlist_track", 0) }

    override fun updateTrackDownload(
        trackId: Long,
        localPath: String?,
        state: com.wtm.musicmanager.domain.model.DownloadState,
    ) {
        driver.execute(
            null,
            "UPDATE track SET local_path = ?, download_state = ? WHERE id = ?",
            0,
        ) {
            bindString(0, localPath)
            bindString(1, state.name)
            bindLong(2, trackId)
        }
    }

    fun count(table: String): Long =
        driver.executeQuery(0, "SELECT COUNT(*) FROM $table", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        }, 0).value

    fun selectArtists(): List<Artist> =
        driver.executeQuery(0, "SELECT id, name, musicbrainz_id, image_path, updated_at FROM artist", { cursor ->
            QueryResult.Value(buildList {
                while (cursor.next().value) {
                    add(
                        Artist(
                            id = cursor.getLong(0) ?: 0L,
                            name = cursor.getString(1) ?: "",
                            musicbrainzId = cursor.getString(2),
                            imagePath = cursor.getString(3),
                            updatedAt = cursor.getString(4),
                        )
                    )
                }
            })
        }, 0).value
}
