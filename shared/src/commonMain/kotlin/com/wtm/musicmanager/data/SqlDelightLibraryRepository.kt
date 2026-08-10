package com.wtm.musicmanager.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Artist
import com.wtm.musicmanager.db.MusicManagerDatabase
import com.wtm.musicmanager.db.Playlist
import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * SQLDelight-backed [LibraryRepository]. All observation methods use
 * SQLDelight's `asFlow().mapToList` so they re-emit whenever a write
 * touches the underlying table.
 *
 * Point lookups (`trackById`, `artistById`, `albumById`) are
 * one-shot `executeAsOneOrNull()` calls.
 */
class SqlDelightLibraryRepository(
    private val db: MusicManagerDatabase,
) : LibraryRepository {

    override fun observeTracks(query: LibraryQuery): Flow<List<Track>> {
        val flow = when {
            query.albumId != null -> db.queriesQueries.selectTracksByAlbum(query.albumId)
            query.artistId != null -> db.queriesQueries.selectTracksByArtist(query.artistId)
            query.search.isNotBlank() -> {
                val needle = "%${query.search.trim()}%"
                db.queriesQueries.searchTracks(needle, needle, needle)
            }
            else -> db.queriesQueries.selectAllTracks()
        }
        return flow.asFlow().mapToList(Dispatchers.IO)
    }

    override fun observeArtists(): Flow<List<Artist>> =
        db.queriesQueries.selectAllArtists().asFlow().mapToList(Dispatchers.IO)

    override fun observeAlbumsByArtist(artistId: Long): Flow<List<Album>> =
        db.queriesQueries.selectAlbumsByArtist(artistId).asFlow().mapToList(Dispatchers.IO)

    override fun observeTrackCount(): Flow<Long> =
        db.queriesQueries.selectTrackCount().asFlow().mapToOne(Dispatchers.IO)

    override fun observeAlbums(query: LibraryQuery): Flow<List<Album>> {
        val flow = if (query.search.isNotBlank()) {
            val needle = "%${query.search.trim()}%"
            db.queriesQueries.searchAlbums(needle, needle)
        } else {
            db.queriesQueries.selectAllAlbums()
        }
        return flow.asFlow().mapToList(Dispatchers.IO)
    }

    override fun observePlaylists(query: LibraryQuery): Flow<List<Playlist>> {
        val flow = if (query.search.isNotBlank()) {
            val needle = "%${query.search.trim()}%"
            db.queriesQueries.searchPlaylists(needle, needle)
        } else {
            db.queriesQueries.selectAllPlaylists()
        }
        return flow.asFlow().mapToList(Dispatchers.IO)
    }

    override fun searchArtists(query: String): Flow<List<Artist>> {
        val flow = if (query.isNotBlank()) {
            val needle = "%${query.trim()}%"
            db.queriesQueries.searchArtists(needle)
        } else {
            // Empty search → fall back to the full list (the screen is in
            // "browse" mode). The query surface is reused so we don't have
            // a second Flow for "all artists" — keeps the test fake
            // smaller.
            db.queriesQueries.selectAllArtists()
        }
        return flow.asFlow().mapToList(Dispatchers.IO)
    }

    override suspend fun trackById(id: Long): Track? =
        db.queriesQueries.selectAllTracks().executeAsList().firstOrNull { it.id == id }

    override suspend fun artistById(id: Long): Artist? =
        db.queriesQueries.selectArtistById(id).executeAsOneOrNull()

    override suspend fun albumById(id: Long): Album? =
        db.queriesQueries.selectAlbumById(id).executeAsOneOrNull()
}
