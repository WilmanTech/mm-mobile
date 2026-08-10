package com.wtm.musicmanager.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Android playback engine. Wraps ExoPlayer and exposes [PlayerTrigger].
 *
 * Lifecycle:
 * - Created at app start by Hilt as a @Singleton.
 * - Owns one ExoPlayer instance for the whole app process.
 * - Survives Activity recreation (no @AndroidEntryPoint teardown needed).
 * - `stop()` is called when the user signs out; the engine can be
 *   `release()`-d then. For now we just `stop()` and keep warm.
 *
 * State projection:
 * - `state` is a MutableStateFlow seeded with [PlayerState.Idle].
 * - Player.Listener callbacks translate ExoPlayer events to PlayerState.
 * - A coroutine ticks every 250ms while Playing to refresh `positionMs`
 *   so the scrubber can animate. When Paused, no tick.
 *
 * URL resolution:
 * - If the incoming Track has `stream_url != null`, we use it verbatim.
 * - Otherwise we build `${baseUrl}/api/stream/${id}` from the same
 *   shared `BASE_URL` constant that MusicManagerApi uses. Phase 4
 *   will swap this for a signed-URL round-trip when the endpoint
 *   grows one.
 */
class ExoPlayerRepository(
    private val context: Context,
    private val baseUrl: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : PlayerTrigger {

    companion object {
        private const val TAG = "ExoPlayerRepository"
        private const val POSITION_TICK_MS = 250L
    }

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build().also { it.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updateFromExo()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateFromExo()
            if (isPlaying) startTicking() else stopTicking()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "ExoPlayer error code=${error.errorCodeName}: ${error.message}")
            val current = _state.value
            val track = (current as? PlayerState.Playing)?.track
                ?: (current as? PlayerState.Paused)?.track
                ?: (current as? PlayerState.Loading)?.track
            _state.value = PlayerState.Error(error.message ?: error.errorCodeName)
        }
    }

    private var tickJob: Job? = null

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                val current = _state.value
                if (current is PlayerState.Playing) {
                    _state.value = current.copy(positionMs = exoPlayer.currentPosition)
                }
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun updateFromExo() {
        when (exoPlayer.playbackState) {
            Player.STATE_IDLE -> {
                // Could happen if we replace media before any playback
            }
            Player.STATE_BUFFERING -> {
                val track = currentTrack() ?: return
                _state.value = PlayerState.Loading(track)
            }
            Player.STATE_READY -> {
                val track = currentTrack() ?: return
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                if (exoPlayer.isPlaying) {
                    _state.value = PlayerState.Playing(track, pos, dur)
                } else {
                    _state.value = PlayerState.Paused(track, pos, dur)
                }
            }
            Player.STATE_ENDED -> {
                _state.value = PlayerState.Idle
            }
        }
    }

    private fun currentTrack(): Track? {
        // ExoPlayer doesn't expose the original Track object — we
        // cached it at play() time. See pendingTrack below.
        return pendingTrack
    }

    private var pendingTrack: Track? = null

    override suspend fun play(track: Track) {
        pendingTrack = track
        val url = track.stream_url ?: "${baseUrl.trimEnd('/')}/api/stream/${track.id}"

        _state.value = PlayerState.Loading(track)

        // Make sure the player exists lazily before prepare.
        exoPlayer

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(track.id.toString())
        mimeFor(track.codec)?.let { mediaItemBuilder.setMimeType(it) }
        val mediaItem = mediaItemBuilder.build()

        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun playPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    override fun seek(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
        updateFromExo()
    }

    override fun stop() {
        stopTicking()
        exoPlayer.stop()
        pendingTrack = null
        _state.value = PlayerState.Idle
    }

    private fun mimeFor(codec: String?): String? = when (codec?.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "vorbis" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        else -> null
    }
}
