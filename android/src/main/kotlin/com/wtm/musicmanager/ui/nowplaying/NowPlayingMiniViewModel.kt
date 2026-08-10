package com.wtm.musicmanager.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.db.Track
import com.wtm.musicmanager.player.PlayerState
import com.wtm.musicmanager.player.PlayerTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backing state for the persistent NowPlayingMini bar rendered above the
 * bottom-nav. Hides itself when [PlayerState.Idle]. Reads PlayerTrigger
 * directly — there's no domain logic here, just projection.
 *
 * The single observable string is exposed in addition to the raw
 * PlayerState so the minicomposable can use a thin `when` without
 * having to handle every PlayerState subtype itself.
 */
@HiltViewModel
class NowPlayingMiniViewModel @Inject constructor(
    private val playerTrigger: PlayerTrigger,
) : ViewModel() {

    val state: StateFlow<NowPlayingMiniUi> = playerTrigger.state
        .map { toUi(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = NowPlayingMiniUi.Hidden,
        )

    fun playPause() = playerTrigger.playPause()

    fun stop() = playerTrigger.stop()

    fun play(track: Track) {
        viewModelScope.launch {
            playerTrigger.play(track)
        }
    }
}

/**
 * Projected UI state for the mini-player.
 * - [Hidden] — no media; the bar isn't rendered.
 * - [Loading] — track is loading; render skeleton.
 * - [Active] — track ready, show title / artist + play/pause.
 * - [Error] — recoverable; render compact error.
 */
sealed interface NowPlayingMiniUi {
    data object Hidden : NowPlayingMiniUi
    data class Loading(val title: String) : NowPlayingMiniUi
    data class Active(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    ) : NowPlayingMiniUi
    data class Error(val message: String) : NowPlayingMiniUi
}

private fun toUi(state: PlayerState): NowPlayingMiniUi = when (state) {
    is PlayerState.Idle -> NowPlayingMiniUi.Hidden
    is PlayerState.Loading -> NowPlayingMiniUi.Loading(state.track.title)
    is PlayerState.Playing -> NowPlayingMiniUi.Active(
        title = state.track.title,
        artist = state.track.artist_name,
        isPlaying = true,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
    )
    is PlayerState.Paused -> NowPlayingMiniUi.Active(
        title = state.track.title,
        artist = state.track.artist_name,
        isPlaying = false,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
    )
    is PlayerState.Error -> NowPlayingMiniUi.Error(state.reason)
}
