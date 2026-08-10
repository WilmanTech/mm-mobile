package com.wtm.musicmanager.player

import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Snapshot of the playback engine exposed by [PlayerTrigger].
 *
 * Modeled after SyncState / PairingState from earlier phases: a
 * closed sealed hierarchy that the UI can render exhaustively.
 * Every state carries the maximum info the UI needs to render
 * itself without consulting the player directly — that's the
 * point of the StateFlow.
 *
 * - [Idle] — no media. Mini-player hidden.
 * - [Loading] — buffer or resolve. Mini-player visible as a skeleton.
 * - [Playing] / [Paused] — track + position + duration.
 * - [Error] — recoverable; mini-player shows the message.
 *
 * `positionMs` updates whenever the progress poll ticks; the
 * mini-player uses this to drive its scrubber.
 */
sealed interface PlayerState {
    data object Idle : PlayerState

    data class Loading(val track: Track) : PlayerState

    data class Playing(
        val track: Track,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlayerState

    data class Paused(
        val track: Track,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlayerState

    data class Error(val reason: String) : PlayerState
}

/**
 * Minimum surface the UI needs from the playback engine.
 * Mirrors SyncTrigger / PairingTrigger — concrete impl hides
 * ExoPlayer (Android) / AVPlayer (iOS).
 */
interface PlayerTrigger {
    val state: StateFlow<PlayerState>

    /**
     * Replace the queue with a single track and start playback.
     * The caller may pass the [Track] either with a pre-resolved
     * `stream_url` (back-end resolved via /api/stream/{id}) or
     * with `stream_url = null` — in which case the impl resolves
     * the canonical URL.
     */
    suspend fun play(track: Track)

    fun playPause()

    fun seek(positionMs: Long)

    fun stop()
}

/**
 * Factory for the platform's PlayerTrigger.
 *
 * expect/actual is needed because the impl binds to platform
 * context classes:
 * - Android: ExoPlayer needs a [android.content.Context].
 * - iOS: AVPlayer needs a UIViewController / dispatch queue. The
 *   Phase 5 native Swift rewrite owns the iOS side; for now the
 *   iOS impl is a no-op stub that never plays anything. The app
 *   builds and runs, just with audio silence on iOS until Phase 5.
 *
 * The Hilt/Singleton side lives in androidMain; on iOS the Swift
 * interop layer will replace the Kotlin entry point entirely.
 */
expect fun providePlayerTrigger(): PlayerTrigger
