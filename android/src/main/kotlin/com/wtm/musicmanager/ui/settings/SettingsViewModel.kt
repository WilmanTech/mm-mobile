package com.wtm.musicmanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.data.SyncState
import com.wtm.musicmanager.data.SyncTrigger
import com.wtm.musicmanager.pairing.PairingState
import com.wtm.musicmanager.pairing.PairingTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings screen UI state.
 *
 * - [deviceName]: from the current PairingState.Paired (or null when
 *   not paired). The PairingState is the source of truth for "what
 *   device is the backend expecting" — the Settings screen just
 *   surfaces it.
 * - [lastSyncServerTime]: ISO 8601 string from SyncCoordinator
 *   (server's clock at the last successful sync). Null = never
 *   synced (first launch, fresh pair, or the sync coordinator was
 *   never asked to run).
 * - [isSyncing]: true while a sync is in flight (mirrors
 *   SyncCoordinator.state == Running).
 * - [lastSyncError]: user-friendly string of the last failed sync,
 *   null when the last attempt succeeded.
 * - [trackCount]: number of tracks in the local cache (a "library
 *   size" sanity indicator — lets the user know if the last sync
 *   actually fetched something).
 * - [unpairRequested]: transient flag the screen watches to bounce
 *   back to the PairingScreen after the user confirms the action.
 */
data class SettingsUiState(
    val deviceName: String? = null,
    val lastSyncServerTime: String? = null,
    val isSyncing: Boolean = false,
    val lastSyncError: String? = null,
    val trackCount: Long = 0L,
    val unpairRequested: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val pairingRepository: PairingTrigger,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    private val _trackCount = MutableStateFlow(0L)

    val state: StateFlow<SettingsUiState> = combine(
        pairingRepository.state,
        syncTrigger.state,
        syncTrigger.lastServerTime,
        _trackCount,
    ) { pairing, syncState, lastServerTime, trackCount ->
        val (deviceName, isSyncing, lastError) = when {
            pairing is PairingState.Paired -> Triple(pairing.deviceName, syncState is SyncState.Running, null)
            else -> Triple(null, syncState is SyncState.Running, null)
        }
        SettingsUiState(
            deviceName = deviceName,
            lastSyncServerTime = lastServerTime,
            isSyncing = isSyncing,
            lastSyncError = (syncState as? SyncState.Failed)?.reason,
            trackCount = trackCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
    )

    fun onTrackCountLoaded(count: Long) {
        _trackCount.value = count
    }

    /**
     * Forces a fresh full sync (wipes local + re-populates from /sync/full).
     * For an incremental update, see [onIncrementalSync] (the default
     * pull-to-refresh on LibraryScreen already calls that).
     */
    fun onFullSync() {
        viewModelScope.launch {
            syncTrigger.syncFull()
        }
    }

    fun onIncrementalSync() {
        viewModelScope.launch {
            syncTrigger.syncChanges()
        }
    }

    fun onUnpair() {
        viewModelScope.launch {
            pairingRepository.unpair()
        }
    }
}
