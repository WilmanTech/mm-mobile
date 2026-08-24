package com.wtm.musicmanager.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Persistent mini-player.
 *
 * Rendered above the bottom-nav as part of
 * [com.wtm.musicmanager.ui.MusicManagerRoot]. Hides itself when
 * PlayerState is Idle (no media). Persistent across tabs — survives
 * navigating from Library to Search to Settings.
 *
 * The full-screen NowPlaying (Phase 4) will replace this when the
 * user taps the bar.
 */
@Composable
fun NowPlayingMini(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    viewModel: NowPlayingMiniViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NowPlayingMiniContent(
        state = state,
        onClick = onClick,
        onPlayPause = { viewModel.playPause() },
        onStop = { viewModel.stop() },
        modifier = modifier,
    )
}

@Composable
private fun NowPlayingMiniContent(
    state: NowPlayingMiniUi,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        NowPlayingMiniUi.Hidden -> Unit
        is NowPlayingMiniUi.Loading -> MiniBar(
            modifier = modifier,
            onClick = onClick,
            onPlayPause = onPlayPause,
            onStop = onStop,
            leading = { TrackThumbnail() },
            middle = {
                Column(Modifier.weight(1f)) {
                    MarqueeText(text = state.title)
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
            },
        )
        is NowPlayingMiniUi.Active -> MiniBar(
            modifier = modifier,
            onClick = onClick,
            onPlayPause = onPlayPause,
            onStop = onStop,
            leading = { TrackThumbnail() },
            middle = {
                Column(Modifier.weight(1f)) {
                    MarqueeText(text = state.title)
                    SubtitleText(text = state.artist)
                    if (state.durationMs > 0L) {
                        Spacer(Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = {
                                (state.positionMs.toFloat() / state.durationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    }
                }
            },
            playButtonIcon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            playButtonLabel = if (state.isPlaying) "Pausa" else "Reproducir",
        )
        is NowPlayingMiniUi.Error -> MiniBar(
            modifier = modifier,
            onClick = onClick,
            onPlayPause = onPlayPause,
            onStop = onStop,
            leading = { ErrorThumbnail() },
            middle = {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            },
            showPlayButton = false,
        )
    }
}

/**
 * The common mini-player chrome. Renders a [Surface] with a [Row]
 * that has:
 *  - leading slot (thumbnail)
 *  - middle slot (title + artist + scrubber)
 *  - trailing slot (play/pause icon, then stop X)
 */
@Composable
private fun MiniBar(
    modifier: Modifier,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    leading: @Composable () -> Unit,
    middle: @Composable RowScope.() -> Unit,
    playButtonIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.PlayArrow,
    playButtonLabel: String = "Reproducir",
    showPlayButton: Boolean = true,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(12.dp))
            middle()
            Spacer(Modifier.width(8.dp))
            if (showPlayButton) {
                IconButton(onClick = onPlayPause) {
                    Icon(imageVector = playButtonIcon, contentDescription = playButtonLabel)
                }
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Close, contentDescription = "Detener")
            }
        }
    }
}

@Composable
private fun TrackThumbnail() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ErrorThumbnail() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun MarqueeText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SubtitleText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
