package com.wtm.musicmanager.data

import kotlin.concurrent.Volatile

/**
 * Holder for the platform-specific "where to put app-private files"
 * path. On Android this is `Context.filesDir` (set during
 * `Application.onCreate()`); on iOS it's the user's
 * `Application Support/com.wtm.musicmanager.MusicManager` directory
 * (set from `MusicManagerApp.init()`).
 *
 * The Kotlin data layer (specifically `createSqlDriver()`) reads
 * the path via [require] when constructing the SQLDelight driver.
 *
 * **Why a holder and not a context object passed through DI?**
 * The path is a one-shot piece of environment that doesn't change
 * for the lifetime of the process. A `@Volatile var` on a singleton
 * keeps the call sites trivial — no constructor wiring, no Hilt
 * bindings to extend, no need to thread the path through every
 * factory that ends up creating a driver.
 *
 * **Initialization is mandatory** — calling [require] before [set]
 * throws `IllegalStateException`. This is intentional: the iOS app
 * crashes loudly on a missing path instead of silently writing to
 * the wrong location. The previous `NativeSqliteDriver(...,
 * "musicmanager.db")` form (Phase 4.A.4 through Phase 3.D) wrote
 * the DB to the process CWD, which on iOS is `/` and silently
 * failed to persist between launches — Phase 3.D smoke follow-up
 * 2026-08-23 found this when taps on the iPhone hit `404` for
 * track ids that no longer existed in the backend DB but were
 * still cached locally.
 *
 * **Android counterpart** — see
 * `shared/src/androidMain/kotlin/com/wtm/musicmanager/data/AppContextHolder.kt`.
 * Both sides expose the same shape (set/require/isInitialized) so
 * the common code can call `AppPathHolder.require()` without knowing
 * which platform it's on.
 */
object AppPathHolder {
    @Volatile
    private var path: String? = null

    /**
     * Called by the platform composition root during startup. The
     * caller is responsible for creating the directory if it
     * doesn't exist before calling this; we don't do it here
     * because the platform APIs differ (Android `Context.filesDir`
     * auto-creates, iOS `FileManager` doesn't).
     */
    fun set(path: String) {
        this.path = path
    }

    /**
     * Read the persisted path. Throws if [set] was never called.
     */
    fun require(): String =
        path ?: error(
            "AppPathHolder not initialised — call AppPathHolder.set() " +
                "from the platform composition root (Application.onCreate " +
                "on Android, MusicManagerApp.init() on iOS) before any " +
                "DB code runs."
        )

    /** True once [set] has been called. */
    val isInitialized: Boolean
        get() = path != null
}