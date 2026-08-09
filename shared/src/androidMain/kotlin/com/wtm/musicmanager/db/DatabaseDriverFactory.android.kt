package com.wtm.musicmanager.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver

/**
 * Android implementation: SQLite via AndroidSqliteDriver (bundled native,
 * zero extra dependencies beyond what SQLDelight already pulls).
 */
actual fun createDriver(): SqlDriver {
    // The actual Application context is supplied via initAndroidDriver(),
    // called from MusicManagerApp.onCreate(). createDriver() throws if it
    // runs before init. This keeps the expect/actual factory platform-pure.
    return androidDriver!!
}

private var androidDriver: SqlDriver? = null

fun initAndroidDriver(context: Context) {
    if (androidDriver == null) {
        androidDriver = AndroidSqliteDriver(
            schema = MusicManagerDatabase.Schema,
            context = context.applicationContext,
            name = "musicmanager.db",
        )
    }
}
