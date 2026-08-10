package com.wtm.musicmanager.di

import android.content.Context
import androidx.work.WorkManager
import com.wtm.musicmanager.data.SyncUpsertQueries
import com.wtm.musicmanager.download.DownloadRepository
import com.wtm.musicmanager.download.DownloadTrigger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt graph for the download subsystem.
 *
 * Why a separate module instead of folding these @Provides into
 * LibraryModule? LibraryModule grew to ~30 providers this phase
 * (sync, pairing, player, work-manager). Splitting downloads out
 * keeps each file readable, and more importantly keeps the diff
 * to LibraryModule small — Phase 3.A only touches the new file.
 *
 * Adding ANY new @Provides to LibraryModule that takes
 * `SyncUpsertQueries` as a parameter triggered a KSP
 * InternalCompilerError with `error.NonExistentClass` cascading
 * through the rest of the module (verified 2026-08-10). Splitting
 * the new bindings into a dedicated module sidesteps that.
 */
@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideDownloadRepository(
        @ApplicationContext context: Context,
        workManager: WorkManager,
        queries: SyncUpsertQueries,
    ): DownloadRepository = DownloadRepository(context, workManager, queries)

    @Provides
    @Singleton
    fun provideDownloadTrigger(repo: DownloadRepository): DownloadTrigger = repo
}