package com.wtm.musicmanager.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.wtm.musicmanager.db.MusicManagerDatabase

/**
 * iOS actual of [createSqlDriver]. Uses `NativeSqliteDriver` (the
 * SQLite3 C library + the SQLDelight native-driver Kotlin bindings)
 * with a **persistent file** backing store at
 * `${AppPathHolder.require()}/musicmanager.db`.
 *
 * **Phase 3.D follow-up (2026-08-23): migrated from bare-name to
 * an absolute path.** The previous form —
 *   ```kotlin
 *   NativeSqliteDriver(MusicManagerDatabase.Schema, "musicmanager.db")
 *   ```
 * was documented as "in-memory" in Phase 4.A.4, but NativeSqliteDriver
 * with a bare name actually creates a temp file in the process CWD,
 * which on iOS Simulator is the app sandbox and on iOS device is
 * `/`. The DB *appeared* in-memory between cold launches because the
 * CWD changed every launch (different sandbox path on simulator,
 * different inode namespace on device). The user reported taps that
 * referenced stale track ids (e.g. `/api/stream/596` 404 after a
 * backend re-scan that re-numbered tracks starting at id 26175)
 * because the local cache survived with ids that no longer existed
 * on the server.
 *
 * **Migration** — once a release ships with this fix, users with a
 * stale `musicmanager.db` (Phase 4.A.4..Phase 3.D) will see a one-
 * time "empty library" because the path now points at a different
 * (empty) file. The fix is a full re-sync; the user lands on
 * LibraryScreen which triggers `rebuildLibraryGraph()` →
 * `syncFull()` after pairing. Acceptable cost vs. the alternative
 * (silent stale state forever).
 *
 * **Swift composition root** — see
 * `ios/MusicManager/MusicManagerApp.swift`. It calls
 * `AppPathHolder.set(...)` during init with the directory path it
 * obtained via `FileManager.default.urls(for: .applicationSupportDirectory, ...).first`
 * after appending the bundle id subdirectory and creating it with
 * `FileManager.createDirectory(at:withIntermediateDirectories:true)`.
 */
actual fun createSqlDriver(): SqlDriver {
    val dbDir = AppPathHolder.require()
    val dbName = "musicmanager.db"
    // v2026-08-24 fix: SQLDelight 2.0 NativeSqliteDriver rejects `name`
    // values that contain path separators
    // ("IllegalArgumentException: File … contains a path separator"
    //  — thrown from co.touchlab.sqliter.DatabaseManager when name has
    //  '/'). The constructor signature is
    //   NativeSqliteDriver(schema, name, …, onConfiguration)
    // where `name` must be the bare filename; the parent directory is
    // configured via the `onConfiguration` callback as
    //   DatabaseConfiguration.Extended(basePath = <dir>)
    // (DatabaseConfiguration.Extended is the data class that owns
    //  basePath, foreignKeyConstraints, pageSize, etc.). Default
    // basePath is the platform default location (Application Support
    // on iOS), but we set it explicitly to the AppPathHolder value
    // so the file lives in the directory Swift created via
    // FileManager.createDirectory during init.
    val onConfig: (co.touchlab.sqliter.DatabaseConfiguration) -> co.touchlab.sqliter.DatabaseConfiguration =
        { config ->
            config.copy(
                extendedConfig = config.extendedConfig.copy(basePath = dbDir),
            )
        }
    return NativeSqliteDriver(
        schema = MusicManagerDatabase.Schema,
        name = dbName,
        onConfiguration = onConfig,
    )
}