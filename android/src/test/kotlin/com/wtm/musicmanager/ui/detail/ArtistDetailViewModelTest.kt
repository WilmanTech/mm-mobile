package com.wtm.musicmanager.ui.detail

import androidx.lifecycle.SavedStateHandle
import com.wtm.musicmanager.data.LibraryQuery
import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Artist
import com.wtm.musicmanager.db.Playlist
import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// =====================================================================
// Shared test fakes / helpers
// =====================================================================

internal open class FakeDetailRepository : LibraryRepository {
    var album: Album? = null
    var artist: Artist? = null
    var playlist: Playlist? = null
    val tracksForAlbum: MutableMap<Long, List<Track>> = mutableMapOf()
    val tracksForArtist: MutableMap<Long, List<Track>> = mutableMapOf()
    val albumsForArtist: MutableMap<Long, List<Album>> = mutableMapOf()
    val tracksForPlaylist: MutableMap<Long, List<Track>> = mutableMapOf()

    override fun observeTracks(query: LibraryQuery): Flow<List<Track>> {
        return when {
            query.albumId != null -> MutableStateFlow(
                tracksForAlbum[query.albumId as Long] ?: emptyList()
            ).asStateFlow()
            query.artistId != null -> MutableStateFlow(
                tracksForArtist[query.artistId as Long] ?: emptyList()
            ).asStateFlow()
            else -> MutableStateFlow<List<Track>>(emptyList()).asStateFlow()
        }
    }

    override fun observeArtists(): Flow<List<Artist>> =
        MutableStateFlow<List<Artist>>(emptyList()).asStateFlow()
    override fun observeAlbumsByArtist(artistId: Long): Flow<List<Album>> =
        MutableStateFlow(albumsForArtist[artistId] ?: emptyList()).asStateFlow()
    override fun observeTrackCount(): Flow<Long> = MutableStateFlow(0L).asStateFlow()
    override fun observeAlbums(query: LibraryQuery): Flow<List<Album>> =
        MutableStateFlow<List<Album>>(emptyList()).asStateFlow()
    override fun observePlaylists(query: LibraryQuery): Flow<List<Playlist>> =
        MutableStateFlow<List<Playlist>>(emptyList()).asStateFlow()
    override fun searchArtists(query: String): Flow<List<Artist>> =
        MutableStateFlow<List<Artist>>(emptyList()).asStateFlow()
    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        MutableStateFlow(tracksForPlaylist[playlistId] ?: emptyList()).asStateFlow()

    override suspend fun trackById(id: Long): Track? = null
    override suspend fun artistById(id: Long): Artist? = artist
    override suspend fun albumById(id: Long): Album? = album
    override suspend fun playlistById(id: Long): Playlist? = playlist
}

internal fun fakeAlbum(
    id: Long,
    title: String,
    artistName: String = "Test Artist",
    year: Long? = null,
): Album = Album(
    id = id, title = title, artist_id = 1L, artist_name = artistName,
    year = year, track_count = 0L, duration_ms = 0L, cover_url = null,
    cover_path = null, is_downloaded = 0L, synced_at = 1L,
)

internal fun fakeTrack(
    id: Long,
    title: String,
    albumId: Long = 1L,
    albumTitle: String = "Test Album",
    artistId: Long = 1L,
    artistName: String = "Test Artist",
): Track = Track(
    id = id, title = title, album_id = albumId, album_title = albumTitle,
    artist_id = artistId, artist_name = artistName, duration_ms = 180_000L,
    track_number = 1L, bitrate = 320L, codec = "mp3", stream_url = null,
    local_path = null, download_state = "NotDownloaded", synced_at = 1L,
)

internal fun fakeArtist(id: Long, name: String, albumCount: Long = 0, trackCount: Long = 0): Artist =
    Artist(id = id, name = name, album_count = albumCount, track_count = trackCount, cover_url = null, synced_at = 1L)

internal fun fakePlaylist(id: Long, name: String, description: String? = null, isSmart: Long = 0L): Playlist =
    Playlist(
        id = id, name = name, description = description, track_count = 0L,
        cover_url = null, is_smart = isSmart, is_m3u_imported = 0L, updated_at = null, synced_at = 1L,
    )

// =====================================================================
// AlbumDetailViewModel
// =====================================================================

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    @BeforeTest
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `loads album metadata and tracks by id`() = runTest(testDispatcher) {
        val fake = FakeDetailRepository().apply {
            album = fakeAlbum(id = 42, title = "A Night at the Opera", artistName = "Queen", year = 1975L)
            tracksForAlbum[42L] = listOf(
                fakeTrack(id = 100, title = "Bohemian Rhapsody", albumId = 42),
                fakeTrack(id = 101, title = "Love of My Life", albumId = 42),
            )
        }
        val vm = AlbumDetailViewModel(
            repository = fake,
            savedStateHandle = SavedStateHandle(mapOf(AlbumDetailViewModel.ARG_ALBUM_ID to 42L)),
        )
        val job = testScope.launch { vm.state.collect { /* swallow */ } }
        try {
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals("A Night at the Opera", s.album?.title)
            assertEquals("Queen", s.album?.artist_name)
            assertEquals(1975L, s.album?.year)
            assertEquals(2, s.tracks.size)
            assertFalse(s.isLoading)
            assertFalse(s.notFound)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `notFound is true when albumById returns null`() = runTest(testDispatcher) {
        val fake = FakeDetailRepository()
        val vm = AlbumDetailViewModel(
            repository = fake,
            savedStateHandle = SavedStateHandle(mapOf(AlbumDetailViewModel.ARG_ALBUM_ID to 999L)),
        )
        val job = testScope.launch { vm.state.collect { /* swallow */ } }
        try {
            advanceUntilIdle()
            val s = vm.state.value
            assertNull(s.album)
            assertTrue(s.notFound)
            assertEquals(0, s.tracks.size)
        } finally {
            job.cancel()
        }
    }
}

// =====================================================================
// ArtistDetailViewModel
// =====================================================================

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    @BeforeTest
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `loads artist metadata plus albums and tracks`() = runTest(testDispatcher) {
        val fake = FakeDetailRepository().apply {
            artist = fakeArtist(5, "Queen", albumCount = 1, trackCount = 1)
            albumsForArtist[5L] = listOf(fakeAlbum(10, "A Night at the Opera", artistName = "Queen"))
            tracksForArtist[5L] = listOf(fakeTrack(100, "Bohemian Rhapsody", albumId = 10))
        }
        val vm = ArtistDetailViewModel(
            repository = fake,
            savedStateHandle = SavedStateHandle(mapOf(ArtistDetailViewModel.ARG_ARTIST_ID to 5L)),
        )
        val job = testScope.launch { vm.state.collect { /* swallow */ } }
        try {
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals("Queen", s.artist?.name)
            assertEquals(1, s.albums.size)
            assertEquals("A Night at the Opera", s.albums[0].title)
            assertEquals(1, s.tracks.size)
            assertFalse(s.isLoading)
            assertFalse(s.notFound)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `notFound is true when artistById returns null`() = runTest(testDispatcher) {
        val fake = FakeDetailRepository()
        val vm = ArtistDetailViewModel(
            repository = fake,
            savedStateHandle = SavedStateHandle(mapOf(ArtistDetailViewModel.ARG_ARTIST_ID to 999L)),
        )
        val job = testScope.launch { vm.state.collect { /* swallow */ } }
        try {
            advanceUntilIdle()
            val s = vm.state.value
            assertNull(s.artist)
            assertTrue(s.notFound)
        } finally {
            job.cancel()
        }
    }
}

// =====================================================================
// PlaylistDetailViewModel
// =====================================================================

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    @BeforeTest
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `loads playlist metadata and tracks by id`() = runTest(testDispatcher) {
        val fake = FakeDetailRepository().apply {
            playlist = fakePlaylist(3, "Road Trip", description = "Long drive", isSmart = 1L)
            tracksForPlaylist[3L] = listOf(
                fakeTrack(100, "Highway Star", albumId = 1, albumTitle = "Machine Head"),
                fakeTrack(101, "Smoke on the Water", albumId = 1, albumTitle = "Machine Head"),
            )
        }
        val vm = PlaylistDetailViewModel(
            repository = fake,
            savedStateHandle = SavedStateHandle(mapOf(PlaylistDetailViewModel.ARG_PLAYLIST_ID to 3L)),
        )
        val job = testScope.launch { vm.state.collect { /* swallow */ } }
        try {
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals("Road Trip", s.playlist?.name)
            assertEquals("Long drive", s.playlist?.description)
            assertEquals(1L, s.playlist?.is_smart)
            assertEquals(2, s.tracks.size)
            assertFalse(s.isLoading)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `notFound is true when playlistById returns null`() = runTest(testDispatcher) {
        val fake = FakeDetailRepository()
        val vm = PlaylistDetailViewModel(
            repository = fake,
            savedStateHandle = SavedStateHandle(mapOf(PlaylistDetailViewModel.ARG_PLAYLIST_ID to 999L)),
        )
        val job = testScope.launch { vm.state.collect { /* swallow */ } }
        try {
            advanceUntilIdle()
            val s = vm.state.value
            assertNull(s.playlist)
            assertTrue(s.notFound)
        } finally {
            job.cancel()
        }
    }
}
