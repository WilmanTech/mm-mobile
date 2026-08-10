package com.wtm.musicmanager.ui.search

import com.wtm.musicmanager.data.LibraryQuery
import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.data.SyncState
import com.wtm.musicmanager.data.SyncTrigger
import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Artist
import com.wtm.musicmanager.db.Playlist
import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    private lateinit var fakeRepository: FakeSearchRepository
    private lateinit var fakeSync: FakeSyncCoordinator
    private lateinit var viewModel: SearchViewModel
    private lateinit var collectorJob: kotlinx.coroutines.Job

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSearchRepository()
        fakeSync = FakeSyncCoordinator()
        viewModel = SearchViewModel(fakeRepository, fakeSync)
        collectorJob = testScope.launch {
            viewModel.state.collect { /* swallow */ }
        }
    }

    @AfterTest
    fun teardown() {
        collectorJob.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty query and Tracks tab selected`() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals("", state.query)
        assertEquals(SearchTab.Tracks, state.activeTab)
        // The fake repository pre-seeds 3 tracks / 3 artists / 2 albums
        // / 2 playlists. With an empty query, all four tabs should show
        // the full set immediately (no search applied).
        assertEquals(3, state.tracks.size)
        assertEquals(3, state.artists.size)
        assertEquals(2, state.albums.size)
        assertEquals(2, state.playlists.size)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `onQueryChange updates the search query slot`() = runTest(testDispatcher) {
        viewModel.onQueryChange("queen")
        advanceUntilIdle()
        assertEquals("queen", viewModel.state.value.query)
        // The debounce hasn't elapsed yet at this point in the test, but
        // the query slot itself is updated synchronously.
    }

    @Test
    fun `debounce fires and the tracks flow re-emits with the search applied`() = runTest(testDispatcher) {
        viewModel.onQueryChange("bohem")
        // Wait past the debounce (150ms in production, but we use the same
        // constant for the test — advanceTimeBy just needs to be > it).
        advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals("bohem", viewModel.state.value.query)
        // Tracks should be filtered down to "Bohemian" / "Bohemian Rhapsody"
        // — both seeded into the fake repo.
        val tracks = viewModel.state.value.tracks
        assertEquals(2, tracks.size)
        assertEquals(setOf("Bohemian", "Bohemian Rhapsody"), tracks.map { it.title }.toSet())
    }

    @Test
    fun `onTabSelected switches the active tab`() = runTest(testDispatcher) {
        viewModel.onTabSelected(SearchTab.Albums)
        advanceUntilIdle()
        assertEquals(SearchTab.Albums, viewModel.state.value.activeTab)
    }

    @Test
    fun `onClear resets the query`() = runTest(testDispatcher) {
        viewModel.onQueryChange("queen")
        advanceUntilIdle()
        viewModel.onClear()
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.query)
    }

    @Test
    fun `onRefresh sets isRefreshing then clears it after sync`() = runTest(testDispatcher) {
        assertFalse(viewModel.state.value.isRefreshing)

        viewModel.onRefresh()
        advanceUntilIdle()

        // After the fake sync completes, isRefreshing should be back to false.
        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals(1, fakeSync.syncChangesCallCount)
    }

    @Test
    fun `onRefresh while already refreshing is a no-op`() = runTest(testDispatcher) {
        fakeSync.holdOpen = true

        viewModel.onRefresh()
        // Yield once so the inner coroutine has time to flip _isRefreshing
        // and reach the suspend point inside fakeSync.syncChanges.
        advanceUntilIdle()

        // While the fake is "in flight" (held on the gate), the indicator
        // should be visible.
        assertEquals(true, viewModel.state.value.isRefreshing)

        viewModel.onRefresh()
        advanceUntilIdle()
        // Second call shouldn't have triggered another syncChanges.
        assertEquals(1, fakeSync.syncChangesCallCount)

        // Release the in-flight sync so the first call can complete and
        // the teardown can dispose the VM cleanly.
        fakeSync.release()
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isRefreshing)
    }
}

// =====================================================================
// Test doubles
// =====================================================================

/**
 * A FakeSearchRepository that returns deterministic seed data for the
 * four SearchScreen tabs. The tracks/albums/artists/playlists lists
 * are kept in MutableStateFlows so debounce-driven re-queries land
 * on real emissions.
 */
private class FakeSearchRepository : LibraryRepository {
    private val tracks = MutableStateFlow(
        listOf(
            fakeTrack(1, "Bohemian Rhapsody", 1, "Queen", 1, "A Night at the Opera"),
            fakeTrack(2, "Stairway to Heaven", 2, "Led Zeppelin", 2, "Led Zeppelin IV"),
            fakeTrack(3, "Bohemian", 3, "Artist Three", 3, "Album Three"),
        )
    )
    private val artists = MutableStateFlow(
        listOf(
            Artist(id = 1, name = "Queen", album_count = 1L, track_count = 1L, cover_url = null, synced_at = 1L),
            Artist(id = 2, name = "Led Zeppelin", album_count = 1L, track_count = 1L, cover_url = null, synced_at = 1L),
            Artist(id = 3, name = "Artist Three", album_count = 1L, track_count = 1L, cover_url = null, synced_at = 1L),
        )
    )
    private val albums = MutableStateFlow(
        listOf(
            Album(1, "A Night at the Opera", 1, "Queen", 1975L, 1, 180_000L, null, null, 0L, 1L),
            Album(2, "Led Zeppelin IV", 2, "Led Zeppelin", 1971L, 1, 180_000L, null, null, 0L, 1L),
        )
    )
    private val playlists = MutableStateFlow(
        listOf(
            Playlist(1, "Rock Classics", "Best of rock", 0L, null, 0L, 0L, null, 1L),
            Playlist(2, "Favorites", "My favorites", 0L, null, 0L, 0L, null, 1L),
        )
    )

    override fun observeTracks(query: LibraryQuery): Flow<List<Track>> {
        val filtered = if (query.search.isBlank()) tracks.value
        else tracks.value.filter {
            it.title.contains(query.search, ignoreCase = true) ||
                it.album_title.contains(query.search, ignoreCase = true) ||
                it.artist_name.contains(query.search, ignoreCase = true)
        }
        return MutableStateFlow(filtered).asStateFlow()
    }

    override fun observeArtists(): Flow<List<Artist>> = artists.asStateFlow()

    override fun observeAlbumsByArtist(artistId: Long): Flow<List<Album>> =
        MutableStateFlow<List<Album>>(emptyList()).asStateFlow()

    override fun observeTrackCount(): Flow<Long> = MutableStateFlow(tracks.value.size.toLong()).asStateFlow()

    override fun observeAlbums(query: LibraryQuery): Flow<List<Album>> {
        val filtered = if (query.search.isBlank()) albums.value
        else albums.value.filter {
            it.title.contains(query.search, ignoreCase = true) ||
                it.artist_name.contains(query.search, ignoreCase = true)
        }
        return MutableStateFlow(filtered).asStateFlow()
    }

    override fun observePlaylists(query: LibraryQuery): Flow<List<Playlist>> {
        val filtered = if (query.search.isBlank()) playlists.value
        else playlists.value.filter {
            it.name.contains(query.search, ignoreCase = true) ||
                (it.description?.contains(query.search, ignoreCase = true) == true)
        }
        return MutableStateFlow(filtered).asStateFlow()
    }

    override fun searchArtists(query: String): Flow<List<Artist>> {
        val filtered = if (query.isBlank()) artists.value
        else artists.value.filter { it.name.contains(query, ignoreCase = true) }
        return MutableStateFlow(filtered).asStateFlow()
    }

    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> {
        // Playlist 1 contains tracks 1+2, playlist 2 contains track 3, in that order.
        val all = tracks.value
        val byPlaylist = when (playlistId) {
            1L -> listOf(all[0], all[1])
            2L -> listOf(all[2])
            else -> emptyList()
        }
        return MutableStateFlow(byPlaylist).asStateFlow()
    }

    override suspend fun trackById(id: Long): Track? = tracks.value.firstOrNull { it.id == id }
    override suspend fun artistById(id: Long): Artist? = artists.value.firstOrNull { it.id == id }
    override suspend fun albumById(id: Long): Album? = albums.value.firstOrNull { it.id == id }
    override suspend fun playlistById(id: Long): Playlist? = playlists.value.firstOrNull { it.id == id }
}

private class FakeSyncCoordinator : SyncTrigger {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)

    /**
     * When set, [syncChanges] sets the state to Running and suspends
     * on the [heldGate] until [release] is called. Used to test that
     * onRefresh() correctly sets isRefreshing=true and that a second
     * onRefresh() is a no-op.
     */
    @Volatile var holdOpen: Boolean = false
    var syncChangesCallCount: Int = 0
    private val heldGate = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun release() {
        heldGate.complete(Unit)
    }

    override val state = _state.asStateFlow()
    override val lastServerTime = MutableStateFlow<String?>(null).asStateFlow()

    override suspend fun syncChanges(): SyncState {
        syncChangesCallCount += 1
        if (holdOpen) {
            _state.value = SyncState.Running(phase = com.wtm.musicmanager.data.SyncPhase.Changes)
            heldGate.await()
        }
        _state.value = SyncState.Idle
        return SyncState.Idle
    }

    override suspend fun syncFull(): SyncState = SyncState.Idle
}

private fun fakeTrack(
    id: Long,
    title: String,
    artistId: Long,
    artistName: String,
    albumId: Long,
    albumTitle: String,
): Track = Track(
    id = id,
    title = title,
    album_id = albumId,
    album_title = albumTitle,
    artist_id = artistId,
    artist_name = artistName,
    duration_ms = 180_000L,
    track_number = 1L,
    bitrate = 320L,
    codec = "mp3",
    stream_url = null,
    local_path = null,
    download_state = "NotDownloaded",
    synced_at = 1L,
)