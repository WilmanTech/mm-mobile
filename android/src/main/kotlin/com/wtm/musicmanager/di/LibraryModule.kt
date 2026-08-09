package com.wtm.musicmanager.di

import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.data.SqlDelightLibraryRepository
import com.wtm.musicmanager.data.SyncUpsertQueries
import com.wtm.musicmanager.db.MusicManagerDatabase
import com.wtm.musicmanager.db.createDriver
import com.wtm.musicmanager.network.AuthStorage
import com.wtm.musicmanager.network.AuthStorageFactory
import com.wtm.musicmanager.network.MusicManagerApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

/**
 * Hilt graph for the music library layer.
 *
 * - [MusicManagerDatabase] is a singleton built off the platform SqlDriver
 *   from [createDriver] (Android actual: AndroidSqliteDriver).
 * - [LibraryRepository] is the SqlDelight-backed read API.
 * - [SyncUpsertQueries] is the same facade SyncCoordinator consumes; we
 *   bind to the SQLDelight-generated transaction wrapper.
 * - [MusicManagerApi] is built once per process off OkHttp + AuthStorage.
 *
 * The base URL is hard-coded to the AVD host loopback (10.0.2.2:8765) for
 * Phase 2 — production wiring of user-configurable hosts happens in the
 * PairingScreen flow (commit 5+).
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8765"

    @Provides
    @Singleton
    fun provideDatabase(): MusicManagerDatabase =
        MusicManagerDatabase(createDriver())

    @Provides
    @Singleton
    fun provideSyncUpsertQueries(db: MusicManagerDatabase): SyncUpsertQueries =
        SqlDelightSyncUpsertQueries(db)

    @Provides
    @Singleton
    fun provideLibraryRepository(db: MusicManagerDatabase): LibraryRepository =
        SqlDelightLibraryRepository(db)

    @Provides
    @Singleton
    fun provideHttpClient(authStorage: AuthStorage): HttpClient =
        AuthStorageFactory.create(
            authStorage = authStorage,
            engineFactory = OkHttp,
            enableLogging = true,
        )

    @Provides
    @Singleton
    fun provideMusicManagerApi(
        client: HttpClient,
        authStorage: AuthStorage,
    ): MusicManagerApi = MusicManagerApi(client, DEFAULT_BASE_URL, authStorage)
}
