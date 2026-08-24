package com.wtm.musicmanager.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver

/**
 * Android actual. The driver is constructed on first access and cached.
 * Needs a [Context] (preferably the Application context, which lives for
 * the process lifetime), so the [androidDriver] initializer is public —
 * `MusicManagerApp.onCreate()` calls [initAndroidDriver] before any
 * Hilt-injected singleton asks the DB module for a driver.
 *
 * For Hilt-aware injection, prefer [createDriver] — the Hilt module in
 * `android/.../di/LibraryModule.kt` uses @ApplicationContext to wire
 * the driver directly, which avoids the lateinit/null trap of having
 * the driver in a top-level `var`.
 */
private var androidDriver: SqlDriver? = null

actual fun createDriver(): SqlDriver =
    androidDriver ?: error(
        "AndroidSqliteDriver not initialized — call DatabaseDriverFactory.initAndroidDriver(context) " +
            "in Application.onCreate() before any DB access."
    )

fun initAndroidDriver(context: Context) {
    if (androidDriver == null) {
        androidDriver = AndroidSqliteDriver(
            schema = MusicManagerDatabase.Schema,
            context = context.applicationContext,
            name = "musicmanager.db",
        )
    }
}
