package com.wtm.musicmanager.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =====================================================================
// Sync DTOs — match /api/v1/sync/full and /api/v1/sync/changes contracts
// =====================================================================
// `updated_at` and `created_at` are ISO 8601 strings ('YYYY-MM-DDTHH:MM:SSZ')
// emitted by the backend's `_row_to_iso` reformatting of SQLite's
// 'YYYY-MM-DD HH:MM:SS' naive timestamps. Server assumes UTC.

@Serializable
data class ArtistDto(
    val id: Long,
    val name: String,
    @SerialName("musicbrainz_id") val musicbrainzId: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class AlbumDto(
    val id: Long,
    val title: String,
    @SerialName("artist_id") val artistId: Long,
    val year: Int? = null,
    val genre: String? = null,
    @SerialName("cover_path") val coverPath: String? = null,
    @SerialName("musicbrainz_id") val musicbrainzId: String? = null,
    @SerialName("folder_path") val folderPath: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class TrackDto(
    val id: Long,
    val title: String,
    @SerialName("album_id") val albumId: Long,
    @SerialName("artist_id") val artistId: Long,
    @SerialName("disc_number") val discNumber: Int? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("file_hash") val fileHash: String? = null,
    val acoustid: String? = null,
    @SerialName("musicbrainz_id") val musicbrainzId: String? = null,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("last_played") val lastPlayed: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("is_cover") val isCover: Boolean = false,
    @SerialName("is_live") val isLive: Boolean = false,
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class PlaylistDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    @SerialName("is_smart") val isSmart: Boolean = false,
    val rules: String? = null,
    @SerialName("m3u_path") val m3uPath: String? = null,
    @SerialName("is_m3u_imported") val isM3uImported: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class PlaylistTrackDto(
    @SerialName("playlist_id") val playlistId: Long,
    @SerialName("track_id") val trackId: Long,
    val position: Int,
    @SerialName("added_at") val addedAt: String? = null,
)

/**
 * Envelope for both /api/v1/sync/full and /api/v1/sync/changes. The mobile
 * client uses one parser because the shape is identical (only the filter
 * differs on the server).
 *
 * `server_time` is normally present (ISO 8601 string with 'Z' suffix) but
 * we mark it nullable defensively: the backend has been seen to return
 * `{"detail": "No library opened"}` (HTTP 400) when the desktop hasn't
 * opened a library yet. With `expectSuccess=false` on the Ktor client,
 * the 400 doesn't throw — but `.body()` then tries to parse the error
 * envelope as `SyncResponse` and throws on the missing `server_time`
 * field. Making the field optional + capturing the parse exception
 * upstream in [SyncCoordinator] lets us surface the real error to the
 * UI instead of crashing with a generic serialization message.
 *
 * `since` is only present on /sync/changes when called with a `since` arg.
 */
@Serializable
data class SyncResponse(
    @SerialName("server_time") val serverTime: String? = null,
    val since: String? = null,
    val artists: List<ArtistDto> = emptyList(),
    val albums: List<AlbumDto> = emptyList(),
    val tracks: List<TrackDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
    @SerialName("playlist_tracks") val playlistTracks: List<PlaylistTrackDto> = emptyList(),
)

// =====================================================================
// Pairing DTOs — match /api/pairing/start, /confirm, /status contracts
// =====================================================================
// The mobile flow:
//   1. start() → token + code + session_id + expires_in (seconds)
//   2. confirm(session_id, code, device_name, device_type)
//      → {status: "confirmed", token, device_name, paired_at: float}
//      The token from /start is the future bearer credential.
//   3. status(session_id) → {exists, confirmed: bool, expired: bool, ...}
//      Polled by the app until confirmed=true.
//   4. All subsequent requests use `Authorization: Bearer <token>`.

/**
 * Library stats from `/api/library/stats`. Used by the iOS Home
 * screen to render the summary card.
 */
@Serializable
data class LibraryStatsDto(
    @SerialName("total_tracks") val totalTracks: Long = 0,
    @SerialName("total_albums") val totalAlbums: Long = 0,
    @SerialName("total_artists") val totalArtists: Long = 0,
    @SerialName("total_duration_ms") val totalDurationMs: Long = 0,
    @SerialName("total_size_bytes") val totalSizeBytes: Long = 0,
    @SerialName("codecs") val codecs: List<CodecStatDto> = emptyList(),
    @SerialName("quality_breakdown") val qualityBreakdown: QualityBreakdownDto = QualityBreakdownDto(),
)

@Serializable
data class CodecStatDto(
    val codec: String = "",
    val count: Long = 0,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
)

@Serializable
data class QualityBreakdownDto(
    val lossless: QualityTier = QualityTier(),
    val high: QualityTier = QualityTier(),
)

@Serializable
data class QualityTier(
    val count: Long = 0,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
)

/**
 * Recently-played track row from `/api/library/recent/tracks`.
 * Already-flattened on the backend (artist + album + cover are
 * inline strings, not nested objects), so we mirror that shape.
 */
@Serializable
data class RecentTrackDto(
    val id: Long,
    @SerialName("album_id") val albumId: Long? = null,
    val title: String,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val bitrate: Int? = null,
    @SerialName("last_played") val lastPlayed: String? = null,
    @SerialName("play_count") val playCount: Int = 0,
    val artist: String,
    val album: String,
    @SerialName("album_cover") val albumCover: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("is_cover") val isCover: Boolean = false,
    @SerialName("is_live") val isLive: Boolean = false,
)

/**
 * Recently-played album from `/api/library/recent/albums`. Already
 * flattened — `artist` is an inline string.
 */
@Serializable
data class RecentAlbumDto(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val genre: String? = null,
    @SerialName("cover_path") val coverPath: String? = null,
    val artist: String,
    @SerialName("last_played") val lastPlayed: String? = null,
)

@Serializable
data class PairingStartRequest(
    @SerialName("device_type") val deviceType: String? = null,
)

@Serializable
data class PairingStartResponse(
    @SerialName("session_id") val sessionId: String,
    val token: String,
    val code: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class PairingConfirmRequest(
    @SerialName("session_id") val sessionId: String,
    val code: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_type") val deviceType: String? = null,
)

@Serializable
data class PairingConfirmResponse(
    val status: String,
    val token: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("paired_at") val pairedAt: Double,
)

/**
 * Flat dict from /api/pairing/status. `exists=false` means the session is
 * unknown/expired/evicted; the app treats that as "restart pairing".
 * `confirmed=true` is the signal to stop polling and persist the token.
 * `revoked=true` means the user explicitly logged the device out from the
 * desktop UI while we were pairing — abort.
 */
@Serializable
data class PairingStatusResponse(
    val exists: Boolean = false,
    @SerialName("session_id") val sessionId: String? = null,
    val confirmed: Boolean = false,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_type") val deviceType: String? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("confirmed_at") val confirmedAt: Double? = null,
    @SerialName("last_seen") val lastSeen: Double? = null,
    @SerialName("age_seconds") val ageSeconds: Double? = null,
    val expired: Boolean = false,
    val revoked: Boolean = false,
)
