package com.vcp.mobile.ui.chat

import com.vcp.mobile.domain.model.ast.MarkdownDocument
import java.util.UUID

enum class MessageSender {
    USER,
    AGENT,
}

enum class ChatGenerationPhase {
    IDLE,
    REQUESTING,
    STARTED,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

fun ChatGenerationPhase.isActive(): Boolean = when (this) {
    ChatGenerationPhase.REQUESTING,
    ChatGenerationPhase.STARTED,
    ChatGenerationPhase.STREAMING -> true
    else -> false
}

fun ChatGenerationPhase.isTerminal(): Boolean = when (this) {
    ChatGenerationPhase.COMPLETED,
    ChatGenerationPhase.FAILED,
    ChatGenerationPhase.CANCELLED -> true
    else -> false
}

enum class ChatBodyMode {
    MARKDOWN,
    PLAIN_TEXT,
    CODE_FALLBACK,
}

data class ChatGenerationState(
    val phase: ChatGenerationPhase = ChatGenerationPhase.IDLE,
    val activeMessageKey: String? = null,
) {
    val canResume: Boolean
        get() = phase.isActive()

    val showsTimelineStatusBanner: Boolean
        get() = when (phase) {
            ChatGenerationPhase.REQUESTING,
            ChatGenerationPhase.STARTED -> activeMessageKey == null
            ChatGenerationPhase.FAILED,
            ChatGenerationPhase.CANCELLED -> true
            else -> false
        }
}

data class UiMessagePart(
    val type: String,
    val text: String = "",
    val language: String? = null,
)

data class UiMessageCompatibilityProjection(
    val content: String,
    val reasoning: String?,
    val partTypes: List<String>,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val content: String,
    val reasoning: String? = null,
    val ast: MarkdownDocument? = null,
    val nodeId: String? = null,
    val variantId: String? = null,
    val parts: List<UiMessagePart> = emptyList(),
    val partTypes: List<String> = emptyList(),
    val timestampMillis: Long = System.currentTimeMillis(),
)

data class ChatMessageIdentity(
    val nodeId: String?,
    val variantId: String?,
) {
    val isTyped: Boolean
        get() = !nodeId.isNullOrBlank() && !variantId.isNullOrBlank()
}

data class ChatRenderProjection(
    val identity: ChatMessageIdentity,
    val hasReasoning: Boolean,
    val hasMarkdown: Boolean,
    val hasCodeBlock: Boolean,
    val bodyMode: ChatBodyMode,
    val labels: List<String>,
)

fun ChatMessage.identity(): ChatMessageIdentity = ChatMessageIdentity(
    nodeId = nodeId,
    variantId = variantId,
)

fun List<UiMessagePart>.toCompatibilityProjection(): UiMessageCompatibilityProjection {
    if (isEmpty()) {
        return UiMessageCompatibilityProjection(
            content = "",
            reasoning = null,
            partTypes = emptyList(),
        )
    }

    val content = buildString {
        this@toCompatibilityProjection.forEach { part ->
            when (part.type.trim().lowercase()) {
                "text", "markdown_block" -> append(part.text)
                "code_block" -> {
                    if (!part.language.isNullOrBlank()) {
                        append("```").append(part.language).append('\n')
                    } else {
                        append("```\n")
                    }
                    append(part.text)
                    if (!part.text.endsWith("\n")) append('\n')
                    append("```")
                }
            }
        }
    }
    val reasoning = buildString {
        this@toCompatibilityProjection.forEach { part ->
            if (part.type.trim().lowercase() == "reasoning") {
                append(part.text)
            }
        }
    }.ifBlank { null }
    val partTypes = this.map { it.type.trim().lowercase() }.distinct()

    return UiMessageCompatibilityProjection(
        content = content,
        reasoning = reasoning,
        partTypes = partTypes,
    )
}

fun ChatMessage.renderProjection(): ChatRenderProjection {
    val normalizedPartTypes = if (parts.isNotEmpty()) {
        parts.map { it.type.trim().lowercase() }
    } else {
        partTypes.map { it.trim().lowercase() }
    }
    val hasReasoning = reasoning?.isNotBlank() == true || "reasoning" in normalizedPartTypes
    val hasMarkdown = ast != null || "markdown_block" in normalizedPartTypes
    val hasCodeBlock = "code_block" in normalizedPartTypes
    val bodyMode = when {
        hasCodeBlock && !hasMarkdown -> ChatBodyMode.CODE_FALLBACK
        hasMarkdown -> ChatBodyMode.MARKDOWN
        else -> ChatBodyMode.PLAIN_TEXT
    }
    val labels = buildList {
        if (hasReasoning) add("思考")
        if (hasMarkdown) add("Markdown")
        if (hasCodeBlock) add("代码")
    }

    return ChatRenderProjection(
        identity = identity(),
        hasReasoning = hasReasoning,
        hasMarkdown = hasMarkdown,
        hasCodeBlock = hasCodeBlock,
        bodyMode = bodyMode,
        labels = labels,
    )
}

fun ChatMessage.rendererParts(): List<UiMessagePart> {
    if (parts.isNotEmpty()) return parts

    val projection = renderProjection()
    return buildList {
        reasoning?.takeIf { it.isNotBlank() }?.let {
            add(UiMessagePart(type = "reasoning", text = it))
        }
        when (projection.bodyMode) {
            ChatBodyMode.MARKDOWN -> content.takeIf { it.isNotBlank() }?.let {
                add(UiMessagePart(type = "markdown_block", text = it))
            }
            ChatBodyMode.CODE_FALLBACK -> content.takeIf { it.isNotBlank() }?.let {
                add(UiMessagePart(type = "code_block", text = it))
            }
            ChatBodyMode.PLAIN_TEXT -> content.takeIf { it.isNotBlank() }?.let {
                add(UiMessagePart(type = "text", text = it))
            }
        }
    }
}

data class RecoverableConversation(
    val conversationId: String,
    val title: String,
    val updatedAt: String,
    val generationState: String,
    val summary: String? = null,
    val pinned: Boolean = false,
    val isRecoverable: Boolean = true,
    val nodeCount: Int = 0,
    val isCurrent: Boolean = false,
)

enum class ConversationCatalogFilter {
    ALL,
    IDLE,
    FAILED,
}

data class ChatDetailState(
    val agentName: String = "VCP Agent",
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val generation: ChatGenerationState = ChatGenerationState(),
    val contentVersion: Long = 0L,
    val recoverableConversations: List<RecoverableConversation> = emptyList(),
    val isRecoveringConversation: Boolean = false,
    val recoveryNotice: String? = null,
    val recoveryFocusMessageId: String? = null,
    val stickToBottom: Boolean = true,
    val isConversationCatalogExpanded: Boolean = false,
    val conversationCatalogQuery: String = "",
    val conversationCatalogFilter: ConversationCatalogFilter = ConversationCatalogFilter.ALL,
) {
    val isTyping: Boolean
        get() = generation.phase.isActive()
}

fun String.normalizedGenerationState(): String = trim().lowercase()

fun String.isActiveGenerationState(): Boolean = normalizedGenerationState() in setOf(
    "requesting",
    "started",
    "streaming",
)

fun String.isFailureGenerationState(): Boolean = normalizedGenerationState() == "failed"

fun String.isIdleLikeGenerationState(): Boolean = normalizedGenerationState() in setOf(
    "idle",
    "completed",
    "cancelled",
)

fun String.isRecoverableGenerationState(): Boolean = !isActiveGenerationState()

data class ChatDraftState(
    val currentInput: String = "",
) {
    val canSend: Boolean
        get() = currentInput.isNotBlank()
}
