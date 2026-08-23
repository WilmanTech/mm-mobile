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
 */
object LibraryEntry {

    /**
     * Composed graph of the library layer. The iOS app keeps one
     * instance per `host:port` (re-created when the user edits the
     * form in `PairingScreen`).
     *
     * **Phase 3.B+ additions**:
     *  - `baseHost` / `basePort` — exposed so SwiftUI screens that
     *    need to hit the backend directly (cover art via
     *    `/api/library/covers/{path}`, which is unauthenticated and
     *    not on `MusicManagerApi`) can build URLs without re-deriving
     *    them from `AppCoordinator`. Both are passed verbatim from
     *    `make(host:port:)` so the values are always consistent with
     *    what `api.baseUrl` uses internally.
     */
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

    fun make(host: String, port: String, tokenStore: AuthStorage): Graph {
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