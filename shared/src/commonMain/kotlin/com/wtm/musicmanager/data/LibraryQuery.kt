package com.wtm.musicmanager.data

/**
 * Filter/sort/pagination parameters for the library list view.
 *
 * - [search]: free-text search across title/album/artist (case-insensitive
 *   `LIKE` query). Empty string means "no search".
 * - [artistId]: when non-null, restrict to tracks of a single artist.
 * - [albumId]: when non-null, restrict to tracks of a single album.
 * - [limit] / [offset]: simple pagination. Offset-based is fine for the
 *   initial LibraryScreen; cursor-based pagination can come later when the
 *   library exceeds a few thousand tracks.
 */
data class LibraryQuery(
    val search: String = "",
    val artistId: Long? = null,
    val albumId: Long? = null,
    val limit: Long = 500,
    val offset: Long = 0,
) {
    init {
        require(limit in 1..5_000) { "limit must be 1..5000 (got $limit)" }
        require(offset >= 0) { "offset must be >= 0 (got $offset)" }
    }

    companion object {
        val Default = LibraryQuery()
    }
}
