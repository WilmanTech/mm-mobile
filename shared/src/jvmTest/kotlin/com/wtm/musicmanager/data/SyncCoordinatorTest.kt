package com.wtm.musicmanager.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.wtm.musicmanager.domain.model.Album
import com.wtm.musicmanager.domain.model.Artist
import com.wtm.musicmanager.domain.model.Playlist
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
                album_count INTEGER NOT NULL DEFAULT 0,
                track_count INTEGER NOT NULL DEFAULT 0,
                cover_url TEXT,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE album (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                artist_id INTEGER NOT NULL,
                artist_name TEXT NOT NULL,
                year INTEGER,
                track_count INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER,
                cover_url TEXT,
                cover_path TEXT,
                is_downloaded INTEGER NOT NULL DEFAULT 0,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE track (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                album_id INTEGER NOT NULL,
                album_title TEXT NOT NULL,
                artist_id INTEGER NOT NULL,
                artist_name TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                track_number INTEGER,
                bitrate INTEGER,
                codec TEXT,
                stream_url TEXT,
                local_path TEXT,
                download_state TEXT NOT NULL DEFAULT 'NotDownloaded',
                synced_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE playlist (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                track_count INTEGER NOT NULL DEFAULT 0,
                cover_url TEXT,
                is_smart INTEGER NOT NULL DEFAULT 0,
                is_m3u_imported INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE playlist_track (
                playlist_id INTEGER NOT NULL,
                track_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
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
    fun `syncFull upserts all five lists and stores server time`() = runTest {
        val payload = """
            {
              "artists": [{"id":1,"name":"Aesop Rock","album_count":1,"track_count":1,"cover_url":null,"updated_at":100}],
              "albums":  [{"id":10,"title":"None Shall Pass","artist_id":1,"artist_name":"Aesop Rock","year":2007,"track_count":1,"duration_ms":3600000,"cover_url":null,"updated_at":100}],
              "tracks":  [{"id":100,"title":"None Shall Pass","album_id":10,"album_title":"None Shall Pass","artist_id":1,"artist_name":"Aesop Rock","duration_ms":210000,"track_number":1,"bitrate":320,"codec":"mp3","stream_url":null,"updated_at":100}],
              "playlists":[{"id":1000,"name":"Best","track_count":1,"cover_url":null,"is_smart":false,"is_m3u_imported":false,"updated_at":100}],
              "playlist_tracks":[{"playlist_id":1000,"track_id":100,"position":0,"added_at":1234}],
              "server_time": 1700000000000
            }
        """.trimIndent()

        val coordinator = SyncCoordinator(mockApi(payload), queries, now = { 1700000001000L })
        val state = coordinator.syncFull()

        assertTrue(state is SyncState.Completed, "expected Completed, got $state")
        assertEquals(1, (state as SyncState.Completed).tracks)
        assertEquals(1700000000000L, coordinator.lastServerTime.value)

        assertEquals(1, queries.count("artist"))
        assertEquals(1, queries.count("album"))
        assertEquals(1, queries.count("track"))
        assertEquals(1, queries.count("playlist"))
        assertEquals(1, queries.count("playlist_track"))
    }

    @Test
    fun `syncFull wipes previous rows before applying new payload`() = runTest {
        val firstPayload = """
            {
              "artists": [{"id":1,"name":"Old Artist","album_count":0,"track_count":0,"cover_url":null,"updated_at":100}],
              "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [],
              "server_time": 1000
            }
        """.trimIndent()
        val secondPayload = """
            {
              "artists": [{"id":2,"name":"New Artist","album_count":0,"track_count":0,"cover_url":null,"updated_at":200}],
              "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [],
              "server_time": 2000
            }
        """.trimIndent()

        val coordinator = SyncCoordinator(mockApi(firstPayload), queries, now = { 100L })
        coordinator.syncFull()
        assertEquals(1, queries.count("artist"))

        val second = SyncCoordinator(mockApi(secondPayload), queries, now = { 200L })
        second.syncFull()

        assertEquals(1, queries.count("artist"))
        val rows = queries.selectArtists()
        assertEquals("New Artist", rows.first().name)
    }

    @Test
    fun `syncChanges sends X-Since and merges without wiping`() = runTest {
        val initialPayload = """
            {
              "artists": [{"id":1,"name":"A","album_count":0,"track_count":0,"cover_url":null,"updated_at":100}],
              "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [],
              "server_time": 1000
            }
        """.trimIndent()
        val deltaPayload = """
            {
              "artists": [{"id":2,"name":"B","album_count":0,"track_count":0,"cover_url":null,"updated_at":200}],
              "albums": [], "tracks": [], "playlists": [], "playlist_tracks": [],
              "server_time": 2000,
              "has_more": false
            }
        """.trimIndent()

        val engine = MockEngine { request ->
            val since = request.headers["X-Since"]
            when (request.url.encodedPath) {
                "/api/v1/sync/full" -> respond(initialPayload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                "/api/v1/sync/changes" -> {
                    assertEquals("1000", since)
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
        val coordinator = SyncCoordinator(api, queries, now = { 1L })

        coordinator.syncFull()
        assertEquals(1, queries.count("artist"))

        coordinator.syncChanges()
        assertEquals(2, queries.count("artist"))
        assertEquals(2000L, coordinator.lastServerTime.value)
    }

    @Test
    fun `syncFull handles empty payload gracefully`() = runTest {
        val emptyPayload = """{"server_time": 1700000000000}"""
        val coordinator = SyncCoordinator(mockApi(emptyPayload), queries, now = { 1L })
        val state = coordinator.syncFull()

        assertTrue(state is SyncState.Completed)
        assertEquals(0, (state as SyncState.Completed).artists)
        assertEquals(1700000000000L, coordinator.lastServerTime.value)
    }
}

private class TestSyncQueries(private val driver: SqlDriver) : SyncUpsertQueries {

    private val txnLock = Any()

    override fun transaction(block: () -> Unit) {
        synchronized(txnLock) { block() }
    }

    override fun upsertArtist(artist: Artist, syncedAt: Long) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO artist(id, name, album_count, track_count, cover_url, synced_at) VALUES (?, ?, ?, ?, ?, ?)",
            6,
        ) {
            bindLong(0, artist.id)
            bindString(1, artist.name)
            bindLong(2, artist.albumCount.toLong())
            bindLong(3, artist.trackCount.toLong())
            artist.coverUrl?.let { bindString(4, it) } ?: bindString(4, null)
            bindLong(5, syncedAt)
        }
    }

    override fun upsertAlbum(album: Album, syncedAt: Long) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO album(id, title, artist_id, artist_name, year, track_count, duration_ms, cover_url, cover_path, is_downloaded, synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            11,
        ) {
            bindLong(0, album.id)
            bindString(1, album.title)
            bindLong(2, album.artistId)
            bindString(3, album.artistName)
            album.year?.let { bindLong(4, it.toLong()) } ?: bindLong(4, null)
            bindLong(5, album.trackCount.toLong())
            album.durationMs?.let { bindLong(6, it) } ?: bindLong(6, null)
            album.coverUrl?.let { bindString(7, it) } ?: bindString(7, null)
            album.coverPath?.let { bindString(8, it) } ?: bindString(8, null)
            bindLong(9, if (album.isDownloaded) 1L else 0L)
            bindLong(10, syncedAt)
        }
    }

    override fun upsertTrack(track: Track, syncedAt: Long) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO track(id, title, album_id, album_title, artist_id, artist_name, duration_ms, track_number, bitrate, codec, stream_url, local_path, download_state, synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            14,
        ) {
            bindLong(0, track.id)
            bindString(1, track.title)
            bindLong(2, track.albumId)
            bindString(3, track.albumTitle)
            bindLong(4, track.artistId)
            bindString(5, track.artistName)
            bindLong(6, track.durationMs)
            track.trackNumber?.let { bindLong(7, it.toLong()) } ?: bindLong(7, null)
            track.bitrate?.let { bindLong(8, it.toLong()) } ?: bindLong(8, null)
            track.codec?.let { bindString(9, it) } ?: bindString(9, null)
            track.streamUrl?.let { bindString(10, it) } ?: bindString(10, null)
            track.localPath?.let { bindString(11, it) } ?: bindString(11, null)
            bindString(12, track.downloadState.name)
            bindLong(13, syncedAt)
        }
    }

    override fun upsertPlaylist(playlist: Playlist, syncedAt: Long) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO playlist(id, name, description, track_count, cover_url, is_smart, is_m3u_imported, updated_at, synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9,
        ) {
            bindLong(0, playlist.id)
            bindString(1, playlist.name)
            playlist.description?.let { bindString(2, it) } ?: bindString(2, null)
            bindLong(3, playlist.trackCount.toLong())
            playlist.coverUrl?.let { bindString(4, it) } ?: bindString(4, null)
            bindLong(5, if (playlist.isSmart) 1L else 0L)
            bindLong(6, if (playlist.isM3uImported) 1L else 0L)
            playlist.updatedAt?.let { bindLong(7, it) } ?: bindLong(7, null)
            bindLong(8, syncedAt)
        }
    }

    override fun upsertPlaylistTrack(playlistId: Long, trackId: Long, position: Int, addedAt: Long) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO playlist_track(playlist_id, track_id, position, added_at) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindLong(0, playlistId)
            bindLong(1, trackId)
            bindLong(2, position.toLong())
            bindLong(3, addedAt)
        }
    }

    override fun deleteAllArtists() { driver.execute(null, "DELETE FROM artist", 0) }
    override fun deleteAllAlbums() { driver.execute(null, "DELETE FROM album", 0) }
    override fun deleteAllTracks() { driver.execute(null, "DELETE FROM track", 0) }
    override fun deleteAllPlaylists() { driver.execute(null, "DELETE FROM playlist", 0) }
    override fun deleteAllPlaylistTracks() { driver.execute(null, "DELETE FROM playlist_track", 0) }

    fun count(table: String): Long =
        driver.executeQuery(0, "SELECT COUNT(*) FROM $table", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        }, 0).value

    fun selectArtists(): List<Artist> =
        driver.executeQuery(0, "SELECT id, name, album_count, track_count, cover_url FROM artist", { cursor ->
            QueryResult.Value(buildList {
                while (cursor.next().value) {
                    add(
                        Artist(
                            id = cursor.getLong(0) ?: 0L,
                            name = cursor.getString(1) ?: "",
                            albumCount = (cursor.getLong(2) ?: 0L).toInt(),
                            trackCount = (cursor.getLong(3) ?: 0L).toInt(),
                            coverUrl = cursor.getString(4),
                        )
                    )
                }
            })
        }, 0).value
}
