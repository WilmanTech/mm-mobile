package com.wtm.musicmanager.pairing

import com.wtm.musicmanager.network.ConfirmPairingRequest
import com.wtm.musicmanager.network.MusicManagerApi
import com.wtm.musicmanager.network.PairingStatusResponse
import com.wtm.musicmanager.network.StartPairingRequest
import com.wtm.musicmanager.network.AuthStorage
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Drives the mobile-side pairing handshake against a MusicManager backend.
 *
 * Flow (mirrors the desktop-side QR flow):
 *   1. UI calls [start] → server returns session_id + 4-word code.
 *   2. UI polls [refreshStatus] every 2s with the session_id.
 *   3. When status flips to "paired", server returns api_token. We persist
 *      it to [AuthStorage] and emit [PairingState.Paired] to observers.
 *
 * The StateFlow is the single source of truth for UI bindings (and for
 * SyncCoordinator's "should I attempt sync?" gate). Tests assert state
 * transitions with Turbine; production uses collectAsState().
 */
/**
 * Reads/writes the pairing token + server label. Decoupled from the
 * concrete [AuthStorage] so tests can pass in a lambda-backed in-memory
 * store without spinning up EncryptedSharedPreferences or Keychain.
 */
interface TokenStore {
    fun load(): String?
    fun save(token: String, serverLabel: String?)
    fun clear()

    companion object {
        /** Wraps an [AuthStorage] in a TokenStore. */
        fun from(storage: AuthStorage): TokenStore = object : TokenStore {
            override fun load(): String? = storage.loadToken()
            override fun save(token: String, serverLabel: String?) =
                storage.saveToken(token, serverLabel)
            override fun clear() = storage.clearToken()
        }
    }
}

class PairingRepository(
    private val api: MusicManagerApi,
    private val tokenStore: TokenStore,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    suspend fun start(
        serverLabel: String? = null,
        deviceName: String = "Music Manager Mobile",
    ): PairingState {
        val response = api.startPairing(StartPairingRequest(deviceName = deviceName))
        val newState = PairingState.Pending(
            sessionId = response.sessionId,
            code = response.code,
            startedAt = now(),
            expiresAt = response.expiresAt,
            serverLabel = serverLabel,
        )
        _state.value = newState
        return newState
    }

    /**
     * Polls /api/pairing/status. On first "paired" response, persists the
     * token to AuthStorage. Returns the new state.
     */
    suspend fun refreshStatus(sessionId: String): PairingState {
        val current = _state.value
        if (current !is PairingState.Pending) return current
        if (current.sessionId != sessionId) return current

        val status = api.pairingStatus(sessionId)
        val newState = when (status.status) {
            "pending" -> current
            "paired" -> {
                val token = status.apiToken
                    ?: error("Server reported paired status without api_token")
                tokenStore.save(token, status.serverLabel)
                PairingState.Paired(
                    token = token,
                    deviceName = status.deviceName ?: "Unknown device",
                    serverLabel = status.serverLabel,
                    pairedAt = now(),
                )
            }
            "expired" -> PairingState.Expired(sessionId)
            "revoked" -> PairingState.Revoked(sessionId)
            else -> current
        }
        _state.value = newState
        return newState
    }

    /**
     * Manual confirmation path — used when the mobile UI shows the code and
     * the user types it into the desktop instead of scanning a QR.
     * Wraps [MusicManagerApi.confirmPairing] and translates HTTP errors to
     * PairingState transitions.
     */
    suspend fun confirm(
        sessionId: String,
        code: String,
        deviceName: String,
    ): PairingState {
        val response: HttpResponse = api.confirmPairing(
            ConfirmPairingRequest(
                sessionId = sessionId,
                code = code,
                deviceName = deviceName,
            )
        )
        return when (response.status) {
            HttpStatusCode.OK -> {
                val status: PairingStatusResponse = api.pairingStatus(sessionId)
                if (status.status == "paired" && status.apiToken != null) {
                    tokenStore.save(status.apiToken, status.serverLabel)
                    val paired = PairingState.Paired(
                        token = status.apiToken,
                        deviceName = status.deviceName ?: deviceName,
                        serverLabel = status.serverLabel,
                        pairedAt = now(),
                    )
                    _state.value = paired
                    paired
                } else {
                    val pending = PairingState.Pending(
                        sessionId = sessionId,
                        code = code,
                        startedAt = now(),
                        expiresAt = null,
                        serverLabel = null,
                    )
                    _state.value = pending
                    pending
                }
            }
            HttpStatusCode.NotFound, HttpStatusCode.Gone -> {
                val expired = PairingState.Expired(sessionId)
                _state.value = expired
                expired
            }
            else -> {
                val error = PairingState.Error(
                    sessionId = sessionId,
                    httpStatus = response.status.value,
                )
                _state.value = error
                error
            }
        }
    }

    /** User-initiated cancel — wipes any persisted token and resets to Idle. */
    fun unpair() {
        tokenStore.clear()
        _state.value = PairingState.Idle
    }
}

/**
 * Discriminated union of pairing lifecycle states. The set is closed on
 * purpose — every state transition emits one of these.
 */
sealed interface PairingState {
    data object Idle : PairingState
    data class Pending(
        val sessionId: String,
        val code: String,
        val startedAt: Long,
        val expiresAt: Long?,
        val serverLabel: String?,
    ) : PairingState
    data class Paired(
        val token: String,
        val deviceName: String,
        val serverLabel: String?,
        val pairedAt: Long,
    ) : PairingState
    data class Expired(val sessionId: String) : PairingState
    data class Revoked(val sessionId: String) : PairingState
    data class Error(val sessionId: String, val httpStatus: Int) : PairingState
}
