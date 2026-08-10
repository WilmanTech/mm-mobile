package com.wtm.musicmanager.download

import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Snapshot of a single track's download lifecycle, exposed by
 * [DownloadTrigger.state]. The progress is in [0, 100]; the
 * remaining cases are name-matched to [com.wtm.musicmanager.domain.model.DownloadState]
 * for easy mapping to the per-track row UI.
 *
 * The UI gets a single map (`Map<trackId, DownloadInfo>`) instead
 * of one flow per track. Tracks not in the map are
 * `DownloadInfo.NotDownloaded`.
 */
sealed interface DownloadInfo {
    data object NotDownloaded : DownloadInfo
    data class Downloading(val progressPercent: Int) : DownloadInfo
    data object Downloaded : DownloadInfo
    data class Failed(val reason: String) : DownloadInfo
}

/**
 * Minimum surface the UI needs from the download subsystem.
 *
 * Modeled after SyncTrigger / PlayerTrigger:
 * - The state is a [StateFlow] (always has a current value)
 * - `enqueue` is non-suspend; the actual work runs in the
 *   worker. The map will switch to DownloadInfo.Downloading
 *   soon after the worker schedules itself.
 * - `delete` is non-suspend; removes the local file + clears
 *   the row in the same way (one transaction).
 *
 * The Android impl (DownloadRepository in androidMain) is
 * the only consumer. iOS will get its own binding in Phase 5
 * (BGTaskScheduler on iOS side).
 */
interface DownloadTrigger {
    val state: StateFlow<Map<Long, DownloadInfo>>

    fun enqueue(track: Track)
    fun delete(track: Track)
    fun clearAll()
}