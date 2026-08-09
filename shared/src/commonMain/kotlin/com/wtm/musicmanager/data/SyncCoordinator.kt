package com.wtm.musicmanager.data

import com.wtm.musicmanager.domain.model.Album
import com.wtm.musicmanager.domain.model.Artist
import com.wtm.musicmanager.domain.model.Playlist
import com.wtm.musicmanager.domain.model.Track
import com.wtm.musicmanager.network.ChangesSyncResponse
import com.wtm.musicmanager.network.FullSyncResponse
import com.wtm.musicmanager.network.MusicManagerApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Orchestrates the full / incremental sync against the backend.
 *
 * Sync lifecycle:
 *   1. UI calls [syncFull] (first launch, "Refresh" button) → wipes local
 *      tables and re-populates from `/api/v1/sync/full`.
 *   2. UI calls [syncChanges] (foreground app resume, periodic worker) →
 *      sends `X-Since: <lastServerTime>` and upserts deltas.
 *   3. If the server returns `has_more = true`, the caller is expected to
 *      call [syncChanges] again until has_more flips to false.
 *
 * State surface:
 *   - [state]: StateFlow observers (UI banner, log) can collect to render
 *     "Syncing N/4" or "Last sync: 12:34 · 1234 tracks".
 *   - lastServerTime is held in-memory only; persistent cursor lives in
 *     SQLDelight via the `synced_at` columns + a settings table (TBD).
 *
 * Concurrency:
 *   - The StateFlow is the single source of truth for "is a sync running".
 *   - If a sync is already in flight, [syncFull] / [syncChanges] are no-ops
 *     and the existing in-flight call wins. This avoids the "user mashes
 *     refresh" thundering-herd against the backend.
 */
class SyncCoordinator(
    private val api: MusicManagerApi,
    private val upsertQueries: SyncUpsertQueries,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _lastServerTime = MutableStateFlow(0L)
    val lastServerTime: StateFlow<Long> = _lastServerTime.asStateFlow()

    suspend fun syncFull(): SyncState {
        if (_state.value is SyncState.Running) return _state.value
        _state.value = SyncState.Running(phase = SyncPhase.Full)

        val response = try {
            api.fullSync()
        } catch (e: Throwable) {
            val err = SyncState.Failed(e.message ?: "Sync failed")
            _state.value = err
            return err
        }

        applyFull(response.artists, response.albums, response.tracks, response.playlists, response.playlistTracks)
        _lastServerTime.value = response.serverTime
        val ok = SyncState.Completed(
            at = now(),
            artists = response.artists.size,
            albums = response.albums.size,
            tracks = response.tracks.size,
            playlists = response.playlists.size,
        )
        _state.value = ok
        return ok
    }

    suspend fun syncChanges(): SyncState {
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

        applyChanges(response.artists, response.albums, response.tracks, response.playlists, response.playlistTracks)
        _lastServerTime.value = response.serverTime
        val ok = SyncState.Completed(
            at = now(),
            artists = response.artists.size,
            albums = response.albums.size,
            tracks = response.tracks.size,
            playlists = response.playlists.size,
        )
        _state.value = ok
        return ok
    }

    private fun applyFull(
        artists: List<com.wtm.musicmanager.network.ArtistDto>,
        albums: List<com.wtm.musicmanager.network.AlbumDto>,
        tracks: List<com.wtm.musicmanager.network.TrackDto>,
        playlists: List<com.wtm.musicmanager.network.PlaylistDto>,
        playlistTracks: List<com.wtm.musicmanager.network.PlaylistTrackDto>,
    ) {
        upsertQueries.transaction {
            upsertQueries.deleteAllArtists()
            upsertQueries.deleteAllAlbums()
            upsertQueries.deleteAllTracks()
            upsertQueries.deleteAllPlaylists()
            upsertQueries.deleteAllPlaylistTracks()

            val syncedAt = now()
            artists.forEach { dto ->
                upsertQueries.upsertArtist(dto.toDomain(), syncedAt)
            }
            albums.forEach { dto ->
                upsertQueries.upsertAlbum(dto.toDomain(), syncedAt)
            }
            tracks.forEach { dto ->
                upsertQueries.upsertTrack(dto.toDomain(), syncedAt)
            }
            playlists.forEach { dto ->
                upsertQueries.upsertPlaylist(dto.toDomain(), syncedAt)
            }
            playlistTracks.forEach { dto ->
                upsertQueries.upsertPlaylistTrack(
                    playlistId = dto.playlistId,
                    trackId = dto.trackId,
                    position = dto.position,
                    addedAt = dto.addedAt,
                )
            }
        }
    }

    private fun applyChanges(
        artists: List<com.wtm.musicmanager.network.ArtistDto>,
        albums: List<com.wtm.musicmanager.network.AlbumDto>,
        tracks: List<com.wtm.musicmanager.network.TrackDto>,
        playlists: List<com.wtm.musicmanager.network.PlaylistDto>,
        playlistTracks: List<com.wtm.musicmanager.network.PlaylistTrackDto>,
    ) {
        upsertQueries.transaction {
            val syncedAt = now()
            artists.forEach { dto ->
                upsertQueries.upsertArtist(dto.toDomain(), syncedAt)
            }
            albums.forEach { dto ->
                upsertQueries.upsertAlbum(dto.toDomain(), syncedAt)
            }
            tracks.forEach { dto ->
                upsertQueries.upsertTrack(dto.toDomain(), syncedAt)
            }
            playlists.forEach { dto ->
                upsertQueries.upsertPlaylist(dto.toDomain(), syncedAt)
            }
            playlistTracks.forEach { dto ->
                upsertQueries.upsertPlaylistTrack(
                    playlistId = dto.playlistId,
                    trackId = dto.trackId,
                    position = dto.position,
                    addedAt = dto.addedAt,
                )
            }
        }
    }
}

/**
 * Thin facade around SQLDelight's generated queries. Tests provide an
 * in-memory implementation backed by the JDBC sqlite-driver; production
 * uses the generated AndroidDriver/NativeDriver-bound queries.
 *
 * The reason for the facade (vs. injecting the SQLDelight class directly):
 * SQLDelight generates platform-bound classes that aren't accessible from
 * commonTest without the actual driver. This interface lets us swap in a
 * fake that records calls and stores rows in a HashMap.
 */
interface SyncUpsertQueries {
    fun transaction(block: () -> Unit)
    fun upsertArtist(artist: Artist, syncedAt: Long)
    fun upsertAlbum(album: Album, syncedAt: Long)
    fun upsertTrack(track: Track, syncedAt: Long)
    fun upsertPlaylist(playlist: Playlist, syncedAt: Long)
    fun upsertPlaylistTrack(playlistId: Long, trackId: Long, position: Int, addedAt: Long)
    fun deleteAllArtists()
    fun deleteAllAlbums()
    fun deleteAllTracks()
    fun deleteAllPlaylists()
    fun deleteAllPlaylistTracks()
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val phase: SyncPhase, val since: Long = 0L) : SyncState
    data class Completed(
        val at: Long,
        val artists: Int,
        val albums: Int,
        val tracks: Int,
        val playlists: Int,
    ) : SyncState
    data class Failed(val reason: String) : SyncState
}

enum class SyncPhase { Full, Changes }
