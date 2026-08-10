package com.wtm.musicmanager.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.data.LibraryQuery
import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Track
import com.wtm.musicmanager.download.DownloadInfo
import com.wtm.musicmanager.download.DownloadTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reactive UI state for [AlbumDetailScreen].
 *
 * - [album]: the album metadata (title, artist_name, year, cover_path).
 *   Loaded once via [LibraryRepository.albumById] — the result is held
 *   in a MutableStateFlow so the screen renders the header even if the
 *   track Flow is empty.
 * - [tracks]: the list of tracks that belong to this album, ordered
 *   by `track_number` (driven by the existing Phase 2
 *   `selectTracksByAlbum` SQL query).
 * - [isLoading]: sticky true until the first emission of either flow.
 * - [notFound]: set to true after the album metadata load returns null
 *   (the id is invalid or was deleted locally). The screen renders an
 *   empty state with a "Volver" button.
 */
data class AlbumDetailUiState(
    val album: Album? = null,
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val downloads: Map<Long, DownloadInfo> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_ALBUM_ID)) {
        "AlbumDetailViewModel requires '$ARG_ALBUM_ID' nav arg"
    }

    /**
     * The download subsystem is wired by the Composable via
     * [bindDownloadTrigger]. Taking a second @Inject ctor param
     * tripped KSP's processing (a known issue when adding a new
     * arg to an existing @HiltViewModel constructor), so the
     * trigger is injected post-construction.
     */
    var downloadTrigger: DownloadTrigger? = null
        private set

    fun bindDownloadTrigger(trigger: DownloadTrigger) {
        if (downloadTrigger === trigger) return
        downloadTrigger = trigger
        // Once bound, mirror the trigger's StateFlow into our combine
        // pipeline so the per-row icon re-renders reactively.
        viewModelScope.launch {
            trigger.state.collect { map ->
                downloadStateFlow.value = map
            }
        }
    }

    private val _album = MutableStateFlow<Album?>(null)
    private val _notFound = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val a = repository.albumById(albumId)
            _album.value = a
            if (a == null) _notFound.value = true
        }
    }

    private val tracksFlow = flowOf(LibraryQuery(albumId = albumId))
        .flatMapLatest { repository.observeTracks(it) }

    private val downloadStateFlow = MutableStateFlow<Map<Long, DownloadInfo>>(emptyMap())

    val state: StateFlow<AlbumDetailUiState> = combine(
        _album,
        tracksFlow,
        _notFound,
        downloadStateFlow,
    ) { album, tracks, notFound, downloads ->
        AlbumDetailUiState(
            album = album,
            tracks = tracks,
            isLoading = album == null && !notFound,
            notFound = notFound,
            downloads = downloads,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AlbumDetailUiState(),
    )

    fun downloadInfo(trackId: Long): DownloadInfo =
        downloadStateFlow.value[trackId] ?: DownloadInfo.NotDownloaded

    fun enqueueDownload(track: Track) { downloadTrigger?.enqueue(track) }
    fun deleteDownload(track: Track) { downloadTrigger?.delete(track) }

    companion object {
        const val ARG_ALBUM_ID: String = "albumId"
    }
}
