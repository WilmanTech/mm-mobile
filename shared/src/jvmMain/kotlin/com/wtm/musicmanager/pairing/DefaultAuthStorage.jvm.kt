package com.wtm.musicmanager.pairing

import com.wtm.musicmanager.network.AuthStorage

/**
 * JVM actual: returns an `AuthStorage` rooted at the default user-home
 * directory. Used by `:shared:jvmTest` to exercise the pairing layer
 * end-to-end against an in-process backend.
 *
 * See `DefaultAuthStorage.kt` in commonMain for the contract.
 */
actual fun defaultAuthStorage(): AuthStorage = AuthStorage()
