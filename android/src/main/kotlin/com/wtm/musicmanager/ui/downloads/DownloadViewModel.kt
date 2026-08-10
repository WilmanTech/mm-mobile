package com.wtm.musicmanager.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.db.Track
import com.wtm.musicmanager.download.DownloadInfo
import com.wtm.musicmanager.download.DownloadTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Bridges [DownloadTrigger] into Compose-friendly shapes.
 *
 * The trigger exposes a `Map<Long, DownloadInfo>` where tracks not in
 * the map are implicitly NotDownloaded. The UI mostly reads two
 * things:
 *
 *  - isDownloaded(trackId): Boolean — for the "downloaded" badge /
 *    play-local indicator on each track row.
 *  - infoFor(trackId): DownloadInfo — for the per-track icon
 *    (Download / Downloading spinner / Delete / Error).
 *
 * The viewmodel itself implements [DownloadTrigger] so callers that
 * need a trigger (e.g. AlbumDetailViewModel.bindDownloadTrigger) can
 * pass `this` directly.
 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadTrigger: DownloadTrigger,
) : ViewModel(), DownloadTrigger by downloadTrigger {

    val uiState: StateFlow<DownloadUiState> = downloadTrigger.state
        .map { DownloadUiState(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DownloadUiState(emptyMap()),
        )

    fun infoFor(trackId: Long): DownloadInfo =
        uiState.value.infos[trackId] ?: DownloadInfo.NotDownloaded

    fun isDownloaded(trackId: Long): Boolean =
        uiState.value.infos[trackId] is DownloadInfo.Downloaded
}

data class DownloadUiState(
    val infos: Map<Long, DownloadInfo>,
)