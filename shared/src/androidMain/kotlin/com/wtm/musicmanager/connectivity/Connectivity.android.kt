package com.wtm.musicmanager.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android ConnectivityManager-backed implementation. Tracks the system
 * network state and emits `Offline` / `Connected` transitions.
 *
 * Auth validity (bearer token + ping) is verified by the sync layer;
 * here we only report raw network reachability.
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow<Connectivity>(current())
    override val state: StateFlow<Connectivity> = _state

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.value = Connectivity.Connected(network.toString())
            }

            override fun onLost(network: Network) {
                _state.value = current()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                _state.value = Connectivity.Connected(if (wifi) "WiFi" else "Mobile")
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun current(): Connectivity {
        val active = cm.activeNetwork ?: return Connectivity.Offline
        val caps = cm.getNetworkCapabilities(active) ?: return Connectivity.Offline
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return Connectivity.Offline
        }
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return Connectivity.Connected(if (wifi) "WiFi" else "Mobile")
    }

    override suspend fun refresh() {
        _state.value = current()
    }
}
