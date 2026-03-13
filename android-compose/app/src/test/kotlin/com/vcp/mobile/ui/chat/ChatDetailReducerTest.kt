package com.vcp.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDetailReducerTest {

    @Test
    fun `user submit enters requesting and completed remains terminal without typing`() {
        val submitted = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.UserMessageSubmitted("hi")
        )

        assertEquals(ChatGenerationPhase.REQUESTING, submitted.generation.phase)
        assertTrue(submitted.isTyping)

        val completed = ChatDetailReducer.reduce(
            submitted,
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.COMPLETED,
                messageKey = null,
            )
        )

        assertEquals(ChatGenerationPhase.COMPLETED, completed.generation.phase)
        assertFalse(completed.isTyping)
    }

    @Test
    fun `system error message does not erase failed lifecycle state`() {
        val failed = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.FAILED,
                messageKey = "node-1:variant-1",
            )
        )

        val updated = ChatDetailReducer.reduce(
            failed,
            ChatDetailAction.SystemMessageAppended("生成失败")
        )

        assertEquals(ChatGenerationPhase.FAILED, updated.generation.phase)
        assertEquals("node-1:variant-1", updated.generation.activeMessageKey)
        assertEquals("生成失败", updated.messages.last().content)
    }

    @Test
    fun `started keeps existing key and completed clears it`() {
        val requesting = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.REQUESTING,
                messageKey = "node-1:variant-1",
            )
        )

        val started = ChatDetailReducer.reduce(
            requesting,
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.STARTED,
                messageKey = null,
            )
        )
        val completed = ChatDetailReducer.reduce(
            started,
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.COMPLETED,
                messageKey = "node-1:variant-1",
            )
        )

        assertEquals(ChatGenerationPhase.STARTED, started.generation.phase)
        assertEquals("node-1:variant-1", started.generation.activeMessageKey)
        assertEquals(ChatGenerationPhase.COMPLETED, completed.generation.phase)
        assertEquals(null, completed.generation.activeMessageKey)
    }

    @Test
    fun `assistant delta preserves stable message id and active key`() {
        val started = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.STREAMING,
                messageKey = "node-1:variant-1",
            )
        )

        val first = ChatDetailReducer.appendAssistantDelta(
            state = started,
            currentMessageId = "node-1:variant-1",
            sender = MessageSender.AGENT,
            appendText = "hello",
            appendReasoning = "thinking",
            nodeId = "node-1",
            variantId = "variant-1",
            parts = listOf(
                UiMessagePart(type = "reasoning", text = "thinking"),
                UiMessagePart(type = "text", text = "hello"),
            ),
            partTypes = listOf("reasoning", "text"),
        )
        val second = ChatDetailReducer.appendAssistantDelta(
            state = first.state,
            currentMessageId = first.messageId,
            sender = MessageSender.AGENT,
            appendText = " world",
            nodeId = "node-1",
            variantId = "variant-1",
            parts = listOf(UiMessagePart(type = "text", text = " world")),
            partTypes = listOf("text"),
        )

        assertEquals("node-1:variant-1", first.messageId)
        assertEquals("node-1:variant-1", second.messageId)
        assertEquals(ChatGenerationPhase.STREAMING, second.state.generation.phase)
        assertEquals("node-1:variant-1", second.state.generation.activeMessageKey)

        val lastMessage = second.state.messages.last()
        assertEquals("hello world", lastMessage.content)
        assertEquals("thinking", lastMessage.reasoning)
        assertEquals("node-1", lastMessage.nodeId)
        assertEquals("variant-1", lastMessage.variantId)
        assertEquals(
            listOf(
                UiMessagePart(type = "reasoning", text = "thinking"),
                UiMessagePart(type = "text", text = "hello"),
                UiMessagePart(type = "text", text = " world"),
            ),
            lastMessage.parts
        )
        assertEquals(listOf("reasoning", "text"), lastMessage.partTypes)
        assertTrue(second.state.isTyping)
    }

    @Test
    fun `snapshot replace upserts without duplicating existing content`() {
        val started = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.STREAMING,
                messageKey = "node-1:variant-1",
            )
        )

        val first = ChatDetailReducer.replaceOrUpsertSnapshot(
            state = started,
            messageId = "node-1:variant-1",
            sender = MessageSender.AGENT,
            content = "hello",
            reasoning = "thinking",
            nodeId = "node-1",
            variantId = "variant-1",
            parts = listOf(
                UiMessagePart(type = "reasoning", text = "thinking"),
                UiMessagePart(type = "markdown_block", text = "hello"),
            ),
            partTypes = listOf("reasoning", "markdown_block"),
        )
        val second = ChatDetailReducer.replaceOrUpsertSnapshot(
            state = first.state,
            messageId = "node-1:variant-1",
            sender = MessageSender.AGENT,
            content = "hello",
            reasoning = "thinking",
            nodeId = "node-1",
            variantId = "variant-1",
            parts = listOf(
                UiMessagePart(type = "reasoning", text = "thinking"),
                UiMessagePart(type = "markdown_block", text = "hello"),
            ),
            partTypes = listOf("reasoning", "markdown_block"),
        )

        assertEquals(2, second.state.messages.size)
        val lastMessage = second.state.messages.last()
        assertEquals("hello", lastMessage.content)
        assertEquals("thinking", lastMessage.reasoning)
        assertEquals("node-1", lastMessage.nodeId)
        assertEquals("variant-1", lastMessage.variantId)
        assertEquals(
            listOf(
                UiMessagePart(type = "reasoning", text = "thinking"),
                UiMessagePart(type = "markdown_block", text = "hello"),
            ),
            lastMessage.parts
        )
        assertEquals(listOf("reasoning", "markdown_block"), lastMessage.partTypes)
        assertEquals(ChatGenerationPhase.STREAMING, second.state.generation.phase)
        assertEquals("node-1:variant-1", second.state.generation.activeMessageKey)
        assertTrue(second.state.contentVersion > started.contentVersion)
    }

    @Test
    fun `snapshot replace can reconcile optimistic user placeholder with rust identity`() {
        val submitted = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.UserMessageSubmitted("hi")
        )
        val optimisticUser = submitted.messages.last()

        val result = ChatDetailReducer.replaceOrUpsertSnapshot(
            state = submitted,
            messageId = "node-user:variant-user",
            sender = MessageSender.USER,
            content = "hi",
            nodeId = "node-user",
            variantId = "variant-user",
            parts = listOf(UiMessagePart(type = "text", text = "hi")),
            partTypes = listOf("text"),
            fallbackMessageId = optimisticUser.id,
        )

        assertEquals(submitted.messages.size, result.state.messages.size)
        val reconciledUser = result.state.messages.last()
        assertEquals("node-user:variant-user", reconciledUser.id)
        assertEquals(MessageSender.USER, reconciledUser.sender)
        assertEquals("hi", reconciledUser.content)
        assertEquals("node-user", reconciledUser.nodeId)
        assertEquals("variant-user", reconciledUser.variantId)
        assertEquals(listOf(UiMessagePart(type = "text", text = "hi")), reconciledUser.parts)
        assertEquals(listOf("text"), reconciledUser.partTypes)
        assertEquals(optimisticUser.timestampMillis, reconciledUser.timestampMillis)
    }

    @Test
    fun `snapshot replace updates selected variant for existing node without duplicating bubble`() {
        val existingMessage = ChatMessage(
            id = "node-1:variant-old",
            sender = MessageSender.AGENT,
            content = "old",
            nodeId = "node-1",
            variantId = "variant-old",
            parts = listOf(UiMessagePart(type = "text", text = "old")),
            partTypes = listOf("text"),
            timestampMillis = 1234L,
        )
        val initial = ChatDetailReducer.initialState()
        val state = initial.copy(messages = initial.messages + existingMessage)

        val result = ChatDetailReducer.replaceOrUpsertSnapshot(
            state = state,
            messageId = "node-1:variant-new",
            sender = MessageSender.AGENT,
            content = "new",
            nodeId = "node-1",
            variantId = "variant-new",
            parts = listOf(UiMessagePart(type = "text", text = "new")),
            partTypes = listOf("text"),
            fallbackNodeId = "node-1",
        )

        assertEquals(state.messages.size, result.state.messages.size)
        val updated = result.state.messages.last()
        assertEquals("node-1:variant-new", updated.id)
        assertEquals("node-1", updated.nodeId)
        assertEquals("variant-new", updated.variantId)
        assertEquals("new", updated.content)
        assertEquals(listOf(UiMessagePart(type = "text", text = "new")), updated.parts)
        assertEquals(existingMessage.timestampMillis, updated.timestampMillis)
    }

    @Test
    fun `assistant delta does not resurrect cancelled lifecycle`() {
        val cancelled = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.CANCELLED,
                messageKey = "node-2:variant-2",
            )
        )

        val result = ChatDetailReducer.appendAssistantDelta(
            state = cancelled,
            currentMessageId = "node-2:variant-2",
            sender = MessageSender.AGENT,
            appendText = "late chunk",
            nodeId = "node-2",
            variantId = "variant-2",
            parts = listOf(UiMessagePart(type = "text", text = "late chunk")),
            partTypes = listOf("text"),
        )

        assertEquals(ChatGenerationPhase.CANCELLED, result.state.generation.phase)
        assertEquals("node-2:variant-2", result.state.generation.activeMessageKey)
        assertEquals("late chunk", result.state.messages.last().content)
    }

    @Test
    fun `parts-aware append derives compatibility fields from ordered parts`() {
        val started = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.STREAMING,
                messageKey = "node-2:variant-2",
            )
        )

        val result = ChatDetailReducer.appendAssistantDelta(
            state = started,
            currentMessageId = "node-2:variant-2",
            sender = MessageSender.AGENT,
            appendText = "stale-text-should-not-win",
            appendReasoning = "stale-reasoning",
            nodeId = "node-2",
            variantId = "variant-2",
            parts = listOf(
                UiMessagePart(type = "reasoning", text = "real-thinking"),
                UiMessagePart(type = "markdown_block", text = "hello **real**"),
                UiMessagePart(type = "code_block", text = "println(1)", language = "kotlin"),
            ),
            partTypes = listOf("reasoning", "markdown_block", "code_block"),
        )

        val lastMessage = result.state.messages.last()
        assertEquals("real-thinking", lastMessage.reasoning)
        assertEquals(
            "hello **real**```kotlin\nprintln(1)\n```",
            lastMessage.content
        )
        assertEquals(
            listOf("reasoning", "markdown_block", "code_block"),
            lastMessage.partTypes
        )
    }

    @Test
    fun `assistant delta switches selected variant on same node without duplicating bubble`() {
        val existingMessage = ChatMessage(
            id = "node-2:variant-old",
            sender = MessageSender.AGENT,
            content = "old",
            nodeId = "node-2",
            variantId = "variant-old",
            parts = listOf(UiMessagePart(type = "text", text = "old")),
            partTypes = listOf("text"),
            timestampMillis = 5678L,
        )
        val initial = ChatDetailReducer.initialState()
        val state = initial.copy(
            messages = initial.messages + existingMessage,
            generation = ChatGenerationState(
                phase = ChatGenerationPhase.STREAMING,
                activeMessageKey = "node-2:variant-new",
            ),
        )

        val result = ChatDetailReducer.appendAssistantDelta(
            state = state,
            currentMessageId = "node-2:variant-new",
            sender = MessageSender.AGENT,
            appendText = "new",
            nodeId = "node-2",
            variantId = "variant-new",
            parts = listOf(UiMessagePart(type = "text", text = "new")),
            partTypes = listOf("text"),
        )

        assertEquals(state.messages.size, result.state.messages.size)
        val updated = result.state.messages.last()
        assertEquals("node-2:variant-new", updated.id)
        assertEquals("node-2", updated.nodeId)
        assertEquals("variant-new", updated.variantId)
        assertEquals("new", updated.content)
        assertEquals(listOf(UiMessagePart(type = "text", text = "new")), updated.parts)
        assertEquals(existingMessage.timestampMillis, updated.timestampMillis)
    }

    @Test
    fun `snapshot replace preserves branch selector history when same node selects another variant`() {
        val existingMessage = ChatMessage(
            id = "node-branch:variant-2",
            sender = MessageSender.AGENT,
            content = "new branch",
            nodeId = "node-branch",
            variantId = "variant-2",
            branchSelector = ChatBranchSelector(
                selectedVariantId = "variant-2",
                options = listOf(ChatBranchOption("variant-2")),
            ),
            parts = listOf(UiMessagePart(type = "text", text = "new branch")),
        )
        val initial = ChatDetailReducer.initialState().copy(
            messages = listOf(existingMessage)
        )

        val result = ChatDetailReducer.replaceOrUpsertSnapshot(
            state = initial,
            messageId = "node-branch:variant-1",
            sender = MessageSender.AGENT,
            content = "old branch",
            nodeId = "node-branch",
            variantId = "variant-1",
            parts = listOf(UiMessagePart(type = "text", text = "old branch")),
            partTypes = listOf("text"),
        )

        val updated = result.state.messages.single()
        assertEquals("variant-1", updated.branchSelector.selectedVariantId)
        assertEquals(
            listOf("variant-2", "variant-1"),
            updated.branchSelector.options.map { it.variantId },
        )
        assertTrue(updated.branchSelector.isVisible)
    }

    @Test
    fun `assistant delta merges repeated reasoning part updates by stable order`() {
        val started = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.STREAMING,
                messageKey = "node-3:variant-3",
            )
        )

        val first = ChatDetailReducer.appendAssistantDelta(
            state = started,
            currentMessageId = "node-3:variant-3",
            sender = MessageSender.AGENT,
            appendText = "",
            appendReasoning = "thinking",
            nodeId = "node-3",
            variantId = "variant-3",
            parts = listOf(
                UiMessagePart(type = "reasoning", text = "thinking", orderIndex = 0),
                UiMessagePart(type = "text", text = "hello", orderIndex = 1),
            ),
            partTypes = listOf("reasoning", "text"),
        )
        val second = ChatDetailReducer.appendAssistantDelta(
            state = first.state,
            currentMessageId = first.messageId,
            sender = MessageSender.AGENT,
            appendText = "",
            appendReasoning = "thinking harder",
            nodeId = "node-3",
            variantId = "variant-3",
            parts = listOf(
                UiMessagePart(type = "reasoning", text = "thinking harder", orderIndex = 0),
                UiMessagePart(type = "text", text = "hello world", orderIndex = 1),
            ),
            partTypes = listOf("reasoning", "text"),
        )

        val lastMessage = second.state.messages.last()
        assertEquals("thinking harder", lastMessage.reasoning)
        assertEquals("hello world", lastMessage.content)
        assertEquals(
            listOf(
                UiMessagePart(type = "reasoning", text = "thinking harder", orderIndex = 0),
                UiMessagePart(type = "text", text = "hello world", orderIndex = 1),
            ),
            lastMessage.parts
        )
    }

    @Test
    fun `assistant delta merges tool state updates by tool call identity`() {
        val started = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.STREAMING,
                messageKey = "node-tool:variant-tool",
            )
        )

        val first = ChatDetailReducer.appendAssistantDelta(
            state = started,
            currentMessageId = "node-tool:variant-tool",
            sender = MessageSender.AGENT,
            appendText = "",
            nodeId = "node-tool",
            variantId = "variant-tool",
            parts = listOf(
                UiMessagePart(
                    type = "tool",
                    text = "{\"query\":\"rust\"}",
                    title = "search",
                    state = "started",
                    partId = "tool-call:tool-1",
                    toolCallId = "tool-1",
                )
            ),
            partTypes = listOf("tool"),
        )
        val second = ChatDetailReducer.appendAssistantDelta(
            state = first.state,
            currentMessageId = first.messageId,
            sender = MessageSender.AGENT,
            appendText = "",
            nodeId = "node-tool",
            variantId = "variant-tool",
            parts = listOf(
                UiMessagePart(
                    type = "tool",
                    text = "",
                    title = "search",
                    state = "completed",
                    partId = "tool-call:tool-1",
                    toolCallId = "tool-1",
                )
            ),
            partTypes = listOf("tool"),
        )

        val lastMessage = second.state.messages.last()
        assertEquals(
            listOf(
                UiMessagePart(
                    type = "tool",
                    text = "{\"query\":\"rust\"}",
                    title = "search",
                    state = "completed",
                    partId = "tool-call:tool-1",
                    toolCallId = "tool-1",
                )
            ),
            lastMessage.parts
        )
        assertEquals("search · completed\n{\"query\":\"rust\"}", lastMessage.content)
    }

    @Test
    fun `conversation hydrated replaces placeholder messages and bumps content version`() {
        val initial = ChatDetailReducer.initialState()

        val hydrated = ChatDetailReducer.reduce(
            initial,
            ChatDetailAction.ConversationHydrated(
                conversationId = "conversation-1",
                messages = listOf(
                    ChatMessage(
                        id = "user-1",
                        sender = MessageSender.USER,
                        content = "hello"
                    ),
                    ChatMessage(
                        id = "assistant-1",
                        sender = MessageSender.AGENT,
                        content = "world"
                    )
                )
            )
        )

        assertEquals("conversation-1", hydrated.conversationId)
        assertEquals(2, hydrated.messages.size)
        assertEquals("hello", hydrated.messages.first().content)
        assertEquals("world", hydrated.messages.last().content)
        assertTrue(hydrated.contentVersion > initial.contentVersion)
    }

    @Test
    fun `recovery catalog update stores recent conversations without mutating messages`() {
        val initial = ChatDetailReducer.initialState()

        val updated = ChatDetailReducer.reduce(
            initial,
            ChatDetailAction.RecoveryCatalogUpdated(
                conversations = listOf(
                    RecoverableConversation(
                        conversationId = "c-1",
                        title = "最近一次",
                        updatedAt = "2026-03-11T00:00:00Z",
                        generationState = "idle",
                        isCurrent = false,
                    )
                )
            )
        )

        assertEquals(initial.messages, updated.messages)
        assertEquals(1, updated.recoverableConversations.size)
        assertEquals("c-1", updated.recoverableConversations.first().conversationId)
    }

    @Test
    fun `recovery notice update stores non intrusive banner message`() {
        val initial = ChatDetailReducer.initialState()

        val updated = ChatDetailReducer.reduce(
            initial,
            ChatDetailAction.RecoveryNoticeChanged("恢复失败")
        )

        assertEquals("恢复失败", updated.recoveryNotice)
        assertEquals(initial.messages, updated.messages)
    }

    @Test
    fun `scene viewport change updates in memory stickiness without clearing recovery focus`() {
        val recovered = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.RecoverySceneApplied(
                focusMessageId = "assistant-1",
                stickToBottom = false,
            )
        )

        val updated = ChatDetailReducer.reduce(
            recovered,
            ChatDetailAction.SceneViewportChanged(stickToBottom = true)
        )

        assertTrue(updated.stickToBottom)
        assertEquals("assistant-1", updated.recoveryFocusMessageId)
        assertEquals(recovered.messages, updated.messages)
    }

    @Test
    fun `conversation catalog expanded flag toggles independently`() {
        val initial = ChatDetailReducer.initialState()

        val expanded = ChatDetailReducer.reduce(
            initial,
            ChatDetailAction.ConversationCatalogExpandedChanged(true)
        )
        val collapsed = ChatDetailReducer.reduce(
            expanded,
            ChatDetailAction.ConversationCatalogExpandedChanged(false)
        )

        assertTrue(expanded.isConversationCatalogExpanded)
        assertFalse(collapsed.isConversationCatalogExpanded)
        assertEquals(initial.messages, collapsed.messages)
    }

    @Test
    fun `start new conversation resets active conversation but preserves recovery catalog`() {
        val initial = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.ConversationHydrated(
                conversationId = "conversation-1",
                messages = listOf(
                    ChatMessage(
                        id = "assistant-1",
                        sender = MessageSender.AGENT,
                        content = "old"
                    )
                )
            )
        )
        val withCatalog = ChatDetailReducer.reduce(
            initial,
            ChatDetailAction.RecoveryCatalogUpdated(
                conversations = listOf(
                    RecoverableConversation(
                        conversationId = "conversation-1",
                        title = "旧会话",
                        updatedAt = "2026-03-11T00:00:00Z",
                        generationState = "idle",
                        isCurrent = false,
                    )
                )
            )
        )

        val reset = ChatDetailReducer.reduce(withCatalog, ChatDetailAction.StartNewConversation)

        assertEquals(null, reset.conversationId)
        assertEquals(1, reset.messages.size)
        assertTrue(reset.messages.first().content.contains("你好"))
        assertEquals(1, reset.recoverableConversations.size)
        assertTrue(reset.contentVersion > withCatalog.contentVersion)
    }

    @Test
    fun `conversation catalog query updates independently from chat messages`() {
        val initial = ChatDetailReducer.initialState()

        val updated = ChatDetailReducer.reduce(
            initial,
            ChatDetailAction.ConversationCatalogQueryChanged("demo")
        )

        assertEquals("demo", updated.conversationCatalogQuery)
        assertEquals(initial.messages, updated.messages)
    }

    @Test
    fun `conversation catalog filter updates independently from chat messages`() {
        val initial = ChatDetailReducer.initialState()

        val updated = ChatDetailReducer.reduce(
            initial,
            ChatDetailAction.ConversationCatalogFilterChanged(ConversationCatalogFilter.FAILED)
        )

        assertEquals(ConversationCatalogFilter.FAILED, updated.conversationCatalogFilter)
        assertEquals(initial.messages, updated.messages)
    }

    @Test
    fun `generation state helpers classify active and recoverable states`() {
        assertTrue(ChatGenerationPhase.REQUESTING.isActive())
        assertTrue(ChatGenerationPhase.CANCELLED.isTerminal())
        assertTrue("completed".isRecoverableGenerationState())
        assertTrue("failed".isRecoverableGenerationState())
        assertFalse("started".isRecoverableGenerationState())
    }
}
