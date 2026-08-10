package com.wtm.musicmanager.player

/**
 * Android side of the PlayerTrigger expect/actual.
 *
 * The Hilt-managed singleton lives in androidMain's DI module
 * (LibraryModule.providePlayerRepository). This file just routes
 * the expect/actual factory so callers can do
 * `providePlayerTrigger()` without needing a Hilt entry point.
 *
 * For Phase 3.B we use a simple Lazy + hand-rolled Application
 * context getter to avoid pulling Hilt into the commonMain
 * factory surface. The DI binding the UI ViewModel gets is
 * still the @Singleton ExoPlayerRepository — just that the
 * factory for tests / non-Hilt consumers goes through here.
 */
actual fun providePlayerTrigger(): PlayerTrigger = PlayerHolder.instance

/**
 * Thread-safe lazy holder for the singleton. The Application
 * installs the real instance at startup via [installPlayerTrigger].
 */
private object PlayerHolder {
    @Volatile private var current: PlayerTrigger? = null

    val instance: PlayerTrigger
        get() = current ?: error(
            "PlayerTrigger not installed. Call installPlayerTrigger(...) " +
                "from your Application.onCreate()."
        )

    fun install(repo: PlayerTrigger) {
        current = repo
    }
}

internal fun installPlayerTrigger(repo: PlayerTrigger) {
    PlayerHolder.install(repo)
}
