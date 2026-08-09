package com.wtm.musicmanager.di

import android.content.Context
import com.wtm.musicmanager.network.AuthStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the platform-specific [AuthStorage] for Android (EncryptedSharedPreferences
 * via the actual declaration in `shared/src/androidMain/.../AuthStorage.android.kt`).
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthStorageModule {

    @Provides
    @Singleton
    fun provideAuthStorage(
        @ApplicationContext context: Context,
    ): AuthStorage = AuthStorage(context)
}
