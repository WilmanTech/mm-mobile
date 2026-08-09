package com.wtm.musicmanager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryQueryTest {

    @Test
    fun `default query has empty search and no filters`() {
        val q = LibraryQuery.Default
        assertEquals("", q.search)
        assertEquals(null, q.artistId)
        assertEquals(null, q.albumId)
        assertEquals(500L, q.limit)
        assertEquals(0L, q.offset)
    }

    @Test
    fun `limit must be between 1 and 5000`() {
        assertFailsWith<IllegalArgumentException> { LibraryQuery(limit = 0) }
        assertFailsWith<IllegalArgumentException> { LibraryQuery(limit = -1) }
        assertFailsWith<IllegalArgumentException> { LibraryQuery(limit = 5_001) }
        // Boundary values OK
        LibraryQuery(limit = 1)
        LibraryQuery(limit = 5_000)
    }

    @Test
    fun `offset must be non-negative`() {
        assertFailsWith<IllegalArgumentException> { LibraryQuery(offset = -1) }
        LibraryQuery(offset = 0)
        LibraryQuery(offset = 1_000_000)
    }

    @Test
    fun `copy with new search preserves other filters`() {
        val original = LibraryQuery(search = "", artistId = 42L, offset = 100L)
        val updated = original.copy(search = "bohem", offset = 0)
        assertEquals("bohem", updated.search)
        assertEquals(42L, updated.artistId)
        assertEquals(0L, updated.offset)
    }
}
