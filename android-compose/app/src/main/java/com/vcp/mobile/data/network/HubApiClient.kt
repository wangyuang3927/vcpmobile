package com.vcp.mobile.data.network

import kotlinx.coroutines.flow.Flow

/**
 * Hub API 数据层接口：
 * - sendMessage: 非流式发送
 * - streamEvents: SSE 流式接收
 */
interface HubApiClient {
    suspend fun sendMessage(request: HubSendMessageRequest): HubSendMessageResponse

    fun streamEvents(request: HubSendMessageRequest): Flow<HubStreamEvent>

    fun regenerateAssistant(request: HubRegenerateRequest): Flow<HubStreamEvent>

    fun selectVariant(request: HubSelectVariantRequest): Flow<HubStreamEvent>

    suspend fun listConversations(): List<HubConversationSummary>

    suspend fun fetchConversationSnapshot(conversationId: String): RustChatEventEnvelope?

    suspend fun listAgents(): List<HubAgentConfig>

    suspend fun getAgent(agentId: String): HubAgentConfig

    suspend fun createAgent(agent: HubAgentConfig): HubAgentMutationResult

    suspend fun updateAgent(agentId: String, agent: HubAgentConfig): HubAgentMutationResult

    suspend fun deleteAgent(agentId: String): HubAgentMutationResult

    suspend fun previewResolvedPrompt(request: HubPromptPreviewRequest): HubResolvedPromptPreview

    suspend fun exchangePairing(request: HubPairingExchangeRequest): HubPairingExchangeResult
}
