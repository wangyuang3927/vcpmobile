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

    suspend fun listConversations(): List<HubConversationSummary>

    suspend fun fetchConversationSnapshot(conversationId: String): RustChatEventEnvelope?
}
