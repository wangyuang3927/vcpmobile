package com.vcp.mobile.data.repository

import com.vcp.mobile.data.network.HubApiClient
import com.vcp.mobile.data.network.HubConversationSummary
import com.vcp.mobile.data.network.HubSendMessageRequest
import com.vcp.mobile.data.network.HubSendMessageResponse
import com.vcp.mobile.data.network.HubStreamEvent
import com.vcp.mobile.data.network.OkHttpSseHubApiClient
import com.vcp.mobile.data.network.RustChatEventEnvelope
import com.vcp.mobile.data.network.toMessageSender
import com.vcp.mobile.data.network.toRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

/**
 * Chat 仓储层接口。
 */
interface HubChatRepository {
    suspend fun sendMessage(request: HubSendMessageRequest): HubSendMessageResponse

    fun observeStream(request: HubSendMessageRequest): Flow<HubStreamEvent>

    suspend fun listConversations(): List<HubConversationSummary>

    suspend fun fetchConversationSnapshot(conversationId: String): RustChatEventEnvelope?
}

/**
 * Chat 仓储层占位实现：当前仅委托给 HubApiClient。
 *
 * 在委托前后补充 sender <-> role 映射，确保请求与流式事件角色语义一致。
 */
class HubChatRepositoryImpl(
    private val hubApiClient: HubApiClient
) : HubChatRepository {

    override suspend fun sendMessage(request: HubSendMessageRequest): HubSendMessageResponse {
        val mappedRequest = request.copy(
            messages = request.messages.map { message ->
                val sender = message.role.toMessageSender()
                message.copy(role = sender.toRole())
            }
        )
        return hubApiClient.sendMessage(mappedRequest)
    }

    override fun observeStream(request: HubSendMessageRequest): Flow<HubStreamEvent> {
        val mappedRequest = request.copy(
            messages = request.messages.map { message ->
                val sender = message.role.toMessageSender()
                message.copy(role = sender.toRole())
            }
        )

        return hubApiClient.streamEvents(mappedRequest).map { event ->
            when (event) {
                is HubStreamEvent.Message -> {
                    val sender = event.role?.toMessageSender()
                    event.copy(role = sender?.toRole())
                }

                else -> event
            }
        }
    }

    override suspend fun listConversations(): List<HubConversationSummary> {
        return hubApiClient.listConversations()
    }

    override suspend fun fetchConversationSnapshot(conversationId: String): RustChatEventEnvelope? {
        return hubApiClient.fetchConversationSnapshot(conversationId)
    }
}

/**
 * 简易工厂：方便在 DI 接入前快速拿到可用仓储实例。
 */
object HubChatRepositoryFactory {
    fun createPlaceholder(
        baseUrl: String,
        apiKeyProvider: () -> String = { "" }
    ): HubChatRepository {
        return HubChatRepositoryImpl(
            hubApiClient = OkHttpSseHubApiClient(
                okHttpClient = OkHttpClient.Builder().build(),
                baseUrl = baseUrl,
                bearerTokenProvider = apiKeyProvider
            )
        )
    }
}
