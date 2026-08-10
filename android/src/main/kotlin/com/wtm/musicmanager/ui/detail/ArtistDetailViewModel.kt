package com.wtm.musicmanager.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.data.LibraryQuery
import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Artist
import com.wtm.musicmanager.db.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reactive UI state for [ArtistDetailScreen].
 *
 * Two flows from the existing repository:
 * - observeAlbumsByArtist(artistId) → album grid
 * - observeTracks(LibraryQuery(artistId=…)) → track list (the same
 *   pattern SearchScreen's Albums tab uses)
 *
 * Plus the artist metadata from artistById(artistId) for the header.
 */
data class ArtistDetailUiState(
    val artist: Artist? = null,
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val repository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val artistId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_ARTIST_ID)) {
        "ArtistDetailViewModel requires '$ARG_ARTIST_ID' nav arg"
    }

    private val _artist = MutableStateFlow<Artist?>(null)
    private val _notFound = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val a = repository.artistById(artistId)
            _artist.value = a
            if (a == null) _notFound.value = true
        }
    }

    val state: StateFlow<ArtistDetailUiState> = combine(
        _artist,
        repository.observeAlbumsByArtist(artistId),
        repository.observeTracks(LibraryQuery(artistId = artistId)),
        _notFound,
    ) { artist, albums, tracks, notFound ->
        ArtistDetailUiState(
            artist = artist,
            albums = albums,
            tracks = tracks,
            isLoading = artist == null && !notFound,
            notFound = notFound,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ArtistDetailUiState(),
    )

    companion object {
        const val ARG_ARTIST_ID: String = "artistId"
    }
}
