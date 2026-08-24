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

        /**
         * Convenience for the iOS detail screens that want to scope
         * the result to a single album. Swift's bridging of Kotlin
         * data-class constructors doesn't honour default arguments
         * (they all become `nil` / `0`), so SwiftUI detail screens
         * that need anything other than [Default] go through one of
         * these named factories instead of the raw constructor.
         *
         * Limits to 500 tracks — enough for any album in the demo
         * fixture, and within the `require(limit in 1..5_000)` check
         * in [init].
         */
        fun forAlbum(albumId: Long, limit: Long = 500): LibraryQuery =
            LibraryQuery(albumId = albumId, limit = limit)

        fun forArtist(artistId: Long, limit: Long = 500): LibraryQuery =
            LibraryQuery(artistId = artistId, limit = limit)
    }
}
