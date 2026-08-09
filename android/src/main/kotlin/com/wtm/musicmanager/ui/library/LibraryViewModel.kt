package com.wtm.musicmanager.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.data.LibraryQuery
import com.wtm.musicmanager.data.LibraryRepository
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

/**
 * Reactive UI state for [LibraryScreen]. One screen, three slots:
 * - [query]: the current search/filter (mutable, drives re-query)
 * - [tracks]: observed list of tracks from SQLDelight
 * - [artists]: sidebar list of available artists
 * - [isLoading]: sticky true while we have no data yet
 * - [error]: last sync/network error to surface as a banner
 */
data class LibraryUiState(
    val query: LibraryQuery = LibraryQuery.Default,
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    private val _query = MutableStateFlow(LibraryQuery.Default)

    private val _tracks = _query
        .debounce(150L) // avoid thrashing on every keystroke
        .flatMapLatest { repository.observeTracks(it) }

    val state: StateFlow<LibraryUiState> = combine(
        _query,
        _tracks,
        repository.observeArtists(),
    ) { query, tracks, artists ->
        LibraryUiState(
            query = query,
            tracks = tracks,
            artists = artists,
            isLoading = false,
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
}
