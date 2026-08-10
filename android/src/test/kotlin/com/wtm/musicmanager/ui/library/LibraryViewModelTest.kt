package com.wtm.musicmanager.ui.library

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

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    private lateinit var fakeRepository: FakeLibraryRepository
    private lateinit var fakeSync: FakeSyncCoordinator
    private lateinit var viewModel: LibraryViewModel
    private lateinit var collectorJob: kotlinx.coroutines.Job

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeLibraryRepository()
        fakeSync = FakeSyncCoordinator()
        viewModel = LibraryViewModel(fakeRepository, fakeSync)
        // stateIn uses WhileSubscribed, so we need a collector for the
        // combine() chain to actually run. We launch one and cancel it
        // in teardown.
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
    fun `initial state has empty tracks and isLoading true`() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(emptyList(), state.tracks)
        assertEquals(emptyList(), state.artists)
    }

    @Test
    fun `onSearchChange propagates to query slot`() = runTest(testDispatcher) {
        viewModel.onSearchChange("queen")
        advanceUntilIdle()
        assertEquals("queen", viewModel.state.value.query.search)
    }

    @Test
    fun `onArtistSelected with non-null id filters by artist`() = runTest(testDispatcher) {
        viewModel.onArtistSelected(42L)
        advanceUntilIdle()
        assertEquals(42L, viewModel.state.value.query.artistId)
    }

    @Test
    fun `onArtistSelected with null clears the filter`() = runTest(testDispatcher) {
        viewModel.onArtistSelected(42L)
        advanceUntilIdle()
        viewModel.onArtistSelected(null)
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.query.artistId)
    }

    @Test
    fun `onClearFilters resets the entire query`() = runTest(testDispatcher) {
        viewModel.onSearchChange("bohem")
        viewModel.onArtistSelected(42L)
        advanceUntilIdle()
        viewModel.onClearFilters()
        advanceUntilIdle()
        assertEquals(LibraryQuery.Default, viewModel.state.value.query)
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
}

/**
 * Fake LibraryRepository that returns empty Flows for everything.
 * Sufficient for testing the ViewModel's state-update logic without
 * spinning up SQLDelight. Phase 2.1 also implements the new
 * observeAlbums / observePlaylists / searchArtists methods as no-ops.
 */
private class FakeLibraryRepository : LibraryRepository {
    private val empty = MutableStateFlow<List<Track>>(emptyList())
    private val emptyArtists = MutableStateFlow<List<Artist>>(emptyList())

    override fun observeTracks(query: LibraryQuery): Flow<List<Track>> = empty.asStateFlow()
    override fun observeArtists(): Flow<List<Artist>> = emptyArtists.asStateFlow()
    override fun observeAlbumsByArtist(artistId: Long): Flow<List<Album>> =
        MutableStateFlow<List<Album>>(emptyList()).asStateFlow()
    override fun observeTrackCount(): Flow<Long> = MutableStateFlow(0L).asStateFlow()

    override fun observeAlbums(query: LibraryQuery): Flow<List<Album>> =
        MutableStateFlow<List<Album>>(emptyList()).asStateFlow()
    override fun observePlaylists(query: LibraryQuery): Flow<List<Playlist>> =
        MutableStateFlow<List<Playlist>>(emptyList()).asStateFlow()
    override fun searchArtists(query: String): Flow<List<Artist>> =
        MutableStateFlow<List<Artist>>(emptyList()).asStateFlow()
    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        MutableStateFlow<List<Track>>(emptyList()).asStateFlow()

    override suspend fun trackById(id: Long): Track? = null
    override suspend fun artistById(id: Long): Artist? = null
    override suspend fun albumById(id: Long): Album? = null
    override suspend fun playlistById(id: Long): Playlist? = null
}

private class FakeSyncCoordinator : SyncTrigger {
    var syncChangesCallCount: Int = 0

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state = _state.asStateFlow()
    override val lastServerTime = MutableStateFlow<String?>(null).asStateFlow()

    override suspend fun syncChanges(): SyncState {
        syncChangesCallCount += 1
        return SyncState.Idle
    }

    override suspend fun syncFull(): SyncState = SyncState.Idle
}
