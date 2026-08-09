package com.wtm.musicmanager

import com.wtm.musicmanager.connectivity.Connectivity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ConnectivityTest {
    @Test
    fun `offline is the default starting state`() = runTest {
        val monitor = FakeConnectivityMonitor()
        monitor.state.value shouldBe Connectivity.Offline
    }
}

private class FakeConnectivityMonitor : com.wtm.musicmanager.connectivity.ConnectivityMonitor {
    override val state = MutableStateFlow<Connectivity>(Connectivity.Offline)
    override suspend fun refresh() = Unit
}
