package com.wtm.musicmanager.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.pairing.PairingRepository
import com.wtm.musicmanager.pairing.PairingState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PairingUiState(
    val host: String = "10.0.2.2",
    val port: String = "8765",
    val pairing: PairingState = PairingState.Idle,
    val statusMessage: String? = null,
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val _host = MutableStateFlow("10.0.2.2")
    private val _port = MutableStateFlow("8765")
    private val _status = MutableStateFlow<String?>(null)

    val state: StateFlow<PairingUiState> = combine(
        _host,
        _port,
        pairingRepository.state,
        _status,
    ) { host, port, pairing, status ->
        PairingUiState(
            host = host,
            port = port,
            pairing = pairing,
            statusMessage = status,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PairingUiState(),
    )

    fun onHostChange(value: String) {
        _host.value = value.trim()
    }

    fun onPortChange(value: String) {
        _port.value = value.filter { it.isDigit() }.take(5)
    }

    fun onStartPairing() {
        val host = _host.value
        val port = _port.value.toIntOrNull() ?: return
        _status.value = "Iniciando emparejamiento con $host:$port…"
        viewModelScope.launch {
            try {
                pairingRepository.start(deviceType = "mobile")
                _status.value = "Esperando confirmación del escritorio…"
            } catch (e: Throwable) {
                _status.value = "Error: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    fun onClearError() {
        _status.value = null
    }
}
