package com.wtm.musicmanager.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS NWPathMonitor-backed implementation. Emits Offline / Connected
 * based on whether the system has any satisfied network path.
 *
 * Pairing validity is verified separately by the sync layer.
 *
 * Note: in Fase 0 this is a placeholder that always reports Offline until
 * the Xcode integration is wired up (Fase 2+). The real implementation will
 * use NWPathMonitor and requires the Xcode platform module on the classpath.
 */
class IosConnectivityMonitor : ConnectivityMonitor {

    private val _state = MutableStateFlow<Connectivity>(Connectivity.Offline)
    override val state: StateFlow<Connectivity> = _state

    override suspend fun refresh() {
        // TODO(Fase 2): wire up NWPathMonitor via the Xcode platform module.
        // For now we report Offline so the iOS UI surfaces the pairing flow.
    }
}
