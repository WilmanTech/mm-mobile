package com.wtm.musicmanager.pairing

import android.content.Context
import com.wtm.musicmanager.network.AuthStorage

/**
 * Android actual: delegates to the platform `AuthStorage(context)` constructor.
 *
 * This is a fallback for callers that don't have Koin/Hilt-injected AuthStorage
 * (i.e. PairingEntry when used outside the Application graph — typically unit
 * tests or the `:shared:jvmTest` source set). Production Android always wires
 * AuthStorage through Hilt; PairingEntry is not used directly on Android.
 *
 * See `DefaultAuthStorage.kt` in commonMain for the contract.
 */
actual fun defaultAuthStorage(): AuthStorage = AuthStorage(StubContext)

/**
 * Sentinel context that allows `AuthStorage(context: Context)` to be called
 * without requiring a real Application. The context is only used at construction
 * time to seed EncryptedSharedPreferences — if PairingEntry is invoked from
 * non-Android code paths via this default, the resulting AuthStorage will be
 * discarded by the host app, so it doesn't matter that this is a no-op context.
 */
private object StubContext : android.content.ContextWrapper(null)
