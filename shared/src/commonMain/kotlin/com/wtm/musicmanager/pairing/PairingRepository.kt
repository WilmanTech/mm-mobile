package com.wtm.musicmanager.pairing

import com.wtm.musicmanager.network.AuthStorage
import com.wtm.musicmanager.network.MusicManagerApi
import com.wtm.musicmanager.network.PairingConfirmRequest
import com.wtm.musicmanager.network.PairingStartRequest
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
 *   1. UI calls [start] → server returns session_id, token, code, expires_in.
 *      The token is the future bearer credential. We persist it tentatively
 *      to AuthStorage so a network hiccup mid-pairing doesn't lose it.
 *   2. UI polls [refreshStatus] every 2s with the session_id.
 *   3. When status.confirmed flips true, we know the desktop has approved.
 *      The token is already in storage; we just transition state.
 *   4. UI calls [confirm] if the user typed the 4-word code on the desktop
 *      (we already know session_id+token+code from /start). confirm() updates
 *      the device_name/device_type metadata.
 *
 * The StateFlow is the single source of truth for UI bindings (and for
 * SyncCoordinator's "should I attempt sync?" gate). Tests assert state
 * transitions with Turbine; production uses collectAsState().
 */
interface TokenStore {
    fun load(): String?
    fun save(token: String)
    fun clear()

    companion object {
        fun from(storage: AuthStorage): TokenStore = object : TokenStore {
            override fun load(): String? = storage.loadToken()
            override fun save(token: String) = storage.saveToken(token)
            override fun clear() = storage.clearToken()
        }
    }
}

class PairingRepository(
    private val api: MusicManagerApi,
    private val tokenStore: TokenStore,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : PairingTrigger {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    override val state: StateFlow<PairingState> = _state.asStateFlow()

    suspend fun start(
        deviceType: String? = "mobile",
    ): PairingState {
        val response = api.startPairing(PairingStartRequest(deviceType = deviceType))
        // Persist token immediately so a backend restart mid-pairing
        // doesn't lose the credential — when /status reports confirmed,
        // we already have it.
        tokenStore.save(response.token)
        val newState = PairingState.Pending(
            sessionId = response.sessionId,
            token = response.token,
            code = response.code,
            startedAt = now(),
            expiresAt = now() + response.expiresIn * 1000L,
        )
        _state.value = newState
        return newState
    }

    /**
     * Polls /api/pairing/status. On first confirmed=true, transitions to
     * Paired. The token is already in storage from start(); we don't need
     * to re-save it.
     */
    suspend fun refreshStatus(sessionId: String): PairingState {
        val current = _state.value
        if (current !is PairingState.Pending) return current
        if (current.sessionId != sessionId) return current

        val status = api.pairingStatus(sessionId)
        val newState = when {
            !status.exists -> PairingState.Expired(sessionId)
            status.expired -> PairingState.Expired(sessionId)
            status.revoked -> PairingState.Revoked(sessionId)
            status.confirmed -> {
                tokenStore.save(current.token)
                PairingState.Paired(
                    token = current.token,
                    deviceName = status.deviceName ?: "Unknown device",
                    pairedAt = status.confirmedAt?.let { (it * 1000L).toLong() } ?: now(),
                )
            }
            else -> current
        }
        _state.value = newState
        return newState
    }

    /**
     * Sends the user's typed 4-word code to the desktop (manual confirmation
     * path — used when the mobile UI shows the code and the user types it
     * on the desktop instead of scanning a QR). Backend ignores device_name
     * and device_type if absent, so they're optional in the request.
     */
    suspend fun confirm(
        sessionId: String,
        code: String,
        deviceName: String? = null,
        deviceType: String? = "mobile",
    ): PairingState {
        return try {
            val response = api.confirmPairing(
                PairingConfirmRequest(
                    sessionId = sessionId,
                    code = code,
                    deviceName = deviceName,
                    deviceType = deviceType,
                )
            )
            // Backend already persisted; refresh status to capture device_name
            // and confirm any desktop-side race.
            val status = api.pairingStatus(sessionId)
            val newState = if (status.confirmed) {
                tokenStore.save(response.token)
                PairingState.Paired(
                    token = response.token,
                    deviceName = response.deviceName ?: status.deviceName ?: deviceName ?: "Unknown device",
                    pairedAt = (response.pairedAt * 1000L).toLong(),
                )
            } else {
                _state.value
            }
            _state.value = newState
            newState
        } catch (e: Exception) {
            val current = _state.value
            if (current is PairingState.Pending) {
                PairingState.Error(sessionId = sessionId, httpStatus = -1)
            } else current
        }
    }

    /**
     * User-initiated cancel. Wipes the persisted token and resets to Idle.
     * Does NOT call /pairing/revoke — that's a separate concern for the
     * user to manage from the desktop UI's "Connected devices" screen.
     */
    override fun unpair() {
        tokenStore.clear()
        _state.value = PairingState.Idle
    }

    /**
     * Restore from disk on app launch. If we have a token and /v1/ping
     * returns 200, transition to Paired. If 401, clear the stale token.
     */
    suspend fun restore(): PairingState {
        val token = tokenStore.load() ?: run {
            _state.value = PairingState.Idle
            return PairingState.Idle
        }
        return try {
            val response = api.ping()
            if (response.status.value == 401) {
                tokenStore.clear()
                _state.value = PairingState.Idle
                PairingState.Idle
            } else {
                val paired = PairingState.Paired(
                    token = token,
                    deviceName = "Restored",
                    pairedAt = now(),
                )
                _state.value = paired
                paired
            }
        } catch (e: Exception) {
            // Network failure — keep token, go Idle so UI shows "offline"
            // until the user reconnects. Don't wipe a potentially-valid token
            // just because the network is flaky on cold start.
            _state.value = PairingState.Idle
            PairingState.Idle
        }
    }
}

/**
 * Discriminated union of pairing lifecycle states. Closed on purpose —
 * every state transition emits one of these.
 */
sealed interface PairingState {
    data object Idle : PairingState
    data class Pending(
        val sessionId: String,
        val token: String,
        val code: String,
        val startedAt: Long,
        val expiresAt: Long,
    ) : PairingState
    data class Paired(
        val token: String,
        val deviceName: String,
        val pairedAt: Long,
    ) : PairingState
    data class Expired(val sessionId: String) : PairingState
    data class Revoked(val sessionId: String) : PairingState
    data class Error(val sessionId: String, val httpStatus: Int) : PairingState
}

/**
 * Minimum surface the UI needs from the pairing layer. Lets the
 * ViewModel inject a fake in tests without subclassing
 * [PairingRepository] (which depends on Ktor + the token store).
 * Mirrors the SyncTrigger pattern introduced in Phase 2.1.
 */
interface PairingTrigger {
    val state: StateFlow<PairingState>

    fun unpair()
}
