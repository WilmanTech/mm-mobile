package com.wtm.musicmanager.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.data.LibraryQuery
import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.data.SyncCoordinator
import com.wtm.musicmanager.data.SyncState
import com.wtm.musicmanager.db.Artist
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
 * Reactive UI state for [LibraryScreen]. One screen, three slots:
 * - [query]: the current search/filter (mutable, drives re-query)
 * - [tracks]: observed list of tracks from SQLDelight
 * - [artists]: sidebar list of available artists
 * - [isLoading]: sticky true while we have no data yet
 * - [isRefreshing]: sticky true while a sync is in flight (driven by
 *   PullToRefreshBox's indicator)
 * - [error]: last sync/network error to surface as a banner
 */
data class LibraryUiState(
    val query: LibraryQuery = LibraryQuery.Default,
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {

    private val _query = MutableStateFlow(LibraryQuery.Default)
    private val _isRefreshing = MutableStateFlow(false)

    private val _tracks = _query
        .debounce(150L) // avoid thrashing on every keystroke
        .flatMapLatest { repository.observeTracks(it) }

    val state: StateFlow<LibraryUiState> = combine(
        _query,
        _tracks,
        repository.observeArtists(),
        _isRefreshing,
    ) { query, tracks, artists, isRefreshing ->
        LibraryUiState(
            query = query,
            tracks = tracks,
            artists = artists,
            isLoading = false,
            isRefreshing = isRefreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LibraryUiState(),
    )

    fun onSearchChange(text: String) {
        _query.update { it.copy(search = text) }
    }

    fun onArtistSelected(artistId: Long?) {
        _query.update { it.copy(artistId = artistId, offset = 0) }
    }

    fun onClearFilters() {
        _query.value = LibraryQuery.Default
    }

    /**
     * Trigger a `syncChanges` against the backend. The coordinator
     * already enforces single-flight (no-op when another sync is in
     * flight), so the VM doesn't need a mutex. PullToRefreshBox watches
     * [state.isRefreshing] to drive its indicator.
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
        // Reset isRefreshing when the coordinator's state leaves Running
        // (covers the case where syncChanges was a no-op because another
        // sync was already in flight — we want the indicator to clear
        // once the underlying sync settles).
        viewModelScope.launch {
            syncCoordinator.state.collect { s ->
                if (s !is SyncState.Running) {
                    _isRefreshing.value = false
                }
            }
        }
    }
}
