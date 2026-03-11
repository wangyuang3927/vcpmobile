package com.vcp.mobile.di

import com.vcp.mobile.BuildConfig
import com.vcp.mobile.data.network.HubApiClient
import com.vcp.mobile.data.network.OkHttpSseHubApiClient
import com.vcp.mobile.data.repository.HubChatRepository
import com.vcp.mobile.data.repository.HubChatRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("hubBaseUrl")
    fun provideHubBaseUrl(): String = BuildConfig.HUB_BASE_URL

    @Provides
    @Singleton
    @Named("hubBearerToken")
    fun provideHubBearerToken(): String = ""

    @Provides
    @Singleton
    fun provideHubApiClient(
        okHttpClient: OkHttpClient,
        @Named("hubBaseUrl") hubBaseUrl: String,
        @Named("hubBearerToken") hubBearerToken: String
    ): HubApiClient {
        return OkHttpSseHubApiClient(
            okHttpClient = okHttpClient,
            baseUrl = hubBaseUrl,
            bearerTokenProvider = { hubBearerToken }
        )
    }

    @Provides
    @Singleton
    fun provideHubChatRepository(hubApiClient: HubApiClient): HubChatRepository {
        return HubChatRepositoryImpl(hubApiClient)
    }
}
