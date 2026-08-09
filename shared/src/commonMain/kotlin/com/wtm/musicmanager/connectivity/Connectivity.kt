package com.wtm.musicmanager.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for whether the app is currently connected
 * to a paired MusicManager server.
 *
 * Emits `Connected` when the local server URL is reachable AND the bearer
 * token is valid (verified via /api/v1/ping or /api/v1/whoami).
 *
 * Emits `Offline` on network failure or auth rejection.
 *
 * UI surfaces a banner from this flow (see Android `ConnectivityBanner`,
 * iOS `ConnectivityBanner`) — never inspect connectivity ad-hoc.
 */
sealed interface Connectivity {
    data object Offline : Connectivity
    data object Syncing : Connectivity
    data class Connected(val deviceName: String) : Connectivity
}

interface ConnectivityMonitor {
    val state: Flow<Connectivity>
    suspend fun refresh()
}
