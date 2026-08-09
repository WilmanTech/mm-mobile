package com.wtm.musicmanager.data

import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Artist
import com.wtm.musicmanager.db.Track
import kotlinx.coroutines.flow.Flow

/**
 * Read-side of the music library. Backed by SQLDelight in production; the
 * ViewModel layer only sees this interface, so it can be swapped for a
 * fake in tests and for a remote/SMB source later.
 *
 * Reactive: every method returns a [Flow] that re-emits whenever the
 * underlying table changes (driven by SQLDelight's table-change
 * notifications + the coroutines extension's `mapToList`).
 */
interface LibraryRepository {
    fun observeTracks(query: LibraryQuery = LibraryQuery.Default): Flow<List<Track>>
    fun observeArtists(): Flow<List<Artist>>
    fun observeAlbumsByArtist(artistId: Long): Flow<List<Album>>
    fun observeTrackCount(): Flow<Long>

    suspend fun trackById(id: Long): Track?
    suspend fun artistById(id: Long): Artist?
    suspend fun albumById(id: Long): Album?
}
