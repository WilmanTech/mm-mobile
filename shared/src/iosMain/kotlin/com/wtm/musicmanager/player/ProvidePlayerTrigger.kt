package com.wtm.musicmanager.player

import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS stub for PlayerTrigger. Phase 5 owns the native Swift AVPlayer
 * rewrite; until then, the app builds and runs but audio playback on
 * iOS is silent (state never leaves Idle).
 *
 * Pulled in via expect/actual — commonMain declares
 * `expect fun providePlayerTrigger(): PlayerTrigger`, androidMain
 * provides an Hilt-mediated ExoPlayer, iosMain provides this stub.
 */
actual fun providePlayerTrigger(): PlayerTrigger = object : PlayerTrigger {
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override suspend fun play(track: Track) {
        // No-op on iOS until Phase 5.
    }

    override fun playPause() {}
    override fun seek(positionMs: Long) {}
    override fun stop() {
        _state.value = PlayerState.Idle
    }
}
