package com.vcp.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vcp.mobile.data.network.AstStreamParser
import com.vcp.mobile.data.network.HubMessage
import com.vcp.mobile.data.network.HubSendMessageRequest
import com.vcp.mobile.data.network.HubStreamEvent
import com.vcp.mobile.data.network.RustChatEventEnvelope
import com.vcp.mobile.data.network.RustChatEventKind
import com.vcp.mobile.data.network.RustChatEventParser
import com.vcp.mobile.data.network.RustMessagePart
import com.vcp.mobile.data.network.RustToolCallEvent
import com.vcp.mobile.data.network.RustToolCallPhase
import com.vcp.mobile.data.network.toMessageSender
import com.vcp.mobile.data.network.toRole
import com.vcp.mobile.data.recovery.RecoveryStore
import com.vcp.mobile.data.recovery.RecoverySceneAnchor
import com.vcp.mobile.data.repository.HubChatRepository
import com.vcp.mobile.domain.model.ast.MarkdownDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: HubChatRepository,
    private val recoveryStore: RecoveryStore,
) : ViewModel() {

    private var pendingOptimisticUserMessageId: String? = null

    private val _detailState = MutableStateFlow(ChatDetailReducer.initialState())
    val detailState: StateFlow<ChatDetailState> = _detailState.asStateFlow()

    private val _draftState = MutableStateFlow(ChatDraftState())
    val draftState: StateFlow<ChatDraftState> = _draftState.asStateFlow()

    init {
        refreshRecoveryCatalog()
        recoverConversationIfPossible()
    }

    fun onInputChanged(input: String) {
        _draftState.update { it.copy(currentInput = input) }
    }

    fun sendMessage() {
        val userInput = _draftState.value.currentInput.trim()
        if (userInput.isEmpty() || _detailState.value.isTyping) return

        dispatchDetail(ChatDetailAction.UserMessageSubmitted(userInput))
        pendingOptimisticUserMessageId = _detailState.value.messages.lastOrNull()
            ?.takeIf { it.sender == MessageSender.USER && it.nodeId == null && it.variantId == null }
            ?.id
        _draftState.value = ChatDraftState()

        viewModelScope.launch {
            val request = HubSendMessageRequest(
                model = "vcp-mobile",
                messages = _detailState.value.messages.map { message ->
                    HubMessage(
                        role = message.sender.toRole(),
                        content = message.content
                    )
                },
                conversationId = _detailState.value.conversationId,
                sessionId = "default",
                stream = true
            )

            var assistantMessageKey: String? = _detailState.value.generation.activeMessageKey
            val renderBuffer = StreamingRenderBuffer()
            renderBuffer.onMessageKeyChanged(assistantMessageKey)
            var sawStreamEvent = false

            try {
                repository.observeStream(request).collect { event ->
                    sawStreamEvent = true
                    when (event) {
                        HubStreamEvent.Opened -> {
                            val activeKey = _detailState.value.generation.activeMessageKey
                            dispatchGeneration(ChatGenerationPhase.STARTED, activeKey)
                        }

                        HubStreamEvent.Completed -> {
                            renderBuffer.clear()
                            dispatchGeneration(ChatGenerationPhase.COMPLETED, null)
                        }

                        is HubStreamEvent.Error -> {
                            renderBuffer.clear()
                            discardPendingOptimisticUserMessage()
                            dispatchGeneration(
                                ChatGenerationPhase.FAILED,
                                _detailState.value.generation.activeMessageKey,
                            )
                            dispatchDetail(
                                ChatDetailAction.SystemMessageAppended(
                                    text = "网络错误：${event.throwable.message ?: "未知错误"}",
                                )
                            )
                        }

                        is HubStreamEvent.Message -> {
                            when {
                                event.event == "ast" -> {
                                    val astNodes = AstStreamParser.parseJson(event.data)
                                    val targetMessageKey = assistantMessageKey
                                        ?: _detailState.value.generation.activeMessageKey
                                    if (targetMessageKey != null && astNodes.isNotEmpty()) {
                                        val document = renderBuffer.appendAst(targetMessageKey, astNodes)
                                        if (document != null) {
                                            assistantMessageKey = appendAssistantDelta(
                                                currentMessageId = targetMessageKey,
                                                sender = event.role?.toMessageSender() ?: MessageSender.AGENT,
                                                appendText = "",
                                                ast = document,
                                            )
                                            renderBuffer.onMessageKeyChanged(assistantMessageKey)
                                        }
                                    }
                                }

                                event.event == "chat_event" -> {
                                    val envelope = RustChatEventParser.parseEnvelope(event.data) ?: return@collect
                                    envelope.conversationId?.let { bindConversationId(it) }
                                    assistantMessageKey = handleRustChatEnvelope(
                                        envelope = envelope,
                                        currentMessageKey = assistantMessageKey,
                                    )
                                    renderBuffer.onMessageKeyChanged(assistantMessageKey)
                                }

                                else -> {
                                    val tokenText = extractTokenText(event.data)
                                    if (tokenText.isNotBlank()) {
                                        val astDoc = renderBuffer.currentDocumentFor(assistantMessageKey)

                                        assistantMessageKey = appendAssistantDelta(
                                            currentMessageId = assistantMessageKey,
                                            sender = event.role?.toMessageSender() ?: MessageSender.AGENT,
                                            appendText = tokenText,
                                            ast = astDoc,
                                        )
                                        renderBuffer.onMessageKeyChanged(assistantMessageKey)
                                    }
                                }
                            }
                        }
                    }
                }
                if (sawStreamEvent && _detailState.value.generation.phase in setOf(
                        ChatGenerationPhase.REQUESTING,
                        ChatGenerationPhase.STARTED,
                        ChatGenerationPhase.STREAMING,
                    )
                ) {
                    renderBuffer.clear()
                    dispatchGeneration(ChatGenerationPhase.COMPLETED, null)
                }
            } catch (error: Throwable) {
                renderBuffer.clear()
                discardPendingOptimisticUserMessage()
                dispatchGeneration(
                    ChatGenerationPhase.FAILED,
                    _detailState.value.generation.activeMessageKey,
                )
                dispatchDetail(
                    ChatDetailAction.SystemMessageAppended(
                        text = "请求失败：${error.message ?: "未知异常"}",
                    )
                )
            }
        }
    }

    fun recoverConversation(conversationId: String) {
        viewModelScope.launch {
            setConversationCatalogExpanded(false)
            recoverConversationById(conversationId, origin = "manual")
        }
    }

    fun startNewConversation() {
        val previousConversationId = _detailState.value.conversationId
        pendingOptimisticUserMessageId = null
        dispatchDetail(ChatDetailAction.StartNewConversation)
        dispatchDetail(ChatDetailAction.ConversationCatalogExpandedChanged(false))
        viewModelScope.launch {
            recoveryStore.clearLastConversationId()
            previousConversationId?.let { recoveryStore.clearSceneAnchor(it) }
            refreshRecoveryCatalog()
        }
    }

    fun persistSceneAnchor(lastVisibleMessageId: String?, stickToBottom: Boolean) {
        val conversationId = _detailState.value.conversationId ?: return
        dispatchDetail(
            ChatDetailAction.SceneViewportChanged(
                stickToBottom = stickToBottom,
            )
        )
        viewModelScope.launch {
            recoveryStore.saveSceneAnchor(
                RecoverySceneAnchor(
                    conversationId = conversationId,
                    lastMessageId = lastVisibleMessageId,
                    stickToBottom = stickToBottom,
                )
            )
        }
    }

    fun consumeRecoveryScene() {
        dispatchDetail(ChatDetailAction.RecoverySceneConsumed)
    }

    fun setConversationCatalogExpanded(expanded: Boolean) {
        dispatchDetail(ChatDetailAction.ConversationCatalogExpandedChanged(expanded))
    }

    fun onConversationCatalogQueryChanged(query: String) {
        dispatchDetail(ChatDetailAction.ConversationCatalogQueryChanged(query))
    }

    fun onConversationCatalogFilterChanged(filter: ConversationCatalogFilter) {
        dispatchDetail(ChatDetailAction.ConversationCatalogFilterChanged(filter))
    }

    fun refreshRecoveryCatalog() {
        viewModelScope.launch {
            runCatching {
                repository.listConversations()
                    .filter { it.isRecoverable && it.generationState.isRecoverableGenerationState() }
                    .sortedByDescending { it.updatedAt }
                    .map { summary ->
                        RecoverableConversation(
                            conversationId = summary.conversationId,
                            title = summary.title.ifBlank { "未命名会话" },
                            updatedAt = summary.updatedAt,
                            generationState = summary.generationState,
                            summary = summary.summary,
                            pinned = summary.pinned,
                            isRecoverable = summary.isRecoverable,
                            nodeCount = summary.nodeCount,
                            isCurrent = summary.conversationId == _detailState.value.conversationId,
                        )
                    }
            }.onSuccess { conversations ->
                dispatchDetail(ChatDetailAction.RecoveryCatalogUpdated(conversations))
                if (conversations.isNotEmpty()) {
                    dispatchDetail(ChatDetailAction.RecoveryNoticeChanged(null))
                }
            }
        }
    }

    private fun recoverConversationIfPossible() {
        viewModelScope.launch {
            runCatching {
                val persistedConversationId = recoveryStore.lastConversationId()
                val recoverableConversationId = repository.listConversations()
                    .filter { it.isRecoverable && it.generationState.isRecoverableGenerationState() }
                    .sortedByDescending { it.updatedAt }
                    .firstOrNull()
                    ?.conversationId
                val conversationId = persistedConversationId
                    ?: recoverableConversationId

                if (conversationId.isNullOrBlank()) {
                    if (persistedConversationId != null) {
                        recoveryStore.clearLastConversationId()
                    }
                    return@runCatching
                }

                recoverConversationById(conversationId, origin = "startup")
            }.onFailure {
                dispatchDetail(
                    ChatDetailAction.RecoveryNoticeChanged(
                        "最近会话自动恢复失败，但不影响新对话。"
                    )
                )
            }
        }
    }

    private fun dispatchDetail(action: ChatDetailAction) {
        _detailState.update { state ->
            ChatDetailReducer.reduce(state, action)
        }
    }

    private fun bindConversationId(conversationId: String) {
        dispatchDetail(ChatDetailAction.ConversationBound(conversationId))
        dispatchDetail(ChatDetailAction.RecoveryNoticeChanged(null))
        viewModelScope.launch {
            recoveryStore.saveLastConversationId(conversationId)
            refreshRecoveryCatalog()
        }
    }

    private fun dispatchGeneration(
        phase: ChatGenerationPhase,
        messageKey: String?,
    ) {
        dispatchDetail(
            ChatDetailAction.GenerationLifecycleChanged(
                phase = phase,
                messageKey = messageKey,
            )
        )
    }

    private fun appendAssistantDelta(
        currentMessageId: String?,
        sender: MessageSender,
        appendText: String,
        appendReasoning: String = "",
        ast: MarkdownDocument? = null,
        nodeId: String? = null,
        variantId: String? = null,
        parts: List<UiMessagePart> = emptyList(),
        partTypes: List<String> = emptyList(),
    ): String {
        val result = ChatDetailReducer.appendAssistantDelta(
            state = _detailState.value,
            currentMessageId = currentMessageId,
            sender = sender,
            appendText = appendText,
            appendReasoning = appendReasoning,
            ast = ast,
            nodeId = nodeId,
            variantId = variantId,
            parts = parts,
            partTypes = partTypes,
        )
        _detailState.value = result.state
        return result.messageId
    }

    private fun replaceSnapshotMessage(
        messageId: String,
        sender: MessageSender,
        content: String,
        reasoning: String = "",
        ast: MarkdownDocument? = null,
        nodeId: String? = null,
        variantId: String? = null,
        parts: List<UiMessagePart> = emptyList(),
        partTypes: List<String> = emptyList(),
    ): String {
        val fallbackMessageId = resolveSnapshotFallbackMessageId(
            sender = sender,
            content = content,
            reasoning = reasoning,
            parts = parts,
            partTypes = partTypes,
        )
        val result = ChatDetailReducer.replaceOrUpsertSnapshot(
            state = _detailState.value,
            messageId = messageId,
            sender = sender,
            content = content,
            reasoning = reasoning,
            ast = ast,
            nodeId = nodeId,
            variantId = variantId,
            parts = parts,
            partTypes = partTypes,
            fallbackMessageId = fallbackMessageId,
            fallbackNodeId = nodeId,
        )
        _detailState.value = result.state
        if (
            sender == MessageSender.USER &&
            fallbackMessageId != null &&
            result.state.messages.none { it.id == fallbackMessageId }
        ) {
            pendingOptimisticUserMessageId = null
        }
        return result.messageId
    }

    private fun currentMessage(messageId: String?): ChatMessage? {
        if (messageId == null) return null
        return _detailState.value.messages.firstOrNull { it.id == messageId }
    }

    private fun resolveSnapshotFallbackMessageId(
        sender: MessageSender,
        content: String,
        reasoning: String,
        parts: List<UiMessagePart>,
        partTypes: List<String>,
    ): String? {
        if (sender != MessageSender.USER) {
            return null
        }
        val pendingId = pendingOptimisticUserMessageId ?: return null
        val pendingMessage = currentMessage(pendingId) ?: return null
        if (
            pendingMessage.sender != MessageSender.USER ||
            pendingMessage.nodeId != null ||
            pendingMessage.variantId != null
        ) {
            return null
        }

        val compatibilityProjection = parts.toCompatibilityProjection()
        val snapshotContent = compatibilityProjection.content.ifBlank { content }
        val snapshotReasoning = compatibilityProjection.reasoning ?: reasoning.ifBlank { null }
        val snapshotPartTypes = compatibilityProjection.partTypes.ifEmpty { partTypes }

        val sameContent = pendingMessage.content == snapshotContent
        val sameReasoning = pendingMessage.reasoning == snapshotReasoning
        val samePartTypes = snapshotPartTypes.isEmpty() ||
            (pendingMessage.parts.isEmpty() && pendingMessage.partTypes.isEmpty()) ||
            pendingMessage.partTypes == snapshotPartTypes ||
            pendingMessage.parts.map { it.type.trim().lowercase() } == snapshotPartTypes

        return pendingId.takeIf { sameContent && sameReasoning && samePartTypes }
    }

    private fun discardPendingOptimisticUserMessage() {
        val pendingId = pendingOptimisticUserMessageId ?: return
        val pendingMessage = _detailState.value.messages.firstOrNull { it.id == pendingId }
        if (
            pendingMessage != null &&
            pendingMessage.sender == MessageSender.USER &&
            pendingMessage.nodeId == null &&
            pendingMessage.variantId == null
        ) {
            dispatchDetail(ChatDetailAction.MessageRemoved(pendingId))
        }
        pendingOptimisticUserMessageId = null
    }

    private fun handleRustChatEnvelope(
        envelope: RustChatEventEnvelope,
        currentMessageKey: String?,
    ): String? {
        return when (envelope.kind) {
            RustChatEventKind.CONVERSATION_SNAPSHOT -> {
                val snapshots = RustChatEventParser.extractSnapshotMessages(envelope.data)
                if (snapshots.isEmpty()) return currentMessageKey

                var latestMessageKey: String? = currentMessageKey
                snapshots.forEach { snapshot ->
                    val messageKey = snapshot.identity.messageKey
                    latestMessageKey = replaceSnapshotMessage(
                        messageId = messageKey,
                        sender = snapshot.role.toMessageSender(),
                        content = snapshot.delta.appendedText,
                        reasoning = snapshot.delta.appendedReasoning,
                        nodeId = snapshot.identity.nodeId,
                        variantId = snapshot.identity.variantId,
                        parts = snapshot.delta.parts.toUiMessageParts(),
                        partTypes = snapshot.delta.partTypes,
                    )
                }
                latestMessageKey
            }

            RustChatEventKind.GENERATION_STARTED -> {
                val identity = RustChatEventParser.extractGenerationIdentity(envelope.data)
                val messageKey = identity?.messageKey ?: currentMessageKey
                dispatchGeneration(ChatGenerationPhase.STARTED, messageKey)
                messageKey
            }

            RustChatEventKind.GENERATION_PART_DELTA -> {
                val identity = RustChatEventParser.extractGenerationIdentity(envelope.data)
                val messageKey = identity?.messageKey ?: currentMessageKey
                val delta = RustChatEventParser.extractPartDelta(envelope.data)
                val existingMessage = currentMessage(messageKey)
                val duplicatesExistingSnapshot = existingMessage != null &&
                    delta.appendedText.isNotBlank() &&
                    existingMessage.content == delta.appendedText &&
                    (
                        delta.appendedReasoning.isBlank() ||
                            existingMessage.reasoning.orEmpty() == delta.appendedReasoning
                        )
                if (duplicatesExistingSnapshot) {
                    return messageKey
                }
                appendAssistantDelta(
                    currentMessageId = messageKey,
                    sender = MessageSender.AGENT,
                    appendText = delta.appendedText,
                    appendReasoning = delta.appendedReasoning,
                    nodeId = identity?.nodeId,
                    variantId = identity?.variantId,
                    parts = delta.parts.toUiMessageParts(),
                    partTypes = delta.partTypes,
                )
            }

            RustChatEventKind.CONVERSATION_NODE_UPSERT -> {
                val snapshot = RustChatEventParser.extractNodeUpsertMessage(envelope.data)
                    ?: return currentMessageKey
                replaceSnapshotMessage(
                    messageId = snapshot.identity.messageKey,
                    sender = snapshot.role.toMessageSender(),
                    content = snapshot.delta.appendedText,
                    reasoning = snapshot.delta.appendedReasoning,
                    nodeId = snapshot.identity.nodeId,
                    variantId = snapshot.identity.variantId,
                    parts = snapshot.delta.parts.toUiMessageParts(),
                    partTypes = snapshot.delta.partTypes,
                )
            }

            RustChatEventKind.GENERATION_COMPLETED -> {
                dispatchGeneration(ChatGenerationPhase.COMPLETED, null)
                currentMessageKey
            }

            RustChatEventKind.GENERATION_CANCELLED -> {
                dispatchGeneration(ChatGenerationPhase.CANCELLED, currentMessageKey)
                dispatchDetail(
                    ChatDetailAction.SystemMessageAppended(
                        text = envelope.data.optString("message").ifBlank { "生成已取消" },
                    )
                )
                currentMessageKey
            }

            RustChatEventKind.GENERATION_FAILED -> {
                discardPendingOptimisticUserMessage()
                dispatchGeneration(ChatGenerationPhase.FAILED, currentMessageKey)
                val error = RustChatEventParser.extractEventError(envelope.data)
                dispatchDetail(
                    ChatDetailAction.SystemMessageAppended(
                        text = error?.message ?: "生成失败",
                    )
                )
                currentMessageKey
            }

            RustChatEventKind.ENGINE_ERROR -> {
                discardPendingOptimisticUserMessage()
                dispatchGeneration(ChatGenerationPhase.FAILED, currentMessageKey)
                val error = RustChatEventParser.extractEventError(envelope.data)
                dispatchDetail(
                    ChatDetailAction.SystemMessageAppended(
                        text = error?.message ?: "引擎异常",
                    )
                )
                currentMessageKey
            }

            RustChatEventKind.TOOL_CALL_STARTED,
            RustChatEventKind.TOOL_CALL_COMPLETED,
            RustChatEventKind.TOOL_CALL_FAILED,
            RustChatEventKind.TOOL_CALL_CANCELLED -> {
                val toolEvent = RustChatEventParser.extractToolCallEvent(envelope.kind, envelope.data)
                    ?: return currentMessageKey
                appendAssistantDelta(
                    currentMessageId = toolEvent.identity.messageKey,
                    sender = MessageSender.AGENT,
                    appendText = "",
                    nodeId = toolEvent.identity.nodeId,
                    variantId = toolEvent.identity.variantId,
                    parts = listOf(toolEvent.toUiMessagePart()),
                    partTypes = listOf("tool"),
                )
            }

            RustChatEventKind.CONVERSATION_LIST_INVALIDATE,
            RustChatEventKind.CONVERSATION_NODE_SELECT,
            RustChatEventKind.CONVERSATION_META_UPDATE,
            RustChatEventKind.DRAFT_UPDATED,
            RustChatEventKind.DRAFT_CLEARED,
            RustChatEventKind.AUTH_QR_PLACEHOLDER -> currentMessageKey
        }
    }

    private fun hydrateConversationSnapshot(envelope: RustChatEventEnvelope) {
        if (envelope.kind != RustChatEventKind.CONVERSATION_SNAPSHOT) return
        val conversationId = envelope.conversationId ?: return
        val snapshots = RustChatEventParser.extractSnapshotMessages(envelope.data)
        if (snapshots.isEmpty()) return
        pendingOptimisticUserMessageId = null

        val messages = snapshots.map { snapshot ->
            ChatMessage(
                id = snapshot.identity.messageKey,
                sender = snapshot.role.toMessageSender(),
                content = snapshot.delta.appendedText,
                reasoning = snapshot.delta.appendedReasoning.ifBlank { null },
                nodeId = snapshot.identity.nodeId,
                variantId = snapshot.identity.variantId,
                parts = snapshot.delta.parts.toUiMessageParts(),
                partTypes = snapshot.delta.partTypes,
            )
        }

        dispatchDetail(
            ChatDetailAction.ConversationHydrated(
                conversationId = conversationId,
                messages = messages,
            )
        )
        dispatchDetail(ChatDetailAction.RecoveryNoticeChanged(null))
        viewModelScope.launch {
            recoveryStore.saveLastConversationId(conversationId)
        }
    }

    private suspend fun recoverConversationById(conversationId: String, origin: String) {
        dispatchDetail(ChatDetailAction.RecoveryLoadingChanged(true))
        try {
            val envelope = repository.fetchConversationSnapshot(conversationId)
                ?: run {
                    dispatchDetail(
                        ChatDetailAction.RecoveryNoticeChanged(
                            if (origin == "manual") {
                                "未找到该会话的恢复快照。"
                            } else {
                                "最近会话不可恢复，已回退到新对话。"
                            }
                        )
                    )
                    return
                }
            hydrateConversationSnapshot(envelope)
            val sceneAnchor = recoveryStore.loadSceneAnchor(conversationId)
            dispatchDetail(
                ChatDetailAction.RecoverySceneApplied(
                    focusMessageId = sceneAnchor?.lastMessageId,
                    stickToBottom = sceneAnchor?.stickToBottom ?: true,
                )
            )
        } catch (_: Throwable) {
            dispatchDetail(
                ChatDetailAction.RecoveryNoticeChanged(
                    if (origin == "manual") {
                        "恢复会话失败，请稍后重试。"
                    } else {
                        "最近会话自动恢复失败，但不影响新对话。"
                    }
                )
            )
        } finally {
            dispatchDetail(ChatDetailAction.RecoveryLoadingChanged(false))
        }
    }

    private fun extractTokenText(rawData: String): String {
        return runCatching {
            val json = JSONObject(rawData)
            when {
                json.has("text") -> json.optString("text")
                json.has("content") -> json.optString("content")
                json.has("message") -> json.optString("message")
                else -> rawData
            }
        }.getOrDefault(rawData)
    }
}

private fun List<RustMessagePart>.toUiMessageParts(): List<UiMessagePart> = map { part ->
    UiMessagePart(
        type = part.type,
        text = part.text,
        language = part.language,
        title = part.title,
        url = part.url,
        mime = part.mime,
        state = part.state,
        partId = part.partId,
        orderIndex = part.orderIndex,
        toolCallId = part.toolCallId,
    )
}

private fun RustToolCallEvent.toUiMessagePart(): UiMessagePart {
    val detailText = when (phase) {
        RustToolCallPhase.STARTED -> argumentsJson.orEmpty()
        RustToolCallPhase.COMPLETED -> ""
        RustToolCallPhase.FAILED -> error?.message.orEmpty()
        RustToolCallPhase.CANCELLED -> message.orEmpty()
    }
    return UiMessagePart(
        type = "tool",
        text = detailText,
        title = toolName,
        state = phase.name.lowercase(),
        partId = "tool-call:$toolCallId",
        toolCallId = toolCallId,
    )
}
