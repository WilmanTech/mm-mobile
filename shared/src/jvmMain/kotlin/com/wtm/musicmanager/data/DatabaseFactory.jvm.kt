package com.wtm.musicmanager.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.wtm.musicmanager.db.MusicManagerDatabase

/**
 * JVM actual of [createSqlDriver]. Used by `:shared:jvmTest` (which
 * runs the SyncCoordinator + LibraryRepository suites on the host
 * JVM via Kotest + the JDBC SQLite driver).
 *
 * The driver opens an **in-memory** database — tests run in
 * transactions and don't persist between cases. Production code
 * never hits this actual; Android uses `createSqlDriver().android`
 * and iOS uses `createSqlDriver().ios`.
 *
 * The schema is migrated via `MusicManagerDatabase.Schema.create()`
 * so the tests don't need to ship the migration SQL inline. The
 * migrations are read from the same `1.sqm` file as production.
 */
actual fun createSqlDriver(): SqlDriver =
    JdbcSqliteDriver(url = "jdbc:sqlite::memory:").also { driver ->
        MusicManagerDatabase.Schema.create(driver)
    }