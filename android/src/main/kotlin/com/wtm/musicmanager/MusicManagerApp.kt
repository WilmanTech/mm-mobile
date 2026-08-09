package com.wtm.musicmanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.wtm.musicmanager.data.SyncCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MusicManagerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
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
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
