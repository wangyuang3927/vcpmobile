package com.vcp.mobile.data.repository

import com.vcp.mobile.data.network.HubAgentConfig
import com.vcp.mobile.data.network.HubAgentMutationResult
import com.vcp.mobile.data.network.HubApiClient
import com.vcp.mobile.data.network.OkHttpSseHubApiClient
import okhttp3.OkHttpClient

interface HubAgentRepository {
    suspend fun listAgents(): List<HubAgentConfig>

    suspend fun getAgent(agentId: String): HubAgentConfig

    suspend fun createAgent(agent: HubAgentConfig): HubAgentMutationResult

    suspend fun updateAgent(agentId: String, agent: HubAgentConfig): HubAgentMutationResult

    suspend fun deleteAgent(agentId: String): HubAgentMutationResult
}

class HubAgentRepositoryImpl(
    private val hubApiClient: HubApiClient,
) : HubAgentRepository {
    override suspend fun listAgents(): List<HubAgentConfig> = hubApiClient.listAgents()

    override suspend fun getAgent(agentId: String): HubAgentConfig = hubApiClient.getAgent(agentId)

    override suspend fun createAgent(agent: HubAgentConfig): HubAgentMutationResult {
        return hubApiClient.createAgent(agent)
    }

    override suspend fun updateAgent(agentId: String, agent: HubAgentConfig): HubAgentMutationResult {
        return hubApiClient.updateAgent(agentId, agent)
    }

    override suspend fun deleteAgent(agentId: String): HubAgentMutationResult {
        return hubApiClient.deleteAgent(agentId)
    }
}

object HubAgentRepositoryFactory {
    fun createPlaceholder(
        baseUrl: String,
        apiKeyProvider: () -> String = { "" },
    ): HubAgentRepository {
        return HubAgentRepositoryImpl(
            hubApiClient = OkHttpSseHubApiClient(
                okHttpClient = OkHttpClient.Builder().build(),
                baseUrl = baseUrl,
                bearerTokenProvider = apiKeyProvider,
            )
        )
    }
}
