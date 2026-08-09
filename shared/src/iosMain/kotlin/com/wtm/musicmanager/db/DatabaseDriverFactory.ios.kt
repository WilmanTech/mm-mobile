package com.wtm.musicmanager.db

import app.cash.sqldelight.db.SqlDriver

/**
 * iOS actual. Wires up via NativeSqliteDriver in Phase 2+. For now we
 * throw so the app fails fast on iOS rather than silently using an
 * unconfigured DB.
 */
actual fun createDriver(): SqlDriver =
    TODO("iOS NativeSqliteDriver not yet implemented (Phase 2+)")
