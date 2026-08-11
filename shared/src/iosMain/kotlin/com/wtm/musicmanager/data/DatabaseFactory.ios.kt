package com.wtm.musicmanager.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.wtm.musicmanager.db.MusicManagerDatabase

/**
 * iOS actual of [createSqlDriver]. Uses `NativeSqliteDriver` (the
 * SQLite3 C library + the SQLDelight native-driver Kotlin bindings)
 * with an **in-memory** backing store for Phase 4.A.4.
 *
 * Why in-memory instead of a file path?
 *   - The app's documents dir wiring is platform-specific and
 *     orthogonal to the data layer — wrapping it here would couple
 *     this factory to NSSearchPathForDirectoriesInDomains, which
 *     belongs in the Swift composition root.
 *   - Phase 4.A.4 only exercises the read-side library (sync +
 *     observe + browse). Downloads (Phase 3) will need a persistent
 *     store to write audio files to, at which point this factory
 *     grows a Swift-provided path parameter (via a `PlatformContext`
 *     holder, mirroring `AppContextHolder` on Android).
 *   - In-memory means every app launch starts empty — the iOS app
 *     triggers `SyncCoordinator.syncFull()` right after pairing, so
 *     the local cache is repopulated within ~1s on the demo library.
 *
 * Migration to file-backed (Phase 3):
 *   ```kotlin
 *   actual fun createSqlDriver(): SqlDriver {
 *       val path = AppPathHolder.require() + "/musicmanager.db"
 *       return NativeSqliteDriver(MusicManagerDatabase.Schema, path)
 *   }
 *   ```
 *   The interface stays stable; only this actual changes.
 */
actual fun createSqlDriver(): SqlDriver =
    NativeSqliteDriver(MusicManagerDatabase.Schema, "musicmanager.db")