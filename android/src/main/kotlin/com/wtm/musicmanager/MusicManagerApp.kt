package com.wtm.musicmanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.wtm.musicmanager.data.SyncCoordinator
import com.wtm.musicmanager.player.PlayerTrigger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MusicManagerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var playerTrigger: PlayerTrigger
    @Inject lateinit var syncCoordinator: SyncCoordinator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Kick off the initial full sync on a background coroutine. The
        // LibraryScreen observes the same SQLDelight tables that
        // SyncCoordinator writes to, so the UI re-renders automatically
        // when the data lands. Failures are surfaced via SyncState.state
        // (collected by the future banner UI).
        appScope.launch {
            syncCoordinator.syncFull()
        }

        // Hilt's @Singleton on providePlayerTrigger means we get the
        // same ExoPlayer instance for the whole app process. The
        // mini-player (this PR) and the future full-screen NowPlaying
        // (Phase 4) will both inject PlayerTrigger and observe the
        // same state.
        //
        // NOTE: there's no expect/actual factory for PlayerTrigger
        // because Kotlin 2.0.21 hits an InternalCompilerError when
        // compiling expect/actual functions into the JVM target of a
        // KMP module that also links SQLDelight codegen. iOS gets
        // its own AVPlayer binding in Phase 5 (the native Swift
        // rewrite). For now, the PlayerTrigger interface lives in
        // commonMain and is consumed only from androidMain.
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
