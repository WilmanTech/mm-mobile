package com.wtm.musicmanager.ui.downloads

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wtm.musicmanager.db.Track
import com.wtm.musicmanager.download.DownloadInfo

/**
 * The per-track-row download affordance.
 *
 * Three states:
 *  - NotDownloaded: a `CloudDownload` IconButton. Tap → enqueue.
 *  - Downloading: a small `CircularProgressIndicator`. No tap (the
 *    worker is doing its thing).
 *  - Downloaded: a `CheckCircle` IconButton. Tap → delete (clears
 *    the file + resets the row).
 *  - Failed: an `Error` IconButton. Tap → re-enqueue.
 *
 * The leading padding matches the SearchTrackRow / TrackRow
 * layouts in the screen files; this composable is just the
 * trailing icon column.
 */
@Composable
fun DownloadIcon(
    track: Track,
    state: DownloadInfo,
    onEnqueue: (Track) -> Unit,
    onDelete: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is DownloadInfo.NotDownloaded -> {
            IconButton(onClick = { onEnqueue(track) }, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = "Descargar",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is DownloadInfo.Downloading -> {
            CircularProgressIndicator(
                modifier = modifier.size(28.dp),
                strokeWidth = 2.dp,
            )
        }
        is DownloadInfo.Downloaded -> {
            IconButton(onClick = { onDelete(track) }, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = "Descargada (pulsar para borrar)",
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        is DownloadInfo.Failed -> {
            IconButton(onClick = { onEnqueue(track) }, modifier = modifier) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error (pulsar para reintentar)",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Static badge — non-interactive. Used in places where we want to
 * show 'downloaded' but tapping should not delete (e.g., the
 * NowPlayingMini art, where a delete action would be jarring).
 */
@Composable
fun DownloadedBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = "Descargada",
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.size(16.dp),
    )
}