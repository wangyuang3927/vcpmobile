package com.vcp.mobile.ui.chat

import com.vcp.mobile.domain.model.ast.MarkdownDocument
import java.util.UUID

/**
 * detail reducer 只负责编排后的 UI 状态演进，不直接依赖 SSE / JSON / 协议事件。
 */
sealed interface ChatDetailAction {
    data object StartNewConversation : ChatDetailAction
    data class UserMessageSubmitted(val text: String) : ChatDetailAction
    data class MessageRemoved(val messageId: String) : ChatDetailAction
    data class ConversationBound(val conversationId: String) : ChatDetailAction
    data class ConversationHydrated(
        val conversationId: String,
        val messages: List<ChatMessage>,
    ) : ChatDetailAction
    data class RecoveryCatalogUpdated(
        val conversations: List<RecoverableConversation>,
    ) : ChatDetailAction
    data class RecoveryLoadingChanged(
        val isLoading: Boolean,
    ) : ChatDetailAction
    data class RecoveryNoticeChanged(
        val notice: String?,
    ) : ChatDetailAction
    data class RecoverySceneApplied(
        val focusMessageId: String?,
        val stickToBottom: Boolean,
    ) : ChatDetailAction
    data class SceneViewportChanged(
        val stickToBottom: Boolean,
    ) : ChatDetailAction
    data object RecoverySceneConsumed : ChatDetailAction
    data class ConversationCatalogExpandedChanged(
        val expanded: Boolean,
    ) : ChatDetailAction
    data class ConversationCatalogQueryChanged(
        val query: String,
    ) : ChatDetailAction
    data class ConversationCatalogFilterChanged(
        val filter: ConversationCatalogFilter,
    ) : ChatDetailAction
    data class GenerationLifecycleChanged(
        val phase: ChatGenerationPhase,
        val messageKey: String? = null,
    ) : ChatDetailAction
    data class SystemMessageAppended(
        val text: String,
        val sender: MessageSender = MessageSender.AGENT,
    ) : ChatDetailAction
}

data class AssistantDeltaResult(
    val state: ChatDetailState,
    val messageId: String,
)

object ChatDetailReducer {

    fun initialState(): ChatDetailState {
        return ChatDetailState(
            messages = listOf(
                ChatMessage(
                    sender = MessageSender.AGENT,
                    content = "你好，我是 VCP Agent。当前已接入 Hub，可开始实时对话。",
                )
            )
        )
    }

    fun reduce(
        state: ChatDetailState,
        action: ChatDetailAction,
    ): ChatDetailState {
        return when (action) {
            ChatDetailAction.StartNewConversation -> {
                initialState().copy(
                    recoverableConversations = state.recoverableConversations,
                    contentVersion = state.contentVersion + 1,
                    stickToBottom = true,
                )
            }

            is ChatDetailAction.UserMessageSubmitted -> state.copy(
                messages = state.messages + ChatMessage(
                    sender = MessageSender.USER,
                    content = action.text,
                ),
                generation = ChatGenerationState(
                    phase = ChatGenerationPhase.REQUESTING,
                    activeMessageKey = null,
                )
            )

            is ChatDetailAction.MessageRemoved -> state.copy(
                messages = state.messages.filterNot { it.id == action.messageId },
                contentVersion = state.contentVersion + 1,
            )

            is ChatDetailAction.ConversationBound -> state.copy(
                conversationId = action.conversationId,
            )

            is ChatDetailAction.ConversationHydrated -> state.copy(
                conversationId = action.conversationId,
                messages = action.messages,
                generation = ChatGenerationState(),
                contentVersion = state.contentVersion + 1,
            )

            is ChatDetailAction.RecoveryCatalogUpdated -> state.copy(
                recoverableConversations = action.conversations,
            )

            is ChatDetailAction.RecoveryLoadingChanged -> state.copy(
                isRecoveringConversation = action.isLoading,
            )

            is ChatDetailAction.RecoveryNoticeChanged -> state.copy(
                recoveryNotice = action.notice,
            )

            is ChatDetailAction.RecoverySceneApplied -> state.copy(
                recoveryFocusMessageId = action.focusMessageId,
                stickToBottom = action.stickToBottom,
            )

            is ChatDetailAction.SceneViewportChanged -> state.copy(
                stickToBottom = action.stickToBottom,
            )

            ChatDetailAction.RecoverySceneConsumed -> state.copy(
                recoveryFocusMessageId = null,
            )

            is ChatDetailAction.ConversationCatalogExpandedChanged -> state.copy(
                isConversationCatalogExpanded = action.expanded,
            )

            is ChatDetailAction.ConversationCatalogQueryChanged -> state.copy(
                conversationCatalogQuery = action.query,
            )

            is ChatDetailAction.ConversationCatalogFilterChanged -> state.copy(
                conversationCatalogFilter = action.filter,
            )

            is ChatDetailAction.GenerationLifecycleChanged -> state.copy(
                generation = ChatGenerationState(
                    phase = action.phase,
                    activeMessageKey = action.messageKey,
                )
            )

            is ChatDetailAction.SystemMessageAppended -> state.copy(
                messages = state.messages + ChatMessage(
                    sender = action.sender,
                    content = action.text,
                ),
                contentVersion = state.contentVersion + 1,
            )
        }
    }

    fun appendAssistantDelta(
        state: ChatDetailState,
        currentMessageId: String?,
        sender: MessageSender,
        appendText: String,
        appendReasoning: String = "",
        ast: MarkdownDocument? = null,
        nodeId: String? = null,
        variantId: String? = null,
        parts: List<UiMessagePart> = emptyList(),
        partTypes: List<String> = emptyList(),
    ): AssistantDeltaResult {
        val targetId = currentMessageId ?: UUID.randomUUID().toString()
        val targetIndex = state.messages.indexOfFirst { it.id == targetId }
        val nodeReplacementIndex = when {
            targetIndex != -1 || nodeId.isNullOrBlank() -> -1
            else -> state.messages.indexOfFirst { it.nodeId == nodeId }
        }

        val updatedMessages = if (targetIndex == -1) {
            val compatibilityProjection = parts.toCompatibilityProjection()
            val replacement = ChatMessage(
                id = targetId,
                sender = sender,
                content = compatibilityProjection.content.ifBlank { appendText },
                reasoning = compatibilityProjection.reasoning ?: appendReasoning.ifBlank { null },
                ast = ast,
                nodeId = nodeId,
                variantId = variantId,
                parts = parts,
                partTypes = compatibilityProjection.partTypes.ifEmpty { partTypes },
            )
            if (nodeReplacementIndex == -1) {
                state.messages + replacement
            } else {
                state.messages.toMutableList().apply {
                    val previous = this[nodeReplacementIndex]
                    this[nodeReplacementIndex] = replacement.copy(timestampMillis = previous.timestampMillis)
                }
            }
        } else {
            state.messages.toMutableList().apply {
                val previous = this[targetIndex]
                val mergedParts = previous.parts + parts
                val compatibilityProjection = mergedParts.toCompatibilityProjection()
                this[targetIndex] = previous.copy(
                    sender = sender,
                    content = compatibilityProjection.content.ifBlank { previous.content + appendText },
                    reasoning = compatibilityProjection.reasoning ?: buildString {
                        append(previous.reasoning.orEmpty())
                        append(appendReasoning)
                    }.ifBlank { null },
                    ast = ast ?: previous.ast,
                    nodeId = nodeId ?: previous.nodeId,
                    variantId = variantId ?: previous.variantId,
                    parts = mergedParts,
                    partTypes = compatibilityProjection.partTypes.ifEmpty {
                        (previous.partTypes + partTypes).distinct()
                    },
                )
            }
        }

        return AssistantDeltaResult(
            state = state.copy(
                messages = updatedMessages,
                generation = state.generation.copy(
                    phase = streamingPhase(state.generation.phase),
                    activeMessageKey = targetId,
                ),
                contentVersion = state.contentVersion + 1,
            ),
            messageId = targetId,
        )
    }

    fun replaceOrUpsertSnapshot(
        state: ChatDetailState,
        messageId: String,
        sender: MessageSender,
        content: String,
        reasoning: String = "",
        ast: MarkdownDocument? = null,
        nodeId: String? = null,
        variantId: String? = null,
        parts: List<UiMessagePart> = emptyList(),
        partTypes: List<String> = emptyList(),
        fallbackMessageId: String? = null,
        fallbackNodeId: String? = null,
    ): AssistantDeltaResult {
        val compatibilityProjection = parts.toCompatibilityProjection()
        val targetIndex = state.messages.indexOfFirst { it.id == messageId }
        val replacementIndex = when {
            targetIndex != -1 -> targetIndex
            fallbackMessageId.isNullOrBlank() -> -1
            else -> state.messages.indexOfFirst { it.id == fallbackMessageId }
        }
        val nodeReplacementIndex = when {
            replacementIndex != -1 || fallbackNodeId.isNullOrBlank() -> -1
            else -> state.messages.indexOfFirst { it.nodeId == fallbackNodeId }
        }
        val replacement = ChatMessage(
            id = messageId,
            sender = sender,
            content = compatibilityProjection.content.ifBlank { content },
            reasoning = compatibilityProjection.reasoning ?: reasoning.ifBlank { null },
            ast = ast,
            nodeId = nodeId,
            variantId = variantId,
            parts = parts,
            partTypes = compatibilityProjection.partTypes.ifEmpty { partTypes },
        )

        val updatedMessages = if (replacementIndex == -1 && nodeReplacementIndex == -1) {
            state.messages + replacement
        } else {
            state.messages.toMutableList().apply {
                val resolvedIndex = if (replacementIndex != -1) replacementIndex else nodeReplacementIndex
                val previous = this[resolvedIndex]
                this[resolvedIndex] = replacement.copy(timestampMillis = previous.timestampMillis)
            }
        }

        return AssistantDeltaResult(
            state = state.copy(
                messages = updatedMessages,
                generation = state.generation.copy(
                    phase = streamingPhase(state.generation.phase),
                    activeMessageKey = messageId,
                ),
                contentVersion = state.contentVersion + 1,
            ),
            messageId = messageId,
        )
    }

    private fun streamingPhase(currentPhase: ChatGenerationPhase): ChatGenerationPhase {
        return when (currentPhase) {
            ChatGenerationPhase.STREAMING -> ChatGenerationPhase.STREAMING
            else -> ChatGenerationPhase.STREAMING
        }
    }
}
