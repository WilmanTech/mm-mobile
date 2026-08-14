package com.wtm.musicmanager.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.wtm.musicmanager.db.MusicManagerDatabase

/**
 * Android actual of [createSqlDriver]. Same wiring as
 * `LibraryModule.provideDatabase` on the Android app module, but
 * exposed here so the iOS app can compose the same factory without
 * duplicating the SQLite path / filename conventions.
 *
 * On Android the database lives at
 * `/data/data/<package>/databases/musicmanager.db` (the OS-managed
 * app-private dir), so it survives process restarts and backs the
 * download manager's local_path lookups in Phase 3.
 */
actual fun createSqlDriver(): SqlDriver {
    val context: Context = AppContextHolder.get()
    return AndroidSqliteDriver(
        schema = MusicManagerDatabase.Schema,
        context = context,
        name = "musicmanager.db",
    )
}

/**
 * Process-wide Application Context holder, set by the Android app
 * during startup (see `MusicManagerApplication.kt`). Needed because
 * `expect` factories in `commonMain` can't receive Android `Context`
 * parameters — the actual constructs the driver from this global
 * holder instead.
 *
 * This is intentionally a `var` set once at startup. The factory is
 * only invoked from `MusicManagerDatabaseFactory.create()` which runs
 * after `onCreate()` finishes, so the holder is always populated when
 * it's read.
 *
 * Why a holder instead of `LocalContext.current`?
 *   That only works inside Compose. The factory is called from Hilt
 *   graph construction (also outside Compose) and from the iOS app
 *   composition root (no Compose at all).
 */
object AppContextHolder {
    @Volatile
    private var context: Context? = null

    fun set(context: Context) {
        this.context = context.applicationContext
    }

    fun get(): Context = context
        ?: error("AppContextHolder not initialised — call set() from Application.onCreate()")
}