package com.wtm.musicmanager.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ArtistDto(
    val id: Long,
    val name: String,
    @SerialName("album_count") val albumCount: Int = 0,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class AlbumDto(
    val id: Long,
    val title: String,
    @SerialName("artist_id") val artistId: Long,
    @SerialName("artist_name") val artistName: String,
    val year: Int? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class TrackDto(
    val id: Long,
    val title: String,
    @SerialName("album_id") val albumId: Long,
    @SerialName("album_title") val albumTitle: String,
    @SerialName("artist_id") val artistId: Long,
    @SerialName("artist_name") val artistName: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("track_number") val trackNumber: Int? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class PlaylistDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("is_smart") val isSmart: Boolean = false,
    @SerialName("is_m3u_imported") val isM3uImported: Boolean = false,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class PlaylistTrackDto(
    @SerialName("playlist_id") val playlistId: Long,
    @SerialName("track_id") val trackId: Long,
    val position: Int,
    @SerialName("added_at") val addedAt: Long,
)

@Serializable
data class FullSyncResponse(
    val artists: List<ArtistDto> = emptyList(),
    val albums: List<AlbumDto> = emptyList(),
    val tracks: List<TrackDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
    @SerialName("playlist_tracks") val playlistTracks: List<PlaylistTrackDto> = emptyList(),
    @SerialName("server_time") val serverTime: Long = 0L,
)

@Serializable
data class ChangesSyncResponse(
    val artists: List<ArtistDto> = emptyList(),
    val albums: List<AlbumDto> = emptyList(),
    val tracks: List<TrackDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
    @SerialName("playlist_tracks") val playlistTracks: List<PlaylistTrackDto> = emptyList(),
    @SerialName("server_time") val serverTime: Long = 0L,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class StartPairingRequest(
    @SerialName("device_type") val deviceType: String = "mobile",
    @SerialName("device_name") val deviceName: String = "Music Manager Mobile",
)

@Serializable
data class StartPairingResponse(
    @SerialName("session_id") val sessionId: String,
    val code: String,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

@Serializable
data class PairingStatusResponse(
    val status: String,
    @SerialName("api_token") val apiToken: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("server_label") val serverLabel: String? = null,
)

@Serializable
data class ConfirmPairingRequest(
    @SerialName("session_id") val sessionId: String,
    val code: String,
    @SerialName("device_name") val deviceName: String,
)
