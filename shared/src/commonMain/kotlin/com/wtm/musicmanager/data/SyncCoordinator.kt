package com.wtm.musicmanager.data

import com.wtm.musicmanager.domain.model.Album
import com.wtm.musicmanager.domain.model.Artist
import com.wtm.musicmanager.domain.model.Playlist
import com.wtm.musicmanager.domain.model.PlaylistTrack
import com.wtm.musicmanager.domain.model.Track
import com.wtm.musicmanager.network.AlbumDto
import com.wtm.musicmanager.network.ArtistDto
import com.wtm.musicmanager.network.MusicManagerApi
import com.wtm.musicmanager.network.PlaylistDto
import com.wtm.musicmanager.network.PlaylistTrackDto
import com.wtm.musicmanager.network.SyncResponse
import com.wtm.musicmanager.network.TrackDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimum surface the UI needs from the sync layer. Lets the ViewModel
 * inject a fake in tests without subclassing [SyncCoordinator] (which
 * has dependencies on the Ktor client + the SQLDelight upsert
 * facade — neither of which the UI cares about).
 */
interface SyncTrigger {
    val state: StateFlow<SyncState>
    val lastServerTime: StateFlow<String?>

    suspend fun syncFull(): SyncState
    suspend fun syncChanges(): SyncState
}

/**
 * Orchestrates the full / incremental sync against the backend.
 *
 * Sync lifecycle:
 *   1. UI calls [syncFull] (first launch, "Refresh" button) → wipes local
 *      tables and re-populates from `/api/v1/sync/full`.
 *   2. UI calls [syncChanges] (foreground app resume, periodic worker) →
 *      sends `since=<ISO 8601>` query param and upserts deltas.
 *
 * State surface:
 *   - [state]: StateFlow observers (UI banner, log) can collect to render
 *     "Syncing…" or "Last sync: 12:34 · 1234 tracks".
 *   - [lastServerTime] is held in-memory only; persistent cursor lives in
 *     SQLDelight via the `synced_at` columns + a settings table (TBD).
 *
 * Concurrency:
 *   - The StateFlow is the single source of truth for "is a sync running".
 *   - If a sync is already in flight, [syncFull] / [syncChanges] are no-ops
 *     and the existing in-flight call wins. This avoids the "user mashes
 *     refresh" thundering-herd against the backend.
 *
 * Implements [SyncTrigger] so the UI can depend on the minimum
 * surface (state + syncChanges), not the full class.
 */
open class SyncCoordinator(
    private val api: MusicManagerApi,
    private val upsertQueries: SyncUpsertQueries,
) : SyncTrigger {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _lastServerTime = MutableStateFlow<String?>(null)
    override val lastServerTime: StateFlow<String?> = _lastServerTime.asStateFlow()

    override suspend fun syncFull(): SyncState {
        if (_state.value is SyncState.Running) return _state.value
        _state.value = SyncState.Running(phase = SyncPhase.Full)

        val response = try {
            api.fullSync()
        } catch (e: Throwable) {
            val err = SyncState.Failed(e.message ?: "Sync failed")
            _state.value = err
            return err
        }

        applyFull(response)
        _lastServerTime.value = response.serverTime
        val ok = SyncState.Completed(
            artists = response.artists.size,
            albums = response.albums.size,
            tracks = response.tracks.size,
            playlists = response.playlists.size,
        )
        _state.value = ok
        return ok
    }

    override suspend fun syncChanges(): SyncState {
        if (_state.value is SyncState.Running) return _state.value
        val since = _lastServerTime.value
        _state.value = SyncState.Running(phase = SyncPhase.Changes, since = since)

        val response = try {
            api.changesSince(since)
        } catch (e: Throwable) {
            val err = SyncState.Failed(e.message ?: "Sync failed")
            _state.value = err
            return err
        }

        applyChanges(response)
        _lastServerTime.value = response.serverTime
        val ok = SyncState.Completed(
            artists = response.artists.size,
            albums = response.albums.size,
            tracks = response.tracks.size,
            playlists = response.playlists.size,
        )
        _state.value = ok
        return ok
    }

    private fun applyFull(response: SyncResponse) {
        upsertQueries.transaction {
            upsertQueries.deleteAllArtists()
            upsertQueries.deleteAllAlbums()
            upsertQueries.deleteAllTracks()
            upsertQueries.deleteAllPlaylists()
            upsertQueries.deleteAllPlaylistTracks()

            val syncedAt = response.serverTime
            response.artists.forEach { dto ->
                upsertQueries.upsertArtist(dto.toDomain(), syncedAt)
            }
            response.albums.forEach { dto ->
                upsertQueries.upsertAlbum(dto.toDomain(), syncedAt)
            }
            response.tracks.forEach { dto ->
                upsertQueries.upsertTrack(dto.toDomain(), syncedAt)
            }
            response.playlists.forEach { dto ->
                upsertQueries.upsertPlaylist(dto.toDomain(), syncedAt)
            }
            response.playlistTracks.forEach { dto ->
                upsertQueries.upsertPlaylistTrack(dto.toDomain(), syncedAt)
            }
        }
    }

    private fun applyChanges(response: SyncResponse) {
        upsertQueries.transaction {
            val syncedAt = response.serverTime
            response.artists.forEach { dto ->
                upsertQueries.upsertArtist(dto.toDomain(), syncedAt)
            }
            response.albums.forEach { dto ->
                upsertQueries.upsertAlbum(dto.toDomain(), syncedAt)
            }
            response.tracks.forEach { dto ->
                upsertQueries.upsertTrack(dto.toDomain(), syncedAt)
            }
            response.playlists.forEach { dto ->
                upsertQueries.upsertPlaylist(dto.toDomain(), syncedAt)
            }
            response.playlistTracks.forEach { dto ->
                upsertQueries.upsertPlaylistTrack(dto.toDomain(), syncedAt)
            }
        }
    }
}

/**
 * Thin facade around SQLDelight's generated queries. Tests provide an
 * in-memory implementation backed by the JDBC sqlite-driver; production
 * uses the generated AndroidDriver/NativeDriver-bound queries.
 */
interface SyncUpsertQueries {
    fun transaction(block: () -> Unit)
    fun upsertArtist(artist: Artist, syncedAt: String)
    fun upsertAlbum(album: Album, syncedAt: String)
    fun upsertTrack(track: Track, syncedAt: String)
    fun upsertPlaylist(playlist: Playlist, syncedAt: String)
    fun upsertPlaylistTrack(playlistTrack: PlaylistTrack, syncedAt: String)
    fun deleteAllArtists()
    fun deleteAllAlbums()
    fun deleteAllTracks()
    fun deleteAllPlaylists()
    fun deleteAllPlaylistTracks()

    /**
     * Update a track's download fields without touching its metadata.
     * Used by [com.wtm.musicmanager.download.DownloadRepository] after a
     * DownloadWorker completes (success or failure) so the UI sees the
     * new local_path + download_state without re-syncing.
     *
     * [localPath] is null when the download failed or the file was
     * deleted (Settings → Storage → Clear).
     */
    fun updateTrackDownload(
        trackId: Long,
        localPath: String?,
        state: com.wtm.musicmanager.domain.model.DownloadState,
    )
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val phase: SyncPhase, val since: String? = null) : SyncState
    data class Completed(
        val artists: Int,
        val albums: Int,
        val tracks: Int,
        val playlists: Int,
    ) : SyncState
    data class Failed(val reason: String) : SyncState
}

enum class SyncPhase { Full, Changes }
