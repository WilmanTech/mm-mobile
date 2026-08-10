package com.wtm.musicmanager.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.data.LibraryQuery
import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.data.SyncState
import com.wtm.musicmanager.data.SyncTrigger
import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Artist
import com.wtm.musicmanager.db.Playlist
import com.wtm.musicmanager.db.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reactive UI state for [SearchScreen]. One search bar, four tabs.
 *
 * Tab results share the same debounced query — the user expects typing
 * to update all four result sets in lockstep (Spotify / Apple Music
 * behaviour). Each tab flow is observed independently by the
 * [combine] so a single Tracks query doesn't block the Albums tab
 * from re-rendering.
 */
data class SearchUiState(
    val query: String = "",
    val activeTab: SearchTab = SearchTab.Tracks,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastError: String? = null,
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

enum class SearchTab(val label: String) {
    Tracks("Canciones"),
    Albums("Álbumes"),
    Artists("Artistas"),
    Playlists("Playlists"),
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val syncCoordinator: SyncTrigger,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _activeTab = MutableStateFlow(SearchTab.Tracks)
    private val _isRefreshing = MutableStateFlow(false)

    /**
     * Tracks tab — debounced 150ms (same as LibraryScreen so behaviour
     * matches across screens).
     */
    private val tracksFlow = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { q ->
            repository.observeTracks(LibraryQuery(search = q))
        }

    private val albumsFlow = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { q ->
            repository.observeAlbums(LibraryQuery(search = q))
        }

    private val artistsFlow = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { q ->
            repository.searchArtists(q)
        }

    private val playlistsFlow = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { q ->
            repository.observePlaylists(LibraryQuery(search = q))
        }

    val state: StateFlow<SearchUiState> = combine(
        _query,
        _activeTab,
        _isRefreshing,
        tracksFlow,
        albumsFlow,
        artistsFlow,
        playlistsFlow,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        SearchUiState(
            query = args[0] as String,
            activeTab = args[1] as SearchTab,
            isRefreshing = args[2] as Boolean,
            tracks = args[3] as List<Track>,
            albums = args[4] as List<Album>,
            artists = args[5] as List<Artist>,
            playlists = args[6] as List<Playlist>,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SearchUiState(),
    )

    fun onQueryChange(text: String) {
        _query.value = text
    }

    fun onTabSelected(tab: SearchTab) {
        _activeTab.value = tab
    }

    fun onClear() {
        _query.value = ""
    }

    /**
     * Pull-to-refresh: trigger an incremental sync. We surface the
     * [SyncCoordinator.state] through [SearchUiState.isRefreshing] so
     * the SwipeRefresh indicator stays up until the sync actually
     * completes (or fails). One sync in flight at a time — the
     * coordinator already enforces that, so we just observe.
     */
    fun onRefresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                syncCoordinator.syncChanges()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    init {
        // Mirror the coordinator's state into isRefreshing for cases where
        // the user pulls but sync was already in flight (a no-op in the
        // coordinator, so this just keeps the indicator from sticking).
        viewModelScope.launch {
            syncCoordinator.state.collect { s ->
                if (s !is SyncState.Running) {
                    _isRefreshing.update { false }
                }
            }
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS: Long = 150L
    }
}
