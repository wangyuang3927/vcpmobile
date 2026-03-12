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
    val title: String? = null,
    val url: String? = null,
    val mime: String? = null,
    val state: String? = null,
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

private val TOOL_RENDER_PART_TYPES = setOf("tool", "tool_call", "tool_result")
private val AST_RENDER_PART_TYPES = setOf("text", "markdown_block")

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

fun UiMessagePart.normalizedType(): String = type.trim().lowercase()

fun UiMessagePart.isAstRenderableBodyPart(): Boolean = normalizedType() in AST_RENDER_PART_TYPES

fun List<UiMessagePart>.supportsAstBodyRendering(): Boolean {
    val nonReasoningParts = filterNot { it.normalizedType() == "reasoning" }
    return nonReasoningParts.singleOrNull()?.isAstRenderableBodyPart() == true
}

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
            if (part.normalizedType() != "reasoning") {
                append(part.compatibilityText())
            }
        }
    }
    val reasoning = buildString {
        this@toCompatibilityProjection.forEach { part ->
            if (part.normalizedType() == "reasoning") {
                append(part.text)
            }
        }
    }.ifBlank { null }
    val partTypes = this.map { it.normalizedType() }.distinct()

    return UiMessageCompatibilityProjection(
        content = content,
        reasoning = reasoning,
        partTypes = partTypes,
    )
}

private fun UiMessagePart.compatibilityText(): String = when (normalizedType()) {
    "code_block" -> buildString {
        if (!language.isNullOrBlank()) {
            append("```").append(language).append('\n')
        } else {
            append("```\n")
        }
        append(text)
        if (!text.endsWith("\n")) append('\n')
        append("```")
    }
    "image", "document" -> listOfNotNull(
        title?.takeIf { it.isNotBlank() },
        url?.takeIf { it.isNotBlank() },
        mime?.takeIf { it.isNotBlank() },
        text.takeIf { it.isNotBlank() && it != title && it != url },
    ).joinToString("\n")
    "tool" -> buildString {
        title?.takeIf { it.isNotBlank() }?.let { append(it) }
        state?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
        if (text.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(text)
        }
    }
    "tool_call", "tool_result" -> buildString {
        val toolName = title?.takeIf { it.isNotBlank() } ?: language?.takeIf { it.isNotBlank() }
        toolName?.let { append(it) }
        if (isNotEmpty()) append(" · ")
        append(if (normalizedType() == "tool_call") "call" else "result")
        if (text.isNotBlank()) {
            append('\n')
            append(text)
        }
    }
    else -> text
}

fun ChatMessage.renderProjection(): ChatRenderProjection {
    val normalizedPartTypes = if (parts.isNotEmpty()) {
        parts.map { it.normalizedType() }
    } else {
        partTypes.map { it.trim().lowercase() }
    }.distinct()
    val hasReasoning = reasoning?.isNotBlank() == true || "reasoning" in normalizedPartTypes
    val hasMarkdown = ast != null || "markdown_block" in normalizedPartTypes
    val hasCodeBlock = "code_block" in normalizedPartTypes
    val hasImage = "image" in normalizedPartTypes
    val hasDocument = "document" in normalizedPartTypes
    val hasTool = normalizedPartTypes.any { it in TOOL_RENDER_PART_TYPES }
    val hasError = "error" in normalizedPartTypes
    val bodyMode = when {
        hasCodeBlock && !hasMarkdown -> ChatBodyMode.CODE_FALLBACK
        hasMarkdown -> ChatBodyMode.MARKDOWN
        else -> ChatBodyMode.PLAIN_TEXT
    }
    val labels = buildList {
        if (hasReasoning) add("思考")
        if (hasImage) add("图片")
        if (hasDocument) add("文档")
        if (hasTool) add("工具")
        if (hasError) add("错误")
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
