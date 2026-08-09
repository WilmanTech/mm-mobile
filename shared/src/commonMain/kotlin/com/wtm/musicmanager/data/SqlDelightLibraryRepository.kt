package com.wtm.musicmanager.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Artist
import com.wtm.musicmanager.db.MusicManagerDatabase
import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * SQLDelight-backed [LibraryRepository]. All observation methods use
 * SQLDelight's `asFlow().mapToList` so they re-emit whenever a write
 * touches the underlying table.
 *
 * Point lookups (`trackById`, `artistById`, `albumById`) are
 * one-shot `executeAsOneOrNull()` calls wrapped in a single-emission
 * `Flow` via `map { listOf(it) }` so callers don't need to know whether
 * a method is reactive or not.
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

    override suspend fun trackById(id: Long): Track? =
        db.queriesQueries.selectAllTracks().executeAsList().firstOrNull { it.id == id }

    override suspend fun artistById(id: Long): Artist? =
        db.queriesQueries.selectArtistById(id).executeAsOneOrNull()

    override suspend fun albumById(id: Long): Album? =
        db.queriesQueries.selectAlbumById(id).executeAsOneOrNull()
}
