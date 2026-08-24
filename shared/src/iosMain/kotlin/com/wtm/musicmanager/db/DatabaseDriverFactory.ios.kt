package com.wtm.musicmanager.db

import app.cash.sqldelight.db.SqlDriver

/**
 * iOS actual. Wires up via NativeSqliteDriver in Phase 3.A. For now we
 * throw so the app fails fast on iOS rather than silently using an
 * unconfigured DB.
 *
 * Phase 3.B (player-only smoke test) does NOT need the DB — the Swift
 * side uses `LibraryMockScreen` for the LibraryScreen surface and
 * `PlayerEntry.shared.make(api:...)` for the player. The DB wiring
 * lands when `LibraryEntry.make(host:port:tokenStore:)` is promoted
 * from the placeholder stub in `data/LibraryEntry.kt` to its full
 * Phase 4.A.4 wiring.
 */
actual fun createDriver(): SqlDriver =
    TODO("iOS NativeSqliteDriver not yet implemented (Phase 3.A)")