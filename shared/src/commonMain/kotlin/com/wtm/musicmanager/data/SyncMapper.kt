package com.wtm.musicmanager.data

import com.wtm.musicmanager.domain.model.Album
import com.wtm.musicmanager.domain.model.Artist
import com.wtm.musicmanager.domain.model.Playlist
import com.wtm.musicmanager.domain.model.PlaylistTrack
import com.wtm.musicmanager.domain.model.Track
import com.wtm.musicmanager.network.AlbumDto
import com.wtm.musicmanager.network.ArtistDto
import com.wtm.musicmanager.network.PlaylistDto
import com.wtm.musicmanager.network.PlaylistTrackDto
import com.wtm.musicmanager.network.TrackDto

fun ArtistDto.toDomain(): Artist = Artist(
    id = id,
    name = name,
    musicbrainzId = musicbrainzId,
    imagePath = imagePath,
    updatedAt = updatedAt,
)

fun AlbumDto.toDomain(): Album = Album(
    id = id,
    title = title,
    artistId = artistId,
    year = year,
    genre = genre,
    coverPath = coverPath,
    musicbrainzId = musicbrainzId,
    folderPath = folderPath,
    updatedAt = updatedAt,
)

fun TrackDto.toDomain(): Track = Track(
    id = id,
    title = title,
    albumId = albumId,
    artistId = artistId,
    discNumber = discNumber,
    trackNumber = trackNumber,
    durationMs = durationMs,
    bitrate = bitrate,
    codec = codec,
    acoustId = acoustid,
    musicbrainzId = musicbrainzId,
    playCount = playCount,
    isFavorite = isFavorite,
    isLive = isLive,
    addedAt = addedAt,
    updatedAt = updatedAt,
)

fun PlaylistDto.toDomain(): Playlist = Playlist(
    id = id,
    name = name,
    description = description,
    isSmart = isSmart,
    rules = rules,
    m3uPath = m3uPath,
    isM3uImported = isM3uImported,
    updatedAt = updatedAt,
)

fun PlaylistTrackDto.toDomain(): PlaylistTrack = PlaylistTrack(
    playlistId = playlistId,
    trackId = trackId,
    position = position,
    addedAt = addedAt,
)
