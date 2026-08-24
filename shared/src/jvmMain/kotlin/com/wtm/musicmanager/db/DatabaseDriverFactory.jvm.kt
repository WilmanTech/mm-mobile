package com.wtm.musicmanager.db

import app.cash.sqldelight.db.SqlDriver

/**
 * JVM actual for unit tests. Tests that need a driver use JdbcSqliteDriver
 * directly (see SyncCoordinatorTest). This stub exists only so the
 * expect/actual graph compiles for the JVM target.
 */
actual fun createDriver(): SqlDriver =
    TODO("Use JdbcSqliteDriver directly in jvmTest — createDriver() is for production code paths")
