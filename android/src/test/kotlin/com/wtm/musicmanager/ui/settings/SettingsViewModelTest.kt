package com.wtm.musicmanager.ui.settings

import com.wtm.musicmanager.data.SyncState
import com.wtm.musicmanager.data.SyncTrigger
import com.wtm.musicmanager.pairing.PairingState
import com.wtm.musicmanager.pairing.PairingTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    @BeforeTest
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun teardown() { Dispatchers.resetMain() }

    /**
     * The VM uses stateIn(WhileSubscribed) so a collector is required
     * to keep the upstream combine() alive. Helper that:
     * 1. constructs the VM with the given state
     * 2. starts a collector on testScope
     * 3. runs the assertions
     * 4. cancels the collector in the cleanup phase via testScope
     */
    private fun runWithVm(
        pairingState: PairingState,
        syncState: SyncState,
        lastServerTime: String? = null,
        assertions: suspend (vm: SettingsViewModel) -> Unit,
    ) = runTest(testDispatcher) {
        val pairing = FakePairingRepository(state = pairingState)
        val sync = FakeSyncTrigger(state = syncState, lastServerTime = lastServerTime)
        val vm = SettingsViewModel(pairing, sync)
        val job = testScope.launch { vm.state.collect { /* swallow */ } }
        try {
            advanceUntilIdle()
            assertions(vm)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `device name comes from PairingState Paired`() = runWithVm(
        pairingState = PairingState.Paired(
            token = "tok-abc",
            deviceName = "Moi's Phone",
            pairedAt = 1_700_000_000L,
        ),
        syncState = SyncState.Idle,
        lastServerTime = "2026-08-10T12:00:00Z",
    ) { vm ->
        val s = vm.state.value
        assertEquals("Moi's Phone", s.deviceName)
        assertEquals("2026-08-10T12:00:00Z", s.lastSyncServerTime)
        assertNull(s.lastSyncError)
    }

    @Test
    fun `lastSyncError mirrors SyncState Failed reason`() = runWithVm(
        pairingState = PairingState.Paired(token = "t", deviceName = "d", pairedAt = 0L),
        syncState = SyncState.Failed(reason = "Network unreachable"),
    ) { vm ->
        val s = vm.state.value
        assertEquals("Network unreachable", s.lastSyncError)
    }

    @Test
    fun `isSyncing mirrors Running state`() = runWithVm(
        pairingState = PairingState.Paired(token = "t", deviceName = "d", pairedAt = 0L),
        syncState = SyncState.Running(phase = com.wtm.musicmanager.data.SyncPhase.Full),
    ) { vm ->
        assertEquals(true, vm.state.value.isSyncing)
    }

    @Test
    fun `device name is null when not paired`() = runWithVm(
        pairingState = PairingState.Idle,
        syncState = SyncState.Idle,
    ) { vm ->
        assertNull(vm.state.value.deviceName)
    }
}

private class FakePairingRepository(
    state: PairingState,
) : PairingTrigger {
    private val _state = MutableStateFlow(state)
    override val state = _state.asStateFlow()
    override fun unpair() {
        _state.value = PairingState.Idle
    }
}

private class FakeSyncTrigger(
    state: SyncState = SyncState.Idle,
    lastServerTime: String? = null,
) : SyncTrigger {
    private val _state = MutableStateFlow(state)
    override val state = _state.asStateFlow()
    override val lastServerTime = MutableStateFlow(lastServerTime).asStateFlow()

    override suspend fun syncFull(): SyncState = SyncState.Idle
    override suspend fun syncChanges(): SyncState = SyncState.Idle
}
