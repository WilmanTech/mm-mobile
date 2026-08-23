package com.wtm.musicmanager.data

import com.wtm.musicmanager.db.MusicManagerDatabase
import com.wtm.musicmanager.domain.model.Album
import com.wtm.musicmanager.domain.model.Artist
import com.wtm.musicmanager.domain.model.Playlist
import com.wtm.musicmanager.domain.model.PlaylistTrack
import com.wtm.musicmanager.domain.model.Track

/**
 * SQLDelight-backed implementation of [SyncUpsertQueries] for production
 * code paths. Tests inject a `TestSyncQueries` over an in-memory
 * JdbcSqliteDriver (see `SyncCoordinatorTest`).
 *
 * Each upsert maps a domain model into the row shape that the generated
 * SQLDelight queries expect. We do the mapping here, not in the query
 * layer, so the SQL stays plain SQL.
 *
 * **Moved from `android/src/main/kotlin/.../di/SqlDelightSyncUpsertQueries.kt`
 * in Phase 4.A.4** so the iOS app can reuse the same facade when
 * composing `SyncCoordinator` against the shared `LibraryDatabaseFactory`.
 *
 * The Android module still references this class through its Hilt
 * `LibraryModule.provideSyncUpsertQueries` — no behaviour change there.
 */
class SqlDelightSyncUpsertQueries(
    private val db: MusicManagerDatabase,
) : SyncUpsertQueries {

    override fun transaction(block: () -> Unit) {
        db.transaction { block() }
    }

    override fun upsertArtist(artist: Artist, syncedAt: String) {
        // v2 schema added `artist.cover_path` so the iOS / Android UI
        // can render real artist artwork via `/api/library/covers/{path}`.
        // The backend's `ArtistDto.imagePath` is the filesystem path
        // we want here; we mirror it into both `cover_url` (legacy v1
        // column, kept for compatibility with the desktop's URL path)
        // and `cover_path` (v2 column, used by the mobile client).
        db.queriesQueries.upsertArtist(
            id = artist.id,
            name = artist.name,
            album_count = 0L,
            track_count = 0L,
            cover_url = artist.imagePath,
            cover_path = artist.imagePath,
            synced_at = parseIso8601(syncedAt),
        )
    }

    override fun upsertAlbum(album: Album, syncedAt: String) {
        db.queriesQueries.upsertAlbum(
            id = album.id,
            title = album.title,
            artist_id = album.artistId,
            artist_name = "", // denormalized at sync time; UI joins via Artist
            year = album.year?.toLong(),
            track_count = 0L,
            duration_ms = 0L,
            cover_url = album.coverPath,
            cover_path = null,
            is_downloaded = if (album.isCoverCached) 1L else 0L,
            synced_at = parseIso8601(syncedAt),
        )
    }

    override fun upsertTrack(track: Track, syncedAt: String) {
        db.queriesQueries.upsertTrack(
            id = track.id,
            title = track.title,
            album_id = track.albumId,
            album_title = "", // denormalized at sync time
            artist_id = track.artistId,
            artist_name = "",
            duration_ms = track.durationMs ?: 0L,
            track_number = track.trackNumber?.toLong(),
            bitrate = track.bitrate?.toLong(),
            codec = track.codec,
            stream_url = null,
            local_path = track.localPath,
            download_state = track.downloadState.name,
            synced_at = parseIso8601(syncedAt),
        )
    }

    override fun upsertPlaylist(playlist: Playlist, syncedAt: String) {
        db.queriesQueries.upsertPlaylist(
            id = playlist.id,
            name = playlist.name,
            description = playlist.description,
            track_count = 0L,
            cover_url = null,
            is_smart = if (playlist.isSmart) 1L else 0L,
            is_m3u_imported = if (playlist.isM3uImported) 1L else 0L,
            updated_at = parseIso8601(syncedAt),
            synced_at = parseIso8601(syncedAt),
        )
    }

    override fun upsertPlaylistTrack(playlistTrack: PlaylistTrack, syncedAt: String) {
        // The schema only has (playlist_id, track_id, position, added_at)
        // — no `synced_at`. We pass syncedAt to match the interface but
        // ignore it on the SQL side.
        @Suppress("UNUSED_PARAMETER")
        syncedAt
        db.queriesQueries.upsertPlaylistTrack(
            playlist_id = playlistTrack.playlistId,
            track_id = playlistTrack.trackId,
            position = playlistTrack.position.toLong(),
            added_at = parseIso8601(syncedAt),
        )
    }

    override fun deleteAllArtists() = db.queriesQueries.deleteAllArtists()
    override fun deleteAllAlbums() = db.queriesQueries.deleteAllAlbums()
    override fun deleteAllTracks() = db.queriesQueries.deleteAllTracks()
    override fun deleteAllPlaylists() = db.queriesQueries.deleteAllPlaylists()
    override fun deleteAllPlaylistTracks() = db.queriesQueries.deleteAllPlaylistTracks()

    override fun updateTrackDownload(
        trackId: Long,
        localPath: String?,
        state: com.wtm.musicmanager.domain.model.DownloadState,
    ) {
        db.queriesQueries.updateTrackDownload(
            local_path = localPath,
            download_state = state.name,
            id = trackId,
        )
    }

    /**
     * Convert an ISO 8601 string to an epoch-ms Long (the schema uses
     * INTEGER synced_at, NOT a string — see 1.sqm). Falls back to current
     * time on a malformed value so a bad server response can't corrupt
     * the local cursor permanently.
     */
    private fun parseIso8601(s: String): Long = try {
        kotlinx.datetime.Instant.parse(s).toEpochMilliseconds()
    } catch (e: Throwable) {
        kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    }
}