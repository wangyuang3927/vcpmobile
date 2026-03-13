package com.vcp.mobile.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vcp.mobile.data.recovery.DataStoreConversationRecoveryStore
import com.vcp.mobile.data.recovery.RecoveryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("conversation_recovery.preferences_pb") }
        )
    }

    @Provides
    @Singleton
    fun provideConversationRecoveryStore(
        dataStore: DataStore<Preferences>
    ): RecoveryStore {
        return DataStoreConversationRecoveryStore(dataStore)
    }
}
