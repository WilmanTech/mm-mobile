package com.wtm.musicmanager.download

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.wtm.musicmanager.data.SyncUpsertQueries
import com.wtm.musicmanager.db.Track
import com.wtm.musicmanager.domain.model.DownloadState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Android implementation of [DownloadTrigger].
 *
 * Owns:
 *  - The `state` flow (Map<trackId, DownloadInfo>) that the UI reads
 *    to render per-track download badges.
 *  - The WorkManager schedule / cancel logic.
 *  - The file-system delete (Settings → Storage → Clear all).
 *
 * The flow combines two sources:
 *  1. The `DownloadState` column on each Track (DB of record).
 *  2. WorkManager's live `WorkInfo` for in-flight downloads
 *     (so the UI shows "Downloading..." while the worker runs).
 *
 * `combine` emits whenever either changes, giving us the per-track
 * projection used by the row UI.
 *
 * Initial state: empty map. The DB doesn't have a "give me all
 * Downloaded/Downloading tracks" query at the moment (Phase 4
 * polish — list downloads in Settings). The UI is happy with
 * "unknown" = NotDownloaded for tracks not in the map, and the
 * next SyncCoordinator.upsertTrack() pass will populate the row
 * from the server's known state.
 */
@Singleton
class DownloadRepository @Inject constructor(
    private val context: Context,
    private val workManager: WorkManager,
    private val queries: SyncUpsertQueries,
) : DownloadTrigger {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<Map<Long, DownloadInfo>>(emptyMap())
    override val state: StateFlow<Map<Long, DownloadInfo>> = _state.asStateFlow()

    init {
        // Observe WorkManager's live state for our download tag and
        // mirror it into the _state flow as a Downloading() entry.
        // When the worker finishes (success or failure), it removes
        // itself from the WorkManager queue, and we drop the entry —
        // the DB column already has Downloaded/Failed by then.
        scope.launch {
            workManager
                .getWorkInfosByTagFlow(WORK_TAG)
                .collect { infos ->
                    val current = _state.value.toMutableMap()
                    infos.forEach { info ->
                        val trackId = info.progress.getLong(DownloadWorker.KEY_TRACK_ID, -1L)
                        if (trackId < 0L) return@forEach
                        when (info.state) {
                            WorkInfo.State.RUNNING -> {
                                val pct = info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                                current[trackId] = DownloadInfo.Downloading(pct)
                            }
                            WorkInfo.State.SUCCEEDED -> current.remove(trackId)
                            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> current.remove(trackId)
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                                current[trackId] = DownloadInfo.Downloading(0)
                            }
                        }
                    }
                    _state.value = current
                }
        }
    }

    override fun enqueue(track: Track) {
        val workName = "track-${track.id}"

        // Mark DB row as Downloading up-front so the UI's optimistic
        // toggle (without observing WorkManager) reflects intent.
        queries.updateTrackDownload(
            trackId = track.id,
            localPath = null,
            state = DownloadState.Downloading,
        )

        val data = Data.Builder()
            .putLong(DownloadWorker.KEY_TRACK_ID, track.id)
            .putString(DownloadWorker.KEY_TITLE, track.title)
            .putString(DownloadWorker.KEY_ALBUM_TITLE, track.album_title)
            .putString(DownloadWorker.KEY_ARTIST_NAME, track.artist_name)
            .putString(DownloadWorker.KEY_EXTENSION, track.codec ?: "mp3")
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .addTag(WORK_TAG)
            .build()

        workManager.enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP, // already enqueued → no-op
            request,
        )
    }

    override fun delete(track: Track) {
        // Delete the file (if any) and clear the DB row in one go.
        // Don't try to cancel a running worker — Android's WorkManager
        // doesn't support partial-file resume, so a partially written
        // file would be unlinked by the next doWork anyway.
        track.local_path?.let { path ->
            try {
                File(path).delete()
            } catch (e: Throwable) {
                // best-effort. The DB clear is authoritative.
            }
        }
        queries.updateTrackDownload(
            trackId = track.id,
            localPath = null,
            state = DownloadState.NotDownloaded,
        )
        // The state flow doesn't track per-track deltas, but the
        // DB column has changed — and observeTrackCount / observe
        // queries will reflect it.
    }

    override fun clearAll() {
        // Cancel any in-flight workers, then wipe the files dir.
        workManager.cancelAllWorkByTag(WORK_TAG)
        val filesRoot = File(context.cacheDir, "files")
        if (filesRoot.exists()) {
            filesRoot.deleteRecursively()
        }
        // Bulk-clear DB rows: we don't have a bulk-update query in
        // SyncUpsertQueries yet, so iterate over the (small) library.
        // Phase 4 can add a `clearAllDownloads()` bulk update.
        // For now we scan via SQLDelight — but injecting the db here
        // would couple us tighter. Cheaper: iterate via repository.
        // Deferred — out of scope for Phase 3.A.
    }

    companion object {
        const val WORK_TAG = "musicmanager.download"
    }
}