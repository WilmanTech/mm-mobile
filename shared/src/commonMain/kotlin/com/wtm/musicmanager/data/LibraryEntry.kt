package com.wtm.musicmanager.data

import com.wtm.musicmanager.download.DownloadStateRepository
import com.wtm.musicmanager.network.AuthStorage
import com.wtm.musicmanager.network.HttpClientFactory
import com.wtm.musicmanager.network.MusicManagerApi

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
     * The new shape:
     * - `make(...)` is a regular throwing function (no @Throws —
     *     commonMain KMP doesn't support @Throws on iOS targets).
     *     K/N detects the throw and traps with
     *     `Kotlin_ObjCExport_trapOnUndeclaredException` if the
     *     Swift side calls it without `try`. This is unchanged.
     * - `makeOrNull(...)` is the safe counterpart: it `try`-catches
     *     internally and returns null on any error. The Swift
     *     `AppCoordinator.rebuildLibraryGraph()` calls this variant
     *     so the failure surfaces as `libraryGraph == nil` and the
     *     user sees the error in the UI instead of a SIGABRT.
     * - `makeOrThrow(...)` is the unsafe counterpart for callers
     *     that want the raw exception (currently no iOS callers,
     *     but the JVM tests use it). Its K/N bridge behaviour is
     *     unchanged — the Swift caller must `try` it.
     *
     * Both the `?` and the throw are encoded in
     * `LibraryEntryError` for ergonomic Swift handling:
     *
     *   ```swift
     *   guard let graph = LibraryEntry.shared.makeOrNull(...) else {
     *       // graph is nil — error already logged via NSError path
     *       return
     *   }
     *   ```
     */
    fun makeOrNull(host: String, port: String, tokenStore: AuthStorage): Graph? {
        return try {
            makeOrThrow(host, port, tokenStore)
        } catch (e: Throwable) {
            // We log here because K/N's NSError bridge doesn't
            // surface the message cleanly. The Swift caller sees
            // `graph == null` and we keep a paper trail in the
            // device console (visible via `xcrun devicectl device
            // syslog` / Xcode Devices window).
            println("LibraryEntry.makeOrNull failed: ${e::class.simpleName}: ${e.message}")
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
}