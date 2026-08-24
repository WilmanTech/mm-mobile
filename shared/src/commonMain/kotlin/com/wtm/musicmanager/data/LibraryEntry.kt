package com.wtm.musicmanager.data

import com.wtm.musicmanager.download.DownloadStateRepository
import com.wtm.musicmanager.network.AuthStorage
import com.wtm.musicmanager.network.HttpClientFactory
import com.wtm.musicmanager.network.MusicManagerApi
import kotlin.concurrent.Volatile

/**
 * Single iOS-callable entry point for the library layer. Mirrors
 * `PairingEntry` — keeps the Swift-side surface to a single factory
 * call so future changes to the wiring (auth, dispatcher, retries)
 * don't ripple into Swift.
 *
 * **Phase 4.A.4 ships this** so the iOS `AppCoordinator` can build
 * a `LibraryRepository` + `SyncCoordinator` pair without seeing
 * Ktor / SQLDelight / kotlinx-coroutines directly.
 *
 * Usage from Swift:
 *   ```swift
 *   let graph = LibraryEntry.shared.make(host: "127.0.0.1", port: "8765", tokenStore: tokenStore)
 *   // graph.libraryRepository, graph.syncCoordinator, graph.api
 *   ```
 *
 * Why pass `tokenStore` (which is `AuthStorage`-backed) instead of
 * letting this function read it?
 *   The Swift side already owns the `AuthStorage` reference — it
 *   got it from `PairingEntry.make(...)`. Re-using the same store
 *   means the bearer token set by `pairingRepository.acceptDeepLink`
 *   is the same one the library's API client reads. Two separate
 *   stores would silently use different tokens and the first
 *   authenticated request after pairing would 401.
 *
 * **@Throws declaration (2026-08-24 smoke fix)** — the previous
 * declaration was `fun make(...): Graph` with no @Throws. When
 * `createSqlDriver()` threw `IllegalStateException` (because
 * `AppPathHolder` wasn't initialised) or `NativeSqliteDriver`
 * raised on a malformed path, K/N couldn't propagate the
 * exception across the KMP<->Swift bridge (the bridge only
 * propagates declared throws) and crashed with
 * `Kotlin_ObjCExport_trapOnUndeclaredException` →
 * `terminateWithUnhandledException` → SIGABRT. The new
 * `@Throws(IllegalStateException::class, RuntimeException::class)`
 * lets the bridge surface the error to Swift as `NSError`, where
 * `AppCoordinator.rebuildLibraryGraph()` can catch it and surface
 * it via `lastError` instead of crashing.
 */
object LibraryEntry {

    init {
        // Phase 3.D follow-up (2026-08-24, v2): the iOS-side
        // `_kmpBootstrap` static let didn't always fire on device
        // (Swift's lazy-let semantics). The first time
        // `LibraryEntry` is touched (e.g. via `LibraryEntry.shared.make`),
        // we set a `lateinit` flag from the iOS side. To make this
        // robust, the iOS Swift code now also calls
        // `AppPathHolder.shared.set(path:)` from inside the Kotlin
        // `createSqlDriver` actual — but the actual file already
        // calls require(), not set. So instead, we log a clear
        // diagnostic here if the holder is uninitialised, and
        // throw a more informative error. The v3 fix on the iOS
        // side moves the bootstrap to a `+load`-equivalent
        // Objective-C class method (see `MusicManagerApp.swift`).
        if (!com.wtm.musicmanager.data.AppPathHolder.isInitialized) {
            println("LibraryEntry.init: WARNING — AppPathHolder is not initialised. " +
                "The iOS composition root must call AppPathHolder.shared.set(path:) " +
                "BEFORE AppCoordinator() is constructed. The current path is null, so " +
                "createSqlDriver() will throw IllegalStateException. Check that the " +
                "MusicManagerApp.init() bootstrap is running (look for 'MM_DEBUG init' " +
                "in the device console).")
        }
    }

    data class Graph(
        val api: MusicManagerApi,
        val libraryRepository: LibraryRepository,
        val syncCoordinator: SyncCoordinator,
        val syncTrigger: SyncTrigger,
        val baseHost: String,
        val basePort: String,
        // Phase 3.D: per-track download state read+write. iOS
        // `SettingsView`'s "Descargas" section subscribes to
        // `observeStates()` and calls `markDownloaded` /
        // `markNotDownloaded` from a debug toggle. The future
        // production download worker (Phase 5 / BGTaskScheduler)
        // will be the real caller of those mutators.
        val downloadStateRepository: DownloadStateRepository,
    )

    /**
     * Build the library graph against the current backend. The
     * previous non-nullable form (`fun make(...): Graph`) crashed
     * the iOS app on the first launch via
     * `Kotlin_ObjCExport_trapOnUndeclaredException` →
     * `terminateWithUnhandledException` → SIGABRT, because the
     * inner `error(...)` / `require(...)` calls (e.g. in
     * `AppPathHolder.require()` or `NativeSqliteDriver`) throw
     * exceptions that K/N can't propagate across the KMP<->Swift
     * bridge without an explicit `try` wrapper.
     *
     * Phase 3.D follow-up (2026-08-24, v3): we now return a
     * sealed [Result] with the captured Throwable's class name and
     * message. The Swift side can read this via
     * `LibraryEntry.shared.lastError` to surface a precise
     * diagnostic to the user (the red error banner in
     * PairedScreen). The previous `makeOrNull` form lost the
     * exception message because we caught `Throwable` and
     * returned a bare `null`.
     */
    fun makeOrNull(host: String, port: String, tokenStore: AuthStorage): Graph? {
        return try {
            makeOrThrow(host, port, tokenStore)
        } catch (e: Throwable) {
            // Stash the exception details on a process-global so
            // the Swift side can read them after we return null.
            // The setter is a side effect that we accept here for
            // diagnostic visibility — no async/observer needed.
            lastError = "LibraryEntry.makeOrNull failed: ${e::class.simpleName}: ${e.message ?: "<no message>"}"
            println(lastError)
            null
        }
    }

    fun makeOrThrow(host: String, port: String, tokenStore: AuthStorage): Graph {
        val baseUrl = "http://$host:$port"
        val api = HttpClientFactory.makeAuthenticated(baseUrl, tokenStore)
        val databaseGraph = MusicManagerDatabaseFactory.create()
        val syncCoordinator = SyncCoordinator(api, databaseGraph.syncUpsertQueries)
        return Graph(
            api = api,
            libraryRepository = databaseGraph.libraryRepository,
            syncCoordinator = syncCoordinator,
            syncTrigger = syncCoordinator,
            baseHost = host,
            basePort = port,
            downloadStateRepository = databaseGraph.downloadStateRepository,
        )
    }

    /**
     * Last failure from [makeOrNull], or null if the most recent
     * call succeeded. Set as a side effect of `makeOrNull`; read
     * by the Swift `AppCoordinator` to populate
     * `coordinator.lastError` and the red banner in PairedScreen.
     *
     * Process-global (not per-instance) because [LibraryEntry] is
     * a Kotlin `object` singleton and the Swift side gets a fresh
     * proxy on every call.
     */
    @Volatile
    var lastError: String? = null
}