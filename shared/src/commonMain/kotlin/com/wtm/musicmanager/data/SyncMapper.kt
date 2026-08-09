package com.wtm.musicmanager.data

import com.wtm.musicmanager.domain.model.Album
import com.wtm.musicmanager.domain.model.Artist
import com.wtm.musicmanager.domain.model.DownloadState
import com.wtm.musicmanager.domain.model.Playlist
import com.wtm.musicmanager.domain.model.Track
import com.wtm.musicmanager.network.AlbumDto
import com.wtm.musicmanager.network.ArtistDto
import com.wtm.musicmanager.network.PlaylistDto
import com.wtm.musicmanager.network.TrackDto

fun ArtistDto.toDomain(): Artist = Artist(
    id = id,
    name = name,
    albumCount = albumCount,
    trackCount = trackCount,
    coverUrl = coverUrl,
)

fun AlbumDto.toDomain(): Album = Album(
    id = id,
    title = title,
    artistId = artistId,
    artistName = artistName,
    year = year,
    trackCount = trackCount,
    durationMs = durationMs,
    coverUrl = coverUrl,
)

fun TrackDto.toDomain(): Track = Track(
    id = id,
    title = title,
    albumId = albumId,
    albumTitle = albumTitle,
    artistId = artistId,
    artistName = artistName,
    durationMs = durationMs,
    trackNumber = trackNumber,
    bitrate = bitrate,
    codec = codec,
    streamUrl = streamUrl,
)

fun PlaylistDto.toDomain(): Playlist = Playlist(
    id = id,
    name = name,
    description = description,
    trackCount = trackCount,
    coverUrl = coverUrl,
    isSmart = isSmart,
    isM3uImported = isM3uImported,
    updatedAt = updatedAt,
)

object SyncMapper {
    fun Artist.toRow(syncedAt: Long): List<Any?> = listOf(
        id, name, albumCount, trackCount, coverUrl, syncedAt,
    )

    fun Album.toRow(syncedAt: Long): List<Any?> = listOf(
        id, title, artistId, artistName, year, trackCount, durationMs,
        coverUrl, coverPath, if (isDownloaded) 1 else 0, syncedAt,
    )

    fun Track.toRow(syncedAt: Long): List<Any?> = listOf(
        id, title, albumId, albumTitle, artistId, artistName,
        durationMs, trackNumber, bitrate, codec, streamUrl, localPath,
        downloadState.name, syncedAt,
    )

    fun Playlist.toRow(syncedAt: Long): List<Any?> = listOf(
        id, name, description, trackCount, coverUrl,
        if (isSmart) 1 else 0, if (isM3uImported) 1 else 0,
        updatedAt, syncedAt,
    )
}

fun parseDownloadState(s: String): DownloadState =
    runCatching { DownloadState.valueOf(s) }.getOrDefault(DownloadState.NotDownloaded)
