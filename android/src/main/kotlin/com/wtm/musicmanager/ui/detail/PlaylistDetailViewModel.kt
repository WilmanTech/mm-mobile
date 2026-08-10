package com.wtm.musicmanager.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.db.Playlist
import com.wtm.musicmanager.db.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reactive UI state for [PlaylistDetailScreen].
 *
 * - [playlist]: metadata (name, description, track_count, smart flag)
 *   loaded once via playlistById() — the header needs it even when
 *   the track Flow is empty.
 * - [tracks]: from observePlaylistTracks(playlistId) — ordered by
 *   `position` via the existing selectTracksByPlaylist SQL.
 */
data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val playlistId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_PLAYLIST_ID)) {
        "PlaylistDetailViewModel requires '$ARG_PLAYLIST_ID' nav arg"
    }

    private val _playlist = MutableStateFlow<Playlist?>(null)
    private val _notFound = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val p = repository.playlistById(playlistId)
            _playlist.value = p
            if (p == null) _notFound.value = true
        }
    }

    val state: StateFlow<PlaylistDetailUiState> = combine(
        _playlist,
        repository.observePlaylistTracks(playlistId),
        _notFound,
    ) { playlist, tracks, notFound ->
        PlaylistDetailUiState(
            playlist = playlist,
            tracks = tracks,
            isLoading = playlist == null && !notFound,
            notFound = notFound,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlaylistDetailUiState(),
    )

    companion object {
        const val ARG_PLAYLIST_ID: String = "playlistId"
    }
}
