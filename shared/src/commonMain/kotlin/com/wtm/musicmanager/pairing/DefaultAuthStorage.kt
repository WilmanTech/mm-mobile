package com.wtm.musicmanager.pairing

import com.wtm.musicmanager.network.AuthStorage

/**
 * Returns the platform-default `AuthStorage`. Each target provides an
 * `actual` that wires the right backend (EncryptedSharedPreferences on
 * Android, NSUserDefaults on iOS, file-based on JVM for tests).
 *
 * Why a top-level factory instead of `AuthStorage()` in commonMain:
 * the `expect class AuthStorage` declaration has no `()` (each platform's
 * `actual` carries its own constructor signature — Android takes a
 * `Context`, JVM takes a `storageDir`), so there's no generic way to
 * instantiate it. The factory is the escape hatch.
 */
expect fun defaultAuthStorage(): AuthStorage
