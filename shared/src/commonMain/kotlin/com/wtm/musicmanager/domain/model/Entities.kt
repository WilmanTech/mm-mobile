package com.wtm.musicmanager.domain.model

import kotlinx.serialization.Serializable

/**
 * Core domain entities shared across platforms. Mirrors MusicManager backend's
 * `/api/library/...` payloads with snake_case JSON via kotlinx.serialization.
 *
 * Persistence schema lives in `sqldelight/` — these are the *wire* models.
 * Repository layer maps between them.
 */

@Serializable
data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
    val coverUrl: String? = null,
)

@Serializable
data class Album(
    val id: Long,
    val title: String,
    val artistId: Long,
    val artistName: String,
    val year: Int? = null,
    val trackCount: Int = 0,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val coverPath: String? = null, // local cache path when downloaded
    val isDownloaded: Boolean = false,
)

@Serializable
data class Track(
    val id: Long,
    val title: String,
    val albumId: Long,
    val albumTitle: String,
    val artistId: Long,
    val artistName: String,
    val durationMs: Long,
    val trackNumber: Int? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    val streamUrl: String? = null, // signed/authorized URL from backend
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
    val trackCount: Int = 0,
    val coverUrl: String? = null,
    val isSmart: Boolean = false,
    val isM3uImported: Boolean = false,
    val updatedAt: Long? = null, // epoch ms — used for sync deltas
)
