package com.wtm.musicmanager.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific factory for the SQLDelight driver. The actual driver
 * differs per platform:
 * - androidMain: AndroidSqliteDriver
 * - iosMain: NativeSqliteDriver
 * - jvmMain: JdbcSqliteDriver (test-only target)
 *
 * Resolution happens through [createDriver] (an expect/actual) so callers
 * in commonMain just ask for a driver without knowing the platform.
 */
expect fun createDriver(): SqlDriver
