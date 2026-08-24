package com.wtm.musicmanager.domain.model

import kotlinx.serialization.Serializable

/**
 * Core domain entities shared across platforms. Sourced from the
 * `/api/v1/sync/{full,changes}` payloads.
 *
 * `updatedAt` is an ISO 8601 string ('YYYY-MM-DDTHH:MM:SSZ') emitted by
 * the backend's `_row_to_iso` reformatting of SQLite's naive timestamps.
 * Use `parseIso8601()` to compare it against the local sync cursor.
 *
 * Persistence schema lives in `sqldelight/` — these are the *wire* models.
 * Repository layer maps between them.
 */

@Serializable
data class Artist(
    val id: Long,
    val name: String,
    val musicbrainzId: String? = null,
    val imagePath: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class Album(
    val id: Long,
    val title: String,
    val artistId: Long,
    val year: Int? = null,
    val genre: String? = null,
    val coverPath: String? = null,
    val musicbrainzId: String? = null,
    val folderPath: String? = null,
    val updatedAt: String? = null,
    /** Set when the cover art has been downloaded to local cache. */
    val isCoverCached: Boolean = false,
)

@Serializable
data class Track(
    val id: Long,
    val title: String,
    val albumId: Long,
    val artistId: Long,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val durationMs: Long? = null,
    val bitrate: Double? = null,
    val codec: String? = null,
    val acoustId: String? = null,
    val musicbrainzId: String? = null,
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    val isLive: Boolean = false,
    val addedAt: String? = null,
    val updatedAt: String? = null,
    val localPath: String? = null, // populated when downloaded
    val downloadState: DownloadState = DownloadState.NotDownloaded,
)

@Serializable
enum class DownloadState {
    NotDownloaded,
    Queued,
    Downloading, // progress in [0, 100] is held separately
    Downloaded,
    Failed,
}

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    val description: String? = null,
    val isSmart: Boolean = false,
    val rules: String? = null,
    val m3uPath: String? = null,
    val isM3uImported: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class PlaylistTrack(
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
    val addedAt: String? = null,
)
