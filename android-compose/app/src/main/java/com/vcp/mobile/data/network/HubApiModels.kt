package com.vcp.mobile.data.network

import com.vcp.mobile.ui.chat.MessageSender

const val HUB_ROLE_USER = "user"
const val HUB_ROLE_ASSISTANT = "assistant"
const val HUB_PAIRING_PLATFORM_ANDROID = "android"

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

data class HubRegenerateRequest(
    val conversationId: String,
    val nodeId: String,
)

data class HubSelectVariantRequest(
    val conversationId: String,
    val nodeId: String,
    val variantId: String,
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

data class HubPromptPreviewPlaceholder(
    val key: String,
    val value: String,
    val category: String,
    val source: String,
)

data class HubPromptPreviewRequest(
    val rawPrompt: String,
    val placeholders: List<HubPromptPreviewPlaceholder> = emptyList(),
)

data class HubPromptPreviewRecord(
    val key: String,
    val value: String,
    val category: String,
    val source: String,
    val status: String,
)

data class HubResolvedPromptPreview(
    val rawPrompt: String,
    val resolvedPrompt: String,
    val records: List<HubPromptPreviewRecord> = emptyList(),
    val unresolvedTokens: List<String> = emptyList(),
    val partialTokens: List<String> = emptyList(),
)

data class HubPairingExchangeRequest(
    val pairingSessionId: String,
    val namespace: String,
    val bootstrapToken: String,
    val deviceName: String,
    val devicePlatform: String = HUB_PAIRING_PLATFORM_ANDROID,
    val devicePublicKey: String,
)

data class HubPairingMobileToken(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: String,
)

data class HubPairingTrustedDevice(
    val trustedDeviceId: String,
    val deviceName: String,
    val devicePlatform: String,
)

data class HubPairingResumeAnchor(
    val anchor: String,
    val expiresAt: String,
)

data class HubPairingExchangeSuccessResponse(
    val pairingSessionId: String,
    val namespace: String,
    val status: String,
    val mobileToken: HubPairingMobileToken,
    val trustedDevice: HubPairingTrustedDevice,
    val resumeAnchor: HubPairingResumeAnchor,
)

data class HubPairingExchangeError(
    val code: String,
    val message: String,
    val retriable: Boolean,
)

data class HubPairingExchangeFailureResponse(
    val pairingSessionId: String? = null,
    val namespace: String? = null,
    val status: String,
    val error: HubPairingExchangeError,
)

sealed interface HubPairingExchangeResult {
    data class Success(
        val response: HubPairingExchangeSuccessResponse
    ) : HubPairingExchangeResult

    data class Failure(
        val response: HubPairingExchangeFailureResponse
    ) : HubPairingExchangeResult
}

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
