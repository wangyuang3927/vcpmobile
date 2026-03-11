package com.vcp.mobile.data.network

import com.vcp.mobile.ui.chat.MessageSender

const val HUB_ROLE_USER = "user"
const val HUB_ROLE_ASSISTANT = "assistant"

/**
 * MessageSender -> Hub role 映射。
 */
fun MessageSender.toRole(): String = when (this) {
    MessageSender.USER -> HUB_ROLE_USER
    MessageSender.AGENT -> HUB_ROLE_ASSISTANT
}

/**
 * Hub role -> MessageSender 映射。
 *
 * 当前保留占位容错：未知 role 默认映射为 AGENT，避免占位网络阶段因脏数据中断流程。
 */
fun String.toMessageSender(): MessageSender = when (trim().lowercase()) {
    HUB_ROLE_USER -> MessageSender.USER
    HUB_ROLE_ASSISTANT -> MessageSender.AGENT
    else -> MessageSender.AGENT
}

/**
 * Hub 聊天消息。
 */
data class HubMessage(
    val role: String,
    val content: String
)

/**
 * Hub 消息请求体（骨架字段，可按后端契约继续扩展）。
 */
data class HubSendMessageRequest(
    val model: String,
    val messages: List<HubMessage>,
    val conversationId: String? = null,
    val sessionId: String? = null,
    val stream: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Hub 非流式响应（占位结构）。
 */
data class HubSendMessageResponse(
    val requestId: String,
    val assistantMessage: String,
    val rawBody: String? = null
)

data class HubConversationSummary(
    val conversationId: String,
    val title: String,
    val updatedAt: String,
    val generationState: String,
    val currentCursor: String? = null,
    val summary: String? = null,
    val pinned: Boolean = false,
    val isRecoverable: Boolean = true,
    val nodeCount: Int = 0,
)

/**
 * SSE 流式事件模型。
 */
sealed interface HubStreamEvent {
    data object Opened : HubStreamEvent

    data class Message(
        val event: String,
        val data: String,
        val role: String? = null
    ) : HubStreamEvent

    data class Error(
        val throwable: Throwable
    ) : HubStreamEvent

    data object Completed : HubStreamEvent
}
