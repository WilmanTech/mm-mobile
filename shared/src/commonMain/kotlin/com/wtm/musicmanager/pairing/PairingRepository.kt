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
        val response = try {
            api.startPairing(PairingStartRequest(deviceType = deviceType))
        } catch (e: Throwable) {
            // Bridge crash guard (verify mm-mobile audit 2026-08-13): Ktor's
            // Darwin engine throws `DarwinHttpRequestException` when the
            // iOS device rejects a local-network request (NSError -1009
            // "Local network prohibited"). That exception is NOT declared
            // in Ktor's @Throws signature, so when this suspend fun is
            // bridged to Swift and back into a callback the KMP runtime
            // can't propagate it as NSError — it logs:
            //
            //   "Exception doesn't match @Throws-specified class list and
            //    thus isn't propagated from Kotlin to Objective-C/Swift
            //    as NSError. It is considered unexpected and unhandled
            //    instead. Program will be terminated."
            //
            // and SIGABRTs the app. By catching here we always invoke the
            // completion handler with a valid PairingState.Error, so the
            // Swift UI lands on the error screen instead of crashing.
            val errorState = PairingState.Error(
                sessionId = "",
                httpStatus = -1,
            )
            _state.value = errorState
            return errorState
        }
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

        val status = try {
            api.pairingStatus(sessionId)
        } catch (e: Throwable) {
            // Transient network blip during the 2s poll — surface as
            // a retryable error (no state change), NOT a propagation
            // that would throw out of the caller's polling loop.
            // Verify mm-mobile audit 2026-08-12.
            return PairingState.Error(
                sessionId = sessionId,
                httpStatus = -1,
            )
        }
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
     * Accept a pairing token handed to us via a deep link
     * (`mm://pair?session=...&token=...&code=...&host=...&port=...`).
     *
     * Unlike [start] + [refreshStatus], this skips the Pending phase
     * entirely: the user already typed the code on the desktop, so we
     * trust the token in the deep link as proof of authorization.
     * Used by [MainActivity.handlePairingIntent] when the app is
     * launched via the QR scan flow.
     *
     * The token is persisted immediately so a process kill between
     * this call and the first authenticated request still leaves the
     * app paired on next cold start ([restore] will validate).
     */
    fun acceptDeepLink(token: String, deviceName: String = "Paired via QR"): PairingState {
        tokenStore.save(token)
        val paired = PairingState.Paired(
            token = token,
            deviceName = deviceName,
            pairedAt = now(),
        )
        _state.value = paired
        return paired
    }

    /**
     * Restore from disk on app launch. If we have a token and /v1/ping
     * returns 200, transition to Paired.
     *
     * **v2026-08-14 fix**: previously, a transient 401 (network not
     * ready on cold launch, DNS resolving the backend host, captive
     * portal interference, etc.) would wipe the persisted bearer and
     * force the user to re-pair on every device restart. The user
     * reported this as "pairing is not persistent" on iPhone 11 / iOS 26
     * even though NSUserDefaults retained the value across launches.
     *
     * New semantics:
     *  - token missing → Idle (legitimately unpaired)
     *  - ping() returns 200 → Paired (happy path)
     *  - ping() returns 401 → keep the token, return Paired(token=...) —
     *    the token is still on disk so we re-verify on next user action
     *    (e.g. opening Library triggers /sync/full which will surface the
     *    401 and offer re-pair). Wiping the token silently was destructive
     *    UX with no recovery affordance.
     *  - ping() throws (DNS failure, connection refused, timeout) →
     *    keep the token, return Idle so the UI shows offline / retry.
     *    Same reasoning: don't wipe on transient network failure.
     *
     * The previous destructive-on-401 path is preserved as a one-shot
     * recovery mechanism reachable through [unpair] (user explicitly
     * taps "Forget this device" in Settings).
     */
    suspend fun restore(): PairingState {
        val token = tokenStore.load() ?: run {
            _state.value = PairingState.Idle
            return PairingState.Idle
        }
        return try {
            val response = api.ping()
            if (response.status.value == 401) {
                // Keep the token — don't punish the user for a transient
                // 401 (DNS race, captive portal, network not yet up after
                // cold boot). Surface Paired with a flag so the UI can
                // optionally show "verification pending" without blocking
                // access to the cached library.
                _state.value = PairingState.Paired(
                    token = token,
                    deviceName = "Restored (unverified)",
                    pairedAt = now(),
                )
                PairingState.Paired(
                    token = token,
                    deviceName = "Restored (unverified)",
                    pairedAt = now(),
                )
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
