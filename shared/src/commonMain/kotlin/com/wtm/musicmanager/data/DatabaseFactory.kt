package com.wtm.musicmanager.data

import app.cash.sqldelight.db.SqlDriver
import com.wtm.musicmanager.db.MusicManagerDatabase

/**
 * Platform-specific factory that produces the [SqlDriver] backing the
 * [MusicManagerDatabase]. Each platform actual opens SQLite on a
 * platform-native location:
 *   - Android: `AndroidSqliteDriver` against the app's databases dir.
 *   - iOS: `NativeDriver` against an in-memory store (Phase 4.A.4
 *     ships with in-memory only — the FileStorageDriver variant lands
 *     with the download manager in Phase 3 so downloaded tracks can
 *     survive app restarts).
 *
 * Why an `expect` factory and not `expect val driver`?
 *   The driver needs the schema + the platform's path/Context, so a
 *   factory is the simplest call site. Consumers go through
 *   [MusicManagerDatabaseFactory.create] which composes this with the
 *   repository / sync coordinator wiring.
 *
 * Why is this in `data/` instead of `db/`?
 *   It's the data layer's responsibility to own how the database is
 *   instantiated — keeping it here avoids having the iOS app reach into
 *   the SQLDelight-generated package directly.
 */
expect fun createSqlDriver(): SqlDriver

/**
 * Single entry point for constructing the [MusicManagerDatabase] +
 * repositories. Use from `AppCoordinator.makeDatabase()` on iOS and
 * from `LibraryModule.provideDatabase` on Android — the latter still
 * constructs its own Hilt provider so the function below is the
 * canonical source for the contract.
 *
 * Returns a small immutable container rather than a DI graph because
 * the iOS app owns its own composition root (we don't ship a Hilt
 * equivalent to iOS).
 */
object MusicManagerDatabaseFactory {

    data class Graph(
        val database: MusicManagerDatabase,
        val libraryRepository: LibraryRepository,
        val syncUpsertQueries: SyncUpsertQueries,
    )

    fun create(): Graph {
        val driver = createSqlDriver()
        val database = MusicManagerDatabase(driver)
        return Graph(
            database = database,
            libraryRepository = SqlDelightLibraryRepository(database),
            syncUpsertQueries = SqlDelightSyncUpsertQueries(database),
        )
    }
}