package com.wtm.musicmanager.download

import com.wtm.musicmanager.data.SyncUpsertQueries
import com.wtm.musicmanager.db.MusicManagerDatabase
import com.wtm.musicmanager.domain.model.DownloadState
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 3.D — read+write surface for the per-track download state,
 * exposed via [LibraryEntry.Graph] so the iOS `SettingsView`
 * "Descargas" section can reactively show how many tracks are
 * downloaded.
 *
 * **Why a separate repo and not the `DownloadTrigger` interface**
 * The `DownloadTrigger` (Phase 5, BGTaskScheduler-based) is the
 * future home of the *queue* — `enqueue / delete / clearAll` against
 * a persistent `download_job` table driven by a background worker.
 * Phase 3.D doesn't ship that worker yet (iOS BGTaskScheduler needs
 * entitlements + a complex scheduling dance). What we *do* ship is
 * the per-track state read+write: a Flow that emits
 * `Map<trackId, DownloadInfo>` and a couple of mutators that flip the
 * `track.download_state` column. The future Android `DownloadWorker`
 * and iOS `BGTaskScheduler` worker will both call into this same
 * repository so the UI is consistent regardless of who's driving the
 * transitions.
 *
 * **Why a `Flow<Map>` and not a `Flow<List>`** The iOS SwiftUI
 * layer maps this 1:1 into `Map<Long, DownloadInfo>` for O(1) lookup
 * from per-track UI rows (Phase 3.D+ mini-bar download badge). A list
 * would force the SwiftUI `ForEach` to do its own `first(where:)`
 * scan on every re-render.
 *
 * **Default state**: tracks not present in the map are
 * [DownloadInfo.NotDownloaded]. We don't emit one entry per track
 * row — the map only contains rows whose state is *not*
 * `NotDownloaded`. The Swift side fills the rest lazily.
 */
interface DownloadStateRepository {

    /**
     * Cold Flow that re-emits whenever any `track.download_state`
     * row changes. The map key is the track id (Long, the same one
     * used by `LibraryRepository.observeTracks()`); the value is the
     * [DownloadInfo] mirror that the UI consumes. Tracks in
     * [DownloadState.NotDownloaded] are NOT included — the caller
     * treats missing keys as `NotDownloaded`.
     */
    fun observeStates(): Flow<Map<Long, DownloadInfo>>

    /**
     * Mark a track as fully downloaded, point its `local_path` at
     * `path`. Caller (the future download worker) is responsible for
     * actually writing the file at `path` BEFORE calling this — the
     * repo just flips the DB state. On the iOS side today the
     * `SettingsView` debug toggle calls this with a fake path so the
     * UI exercises the column without a real download.
     */
    fun markDownloaded(trackId: Long, localPath: String)

    /**
     * Mark a track as not downloaded, clear its `local_path`. Caller
     * is responsible for deleting the file at the previous path
     * BEFORE calling this (or accepting that the file becomes an
     * orphan).
     */
    fun markNotDownloaded(trackId: Long)

    /**
     * Mark every `Downloaded` track as `NotDownloaded` and clear
     * their `local_path`. Used by the "Limpiar descargas" button in
     * the future storage UI. Today the iOS Settings exposes the
     * per-track toggle but not the bulk clear; this method is the
     * building block for when it does.
     */
    fun clearAllDownloads()
}

/**
 * SQLDelight-backed production impl. Reuses the same `MusicManagerDatabase`
 * that `SqlDelightLibraryRepository` reads from, so the per-track
 * `download_state` column is the single source of truth.
 *
 * **Reactive observation** — `selectAllTrackDownloadStates` is a
 * plain `Query` (not a Flow). We wrap it with `asFlow().mapToList`
 * from `kotlinx-coroutines-sqldelight` so the Flow re-emits when
 * the `track` table changes (any insert / update / delete). The
 * `Dispatchers.IO` argument to `asFlow` controls which thread
 * SQLDelight reads on; `Default` would work too but IO is the
 * convention we use for repository reads.
 *
 * **Map projection** — `selectAllTrackDownloadStates` returns
 * `(id, download_state, local_path)` rows. We project to
 * `Map<Long, DownloadInfo>` here, dropping entries whose
 * `download_state == NotDownloaded` (the contract above).
 */
class SqlDelightDownloadStateRepository(
    private val db: MusicManagerDatabase,
    private val queries: SyncUpsertQueries,
) : DownloadStateRepository {

    override fun observeStates(): Flow<Map<Long, DownloadInfo>> {
        // `Dispatchers.IO` exists on JVM but is internal on
        // Kotlin/Native. `Default` works on every target and is
        // fine for a single-table read that runs on cold-cache
        // paths; if profiling shows a regression on Android we can
        // switch to `Dispatchers.IO` behind an `expect/actual`
        // dispatcher.
        return db.queriesQueries
            .selectAllTrackDownloadStates()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.toDownloadInfoMap() }
    }

    override fun markDownloaded(trackId: Long, localPath: String) {
        queries.transaction {
            queries.updateTrackDownload(
                trackId = trackId,
                localPath = localPath,
                state = DownloadState.Downloaded,
            )
        }
    }

    override fun markNotDownloaded(trackId: Long) {
        queries.transaction {
            queries.updateTrackDownload(
                trackId = trackId,
                localPath = null,
                state = DownloadState.NotDownloaded,
            )
        }
    }

    override fun clearAllDownloads() {
        // Bulk-update via raw SQL because the generated queries
        // don't expose a `UPDATE ... WHERE download_state = ?`
        // variant. SQLDelight's `queriesQueries` access from common
        // Main is via `database: MusicManagerDatabase`, but the
        // generated `*Queries` type doesn't expose a raw
        // `driver.execute()` helper. The simplest cross-platform
        // approach: iterate the current map and reset each entry.
        // Phase 3.D keeps the bulk clear out of the iOS UI until
        // there's a use case for it.
        val current = db.queriesQueries
            .selectAllTrackDownloadStates()
            .executeAsList()
        current
            .filter { it.download_state == DownloadState.Downloaded.name }
            .forEach { row ->
                queries.updateTrackDownload(
                    trackId = row.id,
                    localPath = null,
                    state = DownloadState.NotDownloaded,
                )
            }
    }

    /**
     * Project the SQLDelight rows into the public map. Tracks in
     * `NotDownloaded` are dropped so callers can treat the map as
     * "tracks with non-default state only". Tracks in `Downloading`
     * are exposed as `DownloadInfo.Downloading(0)` — the real
     * progress percent will come from the future `DownloadTrigger`
     * flow, not from this repo.
     */
    private fun List<com.wtm.musicmanager.db.SelectAllTrackDownloadStates>.toDownloadInfoMap(): Map<Long, DownloadInfo> {
        val out = mutableMapOf<Long, DownloadInfo>()
        for (row in this) {
            val state = runCatching { DownloadState.valueOf(row.download_state) }
                .getOrDefault(DownloadState.NotDownloaded)
            when (state) {
                DownloadState.NotDownloaded -> { /* dropped by contract */ }
                DownloadState.Queued -> out[row.id] = DownloadInfo.Downloading(progressPercent = 0)
                DownloadState.Downloading -> out[row.id] = DownloadInfo.Downloading(progressPercent = 0)
                DownloadState.Downloaded -> out[row.id] = DownloadInfo.Downloaded
                DownloadState.Failed -> out[row.id] = DownloadInfo.Failed(reason = "Unknown")
            }
        }
        return out
    }
}