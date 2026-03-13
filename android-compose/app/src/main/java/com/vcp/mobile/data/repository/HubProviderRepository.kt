package com.vcp.mobile.data.repository

import com.vcp.mobile.data.network.HubApiClient
import com.vcp.mobile.data.network.HubProviderConfig
import com.vcp.mobile.data.network.HubProviderMutationResult
import com.vcp.mobile.data.network.OkHttpSseHubApiClient
import okhttp3.OkHttpClient

interface HubProviderRepository {
    suspend fun listProviders(): List<HubProviderConfig>

    suspend fun getProvider(providerLocalId: String): HubProviderConfig

    suspend fun createProvider(provider: HubProviderConfig): HubProviderMutationResult

    suspend fun updateProvider(providerLocalId: String, provider: HubProviderConfig): HubProviderMutationResult

    suspend fun deleteProvider(providerLocalId: String): HubProviderMutationResult
}

class HubProviderRepositoryImpl(
    private val hubApiClient: HubApiClient,
) : HubProviderRepository {
    override suspend fun listProviders(): List<HubProviderConfig> = hubApiClient.listProviders()

    override suspend fun getProvider(providerLocalId: String): HubProviderConfig {
        return hubApiClient.getProvider(providerLocalId)
    }

    override suspend fun createProvider(provider: HubProviderConfig): HubProviderMutationResult {
        return hubApiClient.createProvider(provider)
    }

    override suspend fun updateProvider(
        providerLocalId: String,
        provider: HubProviderConfig,
    ): HubProviderMutationResult {
        return hubApiClient.updateProvider(providerLocalId, provider)
    }

    override suspend fun deleteProvider(providerLocalId: String): HubProviderMutationResult {
        return hubApiClient.deleteProvider(providerLocalId)
    }
}

object HubProviderRepositoryFactory {
    fun createPlaceholder(
        baseUrl: String,
        apiKeyProvider: () -> String = { "" },
    ): HubProviderRepository {
        return HubProviderRepositoryImpl(
            hubApiClient = OkHttpSseHubApiClient(
                okHttpClient = OkHttpClient.Builder().build(),
                baseUrl = baseUrl,
                bearerTokenProvider = apiKeyProvider,
            )
        )
    }
}
