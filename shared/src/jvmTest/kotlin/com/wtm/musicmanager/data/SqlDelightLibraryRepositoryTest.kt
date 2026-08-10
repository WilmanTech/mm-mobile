package com.wtm.musicmanager.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.wtm.musicmanager.db.MusicManagerDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SqlDelightLibraryRepositoryTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: MusicManagerDatabase
    private lateinit var repository: SqlDelightLibraryRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        // Mirror the schema in 1.sqm for the tables the repo reads.
        listOf(
            """
            CREATE TABLE artist (
                id INTEGER NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                album_count INTEGER NOT NULL DEFAULT 0,
                track_count INTEGER NOT NULL DEFAULT 0,
                cover_url TEXT,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE album (
                id INTEGER NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                artist_id INTEGER NOT NULL,
                artist_name TEXT NOT NULL DEFAULT '',
                year INTEGER,
                track_count INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER,
                cover_url TEXT,
                cover_path TEXT,
                is_downloaded INTEGER NOT NULL DEFAULT 0,
                local_size_bytes INTEGER NOT NULL DEFAULT 0,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE track (
                id INTEGER NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                album_id INTEGER NOT NULL,
                album_title TEXT NOT NULL DEFAULT '',
                artist_id INTEGER NOT NULL,
                artist_name TEXT NOT NULL DEFAULT '',
                duration_ms INTEGER NOT NULL,
                track_number INTEGER,
                bitrate INTEGER,
                codec TEXT,
                stream_url TEXT,
                local_path TEXT,
                download_state TEXT NOT NULL DEFAULT 'NOT_DOWNLOADED',
                local_size_bytes INTEGER NOT NULL DEFAULT 0,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE playlist (
                id INTEGER NOT NULL PRIMARY KEY,
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
        db = MusicManagerDatabase(driver)
        repository = SqlDelightLibraryRepository(db)
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private fun seedTrack(
        id: Long,
        title: String,
        artistId: Long,
        artistName: String,
        albumId: Long,
        albumTitle: String,
        durationMs: Long = 180_000L,
    ) {
        db.queriesQueries.upsertArtist(
            id = artistId, name = artistName,
            album_count = 1L, track_count = 1L, cover_url = null, synced_at = 1L,
        )
        db.queriesQueries.upsertAlbum(
            id = albumId, title = albumTitle,
            artist_id = artistId, artist_name = artistName, year = 2024L,
            track_count = 1L, duration_ms = durationMs, cover_url = null,
            cover_path = null, is_downloaded = 0L, synced_at = 1L,
        )
        db.queriesQueries.upsertTrack(
            id = id, title = title, album_id = albumId, album_title = albumTitle,
            artist_id = artistId, artist_name = artistName,
            duration_ms = durationMs, track_number = 1L, bitrate = 320L,
            codec = "mp3", stream_url = null, local_path = null,
            download_state = "NotDownloaded", synced_at = 1L,
        )
    }

    private fun seedPlaylist(
        id: Long,
        name: String,
        description: String? = null,
    ) {
        db.queriesQueries.upsertPlaylist(
            id = id, name = name, description = description,
            track_count = 0L, cover_url = null, is_smart = 0L,
            is_m3u_imported = 0L, updated_at = 1L, synced_at = 1L,
        )
    }

    @Test
    fun `observeAllTracks returns all tracks when no query filter`() = runTest {
        seedTrack(1, "Bohemian Rhapsody", 1, "Queen", 1, "A Night at the Opera")
        seedTrack(2, "Stairway to Heaven", 2, "Led Zeppelin", 2, "Led Zeppelin IV")

        repository.observeTracks(LibraryQuery.Default).test {
            val tracks = awaitItem()
            assertEquals(2, tracks.size, "expected 2 tracks (no filter)")
            assertEquals(
                setOf("Bohemian Rhapsody", "Stairway to Heaven"),
                tracks.map { it.title }.toSet(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchTracks filters by title substring (case insensitive)`() = runTest {
        seedTrack(1, "Bohemian Rhapsody", 1, "Queen", 1, "A Night at the Opera")
        seedTrack(2, "Stairway to Heaven", 2, "Led Zeppelin", 2, "Led Zeppelin IV")
        seedTrack(3, "Bohemian", 3, "Artist Three", 3, "Album Three")

        repository.observeTracks(LibraryQuery(search = "bohem")).test {
            val tracks = awaitItem()
            assertEquals(2, tracks.size)
            assertTrue(tracks.all { it.title.contains("Bohemian") })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeTracksByArtist restricts to a single artist`() = runTest {
        seedTrack(1, "Bohemian Rhapsody", 1, "Queen", 1, "A Night at the Opera")
        seedTrack(2, "Stairway to Heaven", 2, "Led Zeppelin", 2, "Led Zeppelin IV")
        seedTrack(3, "We Will Rock You", 1, "Queen", 1, "A Night at the Opera")

        repository.observeTracks(LibraryQuery(artistId = 1)).test {
            val tracks = awaitItem()
            assertEquals(2, tracks.size)
            assertTrue(tracks.all { it.artist_id == 1L })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeTrackCount reports total after upsert`() = runTest {
        repository.observeTrackCount().test {
            // First emission is the initial empty count, before any seed
            assertEquals(0L, awaitItem())

            seedTrack(1, "Bohemian Rhapsody", 1, "Queen", 1, "A Night at the Opera")
            // After the first seed the table has 1 row
            assertEquals(1L, awaitItem())

            seedTrack(2, "Stairway to Heaven", 2, "Led Zeppelin", 2, "Led Zeppelin IV")
            // After the second seed the table has 2 rows
            assertEquals(2L, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `artistById returns null for unknown id`() = runTest {
        val artist = repository.artistById(999)
        assertNull(artist)
    }

    @Test
    fun `observeArtists returns inserted artists ordered by name (NOCASE)`() = runTest {
        db.queriesQueries.upsertArtist(
            id = 1, name = "Zeppelin",
            album_count = 0L, track_count = 0L, cover_url = null, synced_at = 1L,
        )
        db.queriesQueries.upsertArtist(
            id = 2, name = "abba",
            album_count = 0L, track_count = 0L, cover_url = null, synced_at = 1L,
        )

        repository.observeArtists().test {
            val artists = awaitItem()
            assertEquals(2, artists.size)
            // SQLite COLLATE NOCASE: 'abba' < 'Zeppelin' in case-insensitive order
            assertEquals("abba", artists[0].name)
            assertEquals("Zeppelin", artists[1].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =================================================================
    // Phase 2.1 — SearchScreen tab backing queries
    // =================================================================

    @Test
    fun `observeAlbums returns all albums when search is empty`() = runTest {
        seedTrack(1, "Bohemian Rhapsody", 1, "Queen", 1, "A Night at the Opera")
        seedTrack(2, "Stairway to Heaven", 2, "Led Zeppelin", 2, "Led Zeppelin IV")

        repository.observeAlbums(LibraryQuery.Default).test {
            val albums = awaitItem()
            assertEquals(2, albums.size)
            assertEquals(
                setOf("A Night at the Opera", "Led Zeppelin IV"),
                albums.map { it.title }.toSet(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchAlbums filters by title or artist_name substring`() = runTest {
        seedTrack(1, "Bohemian Rhapsody", 1, "Queen", 1, "A Night at the Opera")
        seedTrack(2, "Stairway to Heaven", 2, "Led Zeppelin", 2, "Led Zeppelin IV")

        repository.observeAlbums(LibraryQuery(search = "opera")).test {
            val albums = awaitItem()
            assertEquals(1, albums.size)
            assertEquals("A Night at the Opera", albums[0].title)
            cancelAndIgnoreRemainingEvents()
        }

        repository.observeAlbums(LibraryQuery(search = "zeppelin")).test {
            val albums = awaitItem()
            assertEquals(1, albums.size)
            // "zeppelin" is the artist_name on the second album
            assertEquals("Led Zeppelin IV", albums[0].title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observePlaylists returns all playlists when search is empty`() = runTest {
        seedPlaylist(1, "Rock Classics", "Best of rock")
        seedPlaylist(2, "Favorites", null)

        repository.observePlaylists(LibraryQuery.Default).test {
            val playlists = awaitItem()
            assertEquals(2, playlists.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchPlaylists filters by name or description`() = runTest {
        seedPlaylist(1, "Rock Classics", "Best of rock")
        seedPlaylist(2, "Favorites", null)
        seedPlaylist(3, "Indie Hits", "Indie rock curated")

        // Match by name
        repository.observePlaylists(LibraryQuery(search = "fav")).test {
            val playlists = awaitItem()
            assertEquals(1, playlists.size)
            assertEquals("Favorites", playlists[0].name)
            cancelAndIgnoreRemainingEvents()
        }

        // Match by description
        repository.observePlaylists(LibraryQuery(search = "indie")).test {
            val playlists = awaitItem()
            assertEquals(1, playlists.size)
            assertEquals("Indie Hits", playlists[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchArtists returns all artists when query is empty`() = runTest {
        db.queriesQueries.upsertArtist(
            id = 1, name = "Queen",
            album_count = 1L, track_count = 1L, cover_url = null, synced_at = 1L,
        )
        db.queriesQueries.upsertArtist(
            id = 2, name = "Led Zeppelin",
            album_count = 1L, track_count = 1L, cover_url = null, synced_at = 1L,
        )

        repository.searchArtists("").test {
            val artists = awaitItem()
            assertEquals(2, artists.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchArtists filters by name substring (case insensitive)`() = runTest {
        db.queriesQueries.upsertArtist(
            id = 1, name = "Queen",
            album_count = 1L, track_count = 1L, cover_url = null, synced_at = 1L,
        )
        db.queriesQueries.upsertArtist(
            id = 2, name = "Led Zeppelin",
            album_count = 1L, track_count = 1L, cover_url = null, synced_at = 1L,
        )

        repository.searchArtists("qu").test {
            val artists = awaitItem()
            assertEquals(1, artists.size)
            assertEquals("Queen", artists[0].name)
            cancelAndIgnoreRemainingEvents()
        }

        repository.searchArtists("ZEP").test {
            val artists = awaitItem()
            assertEquals(1, artists.size)
            assertEquals("Led Zeppelin", artists[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ===========================================================
    // Phase 3.C — Detail screens backing queries
    // ===========================================================

    @Test
    fun `observePlaylistTracks returns the playlist's tracks in order`() = runTest {
        db.queriesQueries.upsertPlaylist(
            id = 1, name = "Mixed", description = null,
            track_count = 0L, cover_url = null, is_smart = 0L,
            is_m3u_imported = 0L, updated_at = 1L, synced_at = 1L,
        )
        seedTrack(1, "First", 1, "A", 1, "Alb-1")
        seedTrack(2, "Second", 1, "A", 1, "Alb-1")
        seedTrack(3, "Third", 2, "B", 2, "Alb-2")
        // Position 1, 2, 3 — manually insert playlist_track rows.
        listOf(
            Triple(1L, 1L, 1),
            Triple(1L, 2L, 2),
            Triple(1L, 3L, 3),
        ).forEach { (pl, trackId, pos) ->
            db.queriesQueries.upsertPlaylistTrack(
                playlist_id = pl, track_id = trackId, position = pos.toLong(),
                added_at = 1L,
            )
        }

        repository.observePlaylistTracks(1L).test {
            val tracks = awaitItem()
            assertEquals(3, tracks.size, "expected all 3 tracks in playlist")
            // Order is by position ASC (SQL: ORDER BY pt.position ASC).
            assertEquals("First", tracks[0].title)
            assertEquals("Second", tracks[1].title)
            assertEquals("Third", tracks[2].title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observePlaylistTracks returns empty list for unknown playlist id`() = runTest {
        repository.observePlaylistTracks(999L).test {
            val tracks = awaitItem()
            assertEquals(0, tracks.size, "unknown playlist id returns empty list")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playlistById returns the playlist metadata`() = runTest {
        db.queriesQueries.upsertPlaylist(
            id = 7, name = "Road Trip", description = "Long drive music",
            track_count = 0L, cover_url = null, is_smart = 0L,
            is_m3u_imported = 0L, updated_at = 1L, synced_at = 1L,
        )

        val p = repository.playlistById(7)
        assertEquals(7L, p?.id)
        assertEquals("Road Trip", p?.name)
        assertEquals("Long drive music", p?.description)
    }

    @Test
    fun `playlistById returns null for unknown id`() = runTest {
        val p = repository.playlistById(999)
        assertNull(p)
    }
}
