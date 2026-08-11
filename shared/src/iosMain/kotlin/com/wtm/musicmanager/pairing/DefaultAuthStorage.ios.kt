package com.wtm.musicmanager.pairing

import com.wtm.musicmanager.network.AuthStorage

/**
 * iOS actual: returns a freshly-constructed `AuthStorage`. The iOS one is a
 * plain NSUserDefaults wrapper with a no-arg constructor, so this is just
 * `AuthStorage()`.
 *
 * See `DefaultAuthStorage.kt` in commonMain for the contract.
 */
actual fun defaultAuthStorage(): AuthStorage = AuthStorage()
