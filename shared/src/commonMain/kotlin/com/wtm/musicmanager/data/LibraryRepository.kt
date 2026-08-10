package com.wtm.musicmanager.data

import com.wtm.musicmanager.db.Album
import com.wtm.musicmanager.db.Artist
import com.wtm.musicmanager.db.Playlist
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
 *
 * Phase 2.1 added album / playlist / artist search methods used by
 * [SearchScreen]. Empty / blank `query.search` returns the full list so
 * the screen can show "browse" before the user types anything.
 */
interface LibraryRepository {
    fun observeTracks(query: LibraryQuery = LibraryQuery.Default): Flow<List<Track>>
    fun observeArtists(): Flow<List<Artist>>
    fun observeAlbumsByArtist(artistId: Long): Flow<List<Album>>
    fun observeTrackCount(): Flow<Long>

    // Phase 2.1: search-backed tab queries. Empty search returns the
    // full list (browse mode); non-empty applies the LIKE filter.
    fun observeAlbums(query: LibraryQuery = LibraryQuery.Default): Flow<List<Album>>
    fun observePlaylists(query: LibraryQuery = LibraryQuery.Default): Flow<List<Playlist>>
    fun searchArtists(query: String): Flow<List<Artist>>

    suspend fun trackById(id: Long): Track?
    suspend fun artistById(id: Long): Artist?
    suspend fun albumById(id: Long): Album?
}
