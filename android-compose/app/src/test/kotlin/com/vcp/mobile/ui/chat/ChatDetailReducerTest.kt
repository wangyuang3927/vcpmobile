package com.vcp.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDetailReducerTest {

    @Test
    fun `user submit enters requesting and completed clears typing`() {
        val submitted = ChatDetailReducer.reduce(
            ChatDetailReducer.initialState(),
            ChatDetailAction.UserMessageSubmitted("hi")
        )

        assertEquals(ChatGenerationPhase.REQUESTING, submitted.generation.phase)
        assertTrue(submitted.isTyping)

        val completed = ChatDetailReducer.reduce(
            submitted,
            ChatDetailAction.GenerationLifecycleChanged(
                phase = ChatGenerationPhase.IDLE,
                messageKey = null,
            )
        )

        assertEquals(ChatGenerationPhase.IDLE, completed.generation.phase)
        assertFalse(completed.isTyping)
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

        val first = ChatDetailReducer.replaceOrUpsertAssistantSnapshot(
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
        val second = ChatDetailReducer.replaceOrUpsertAssistantSnapshot(
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
        assertEquals("node-1:variant-1", second.state.generation.activeMessageKey)
        assertTrue(second.state.contentVersion > started.contentVersion)
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
}
