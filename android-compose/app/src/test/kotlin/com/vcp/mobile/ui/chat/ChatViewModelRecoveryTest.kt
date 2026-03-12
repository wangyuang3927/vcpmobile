package com.vcp.mobile.ui.chat

import com.vcp.mobile.data.network.HubConversationSummary
import com.vcp.mobile.data.network.HubRegenerateRequest
import com.vcp.mobile.data.network.HubRelayErrorException
import com.vcp.mobile.data.network.HubSendMessageRequest
import com.vcp.mobile.data.network.HubStreamFailureException
import com.vcp.mobile.data.network.HubSendMessageResponse
import com.vcp.mobile.data.network.HubSelectVariantRequest
import com.vcp.mobile.data.network.HubStreamEvent
import com.vcp.mobile.data.network.RustChatEventEnvelope
import com.vcp.mobile.data.network.RustChatEventKind
import com.vcp.mobile.data.recovery.RecoveryStore
import com.vcp.mobile.data.recovery.RecoverySceneAnchor
import com.vcp.mobile.data.repository.HubChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelRecoveryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `manual recovery hydrates selected conversation`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            conversations = listOf(
                HubConversationSummary(
                    conversationId = "conversation-1",
                    title = "Recovered",
                    updatedAt = "2026-03-11T00:00:00Z",
                    generationState = "idle",
                )
            ),
            snapshotEnvelope = snapshotEnvelope(
                conversationId = "conversation-1",
                nodeId = "node-1",
                role = "assistant",
                text = "hello recovery",
            )
        )
        val recoveryStore = FakeConversationRecoveryStore(null)

        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.recoverConversation("conversation-1")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        assertEquals("conversation-1", state.conversationId)
        assertEquals(1, state.messages.size)
        assertEquals("hello recovery", state.messages.first().content)
        assertNull(state.recoveryNotice)
        assertEquals("conversation-1", recoveryStore.savedConversationId)
    }

    @Test
    fun `manual recovery collapses workbench and keeps recovered scene as active context`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            conversations = listOf(
                HubConversationSummary(
                    conversationId = "conversation-1",
                    title = "Recovered",
                    updatedAt = "2026-03-11T00:00:00Z",
                    generationState = "idle",
                )
            ),
            snapshotEnvelope = snapshotEnvelope(
                conversationId = "conversation-1",
                nodeId = "node-1",
                role = "assistant",
                text = "hello recovery",
            )
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.setConversationCatalogExpanded(true)
        assertTrue(viewModel.detailState.value.isConversationCatalogExpanded)

        viewModel.recoverConversation("conversation-1")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        assertEquals("conversation-1", state.conversationId)
        assertFalse(state.isConversationCatalogExpanded)
    }

    @Test
    fun `manual recovery failure shows notice`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            conversations = listOf(
                HubConversationSummary(
                    conversationId = "conversation-2",
                    title = "Broken",
                    updatedAt = "2026-03-11T00:00:00Z",
                    generationState = "idle",
                )
            ),
            failSnapshot = true,
        )
        val recoveryStore = FakeConversationRecoveryStore(null)

        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.recoverConversation("conversation-2")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.detailState.value.recoveryNotice?.contains("恢复会话失败") == true)
    }

    @Test
    fun `startup recovery prefers most recently updated recoverable conversation`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            conversations = listOf(
                HubConversationSummary(
                    conversationId = "conversation-old",
                    title = "Old",
                    updatedAt = "2026-03-01T00:00:00Z",
                    generationState = "idle",
                ),
                HubConversationSummary(
                    conversationId = "conversation-new",
                    title = "New",
                    updatedAt = "2026-03-11T12:00:00Z",
                    generationState = "idle",
                )
            ),
            snapshotEnvelope = snapshotEnvelope(
                conversationId = "conversation-new",
                nodeId = "node-1",
                role = "assistant",
                text = "latest recovery",
            )
        )
        val recoveryStore = FakeConversationRecoveryStore(null)

        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        assertEquals("conversation-new", state.conversationId)
        assertEquals("conversation-new", recoveryStore.savedConversationId)
        assertEquals("latest recovery", state.messages.first().content)
    }

    @Test
    fun `startup recovery skips non recoverable conversation even if it is newer`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            conversations = listOf(
                HubConversationSummary(
                    conversationId = "conversation-unrecoverable",
                    title = "Broken recent",
                    updatedAt = "2026-03-11T12:00:00Z",
                    generationState = "idle",
                    isRecoverable = false,
                ),
                HubConversationSummary(
                    conversationId = "conversation-recoverable",
                    title = "Recover me",
                    updatedAt = "2026-03-10T12:00:00Z",
                    generationState = "idle",
                    isRecoverable = true,
                )
            ),
            snapshotEnvelope = snapshotEnvelope(
                conversationId = "conversation-recoverable",
                nodeId = "node-1",
                role = "assistant",
                text = "recoverable wins",
            )
        )
        val recoveryStore = FakeConversationRecoveryStore(null)

        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        assertEquals("conversation-recoverable", state.conversationId)
        assertEquals("conversation-recoverable", recoveryStore.savedConversationId)
        assertEquals("recoverable wins", state.messages.first().content)
    }

    @Test
    fun `recovery restores scene anchor metadata`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            conversations = listOf(
                HubConversationSummary(
                    conversationId = "conversation-1",
                    title = "Recovered",
                    updatedAt = "2026-03-11T00:00:00Z",
                    generationState = "idle",
                )
            ),
            snapshotEnvelope = snapshotEnvelope(
                conversationId = "conversation-1",
                nodeId = "node-1",
                role = "assistant",
                text = "hello recovery",
            )
        )
        val recoveryStore = FakeConversationRecoveryStore(
            currentConversationId = null,
            sceneAnchor = RecoverySceneAnchor(
                conversationId = "conversation-1",
                lastMessageId = "node-1:variant-1",
                stickToBottom = false,
            )
        )

        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.recoverConversation("conversation-1")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        assertEquals("node-1:variant-1", state.recoveryFocusMessageId)
        assertEquals(false, state.stickToBottom)
    }

    @Test
    fun `persist scene anchor updates in memory stickiness and saves latest viewport anchor`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            conversations = listOf(
                HubConversationSummary(
                    conversationId = "conversation-1",
                    title = "Recovered",
                    updatedAt = "2026-03-11T00:00:00Z",
                    generationState = "idle",
                )
            ),
            snapshotEnvelope = snapshotEnvelope(
                conversationId = "conversation-1",
                nodeId = "node-1",
                role = "assistant",
                text = "hello recovery",
            )
        )
        val recoveryStore = FakeConversationRecoveryStore(
            currentConversationId = "conversation-1",
            sceneAnchor = RecoverySceneAnchor(
                conversationId = "conversation-1",
                lastMessageId = "node-1:variant-1",
                stickToBottom = false,
            )
        )

        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.detailState.value.stickToBottom)

        viewModel.persistSceneAnchor(
            lastVisibleMessageId = "node-1:variant-1",
            stickToBottom = true,
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.detailState.value.stickToBottom)
        assertEquals(
            RecoverySceneAnchor(
                conversationId = "conversation-1",
                lastMessageId = "node-1:variant-1",
                stickToBottom = true,
            ),
            recoveryStore.savedSceneAnchor,
        )
    }

    @Test
    fun `stream snapshot rich parts are not duplicated by matching generation delta`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_snapshot",
                          "payload":{
                            "event":"conversation_snapshot",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-stream",
                                "nodes":[
                                {
                                  "node_id":"node-stream",
                                  "role":"assistant",
                                  "selected_variant":{
                                    "variant_id":"variant-stream",
                                    "parts":[
                                        {"payload":{"type":"reasoning","text":"thinking"}},
                                        {"payload":{"type":"markdown_block","markdown":"hello **markdown**"}}
                                    ]
                                  }
                                }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_started",
                          "payload":{
                            "event":"generation_started",
                            "data":{"node_id":"node-stream","variant_id":"variant-stream"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_part_delta",
                          "payload":{
                            "event":"generation_part_delta",
                            "data":{
                              "node_id":"node-stream",
                              "variant_id":"variant-stream",
                              "appended_parts":[
                                {"payload":{"type":"markdown_block","markdown":"hello **markdown**"}}
                              ]
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.detailState.value.messages
        val assistant = messages.last()
        assertEquals("conversation-stream", viewModel.detailState.value.conversationId)
        assertEquals("hello **markdown**", assistant.content)
        assertEquals("thinking", assistant.reasoning)
        assertEquals("node-stream", assistant.nodeId)
        assertEquals("variant-stream", assistant.variantId)
        assertEquals(
            listOf(
                UiMessagePart(type = "reasoning", text = "thinking"),
                UiMessagePart(type = "markdown_block", text = "hello **markdown**"),
            ),
            assistant.parts
        )
        assertEquals(listOf("reasoning", "markdown_block"), assistant.partTypes)
    }

    @Test
    fun `tool lifecycle events merge into one typed tool part during streaming`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_started",
                          "payload":{
                            "event":"generation_started",
                            "data":{"node_id":"node-tool","variant_id":"variant-tool"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"tool_call_started",
                          "payload":{
                            "event":"tool_call_started",
                            "data":{
                              "node_id":"node-tool",
                              "variant_id":"variant-tool",
                              "tool_call_id":"tool-call-1",
                              "tool_name":"search",
                              "arguments_json":"{\"query\":\"rust\"}"
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"tool_call_completed",
                          "payload":{
                            "event":"tool_call_completed",
                            "data":{
                              "node_id":"node-tool",
                              "variant_id":"variant-tool",
                              "tool_call_id":"tool-call-1",
                              "tool_name":"search"
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val toolMessage = viewModel.detailState.value.messages.last()
        assertEquals("node-tool:variant-tool", toolMessage.id)
        assertEquals(
            listOf(
                UiMessagePart(
                    type = "tool",
                    text = "{\"query\":\"rust\"}",
                    title = "search",
                    state = "completed",
                    partId = "tool-call:tool-call-1",
                    toolCallId = "tool-call-1",
                )
            ),
            toolMessage.parts
        )
        assertEquals("search · completed\n{\"query\":\"rust\"}", toolMessage.content)
    }

    @Test
    fun `stream snapshot reconciles optimistic user message with rust identity`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_snapshot",
                          "payload":{
                            "event":"conversation_snapshot",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-assistant",
                                "nodes":[
                                {
                                  "node_id":"node-user",
                                  "role":"user",
                                  "selected_variant":{
                                    "variant_id":"variant-user",
                                    "parts":[
                                        {"payload":{"type":"text","text":"stream me"}}
                                    ]
                                  }
                                },
                                {
                                  "node_id":"node-assistant",
                                  "role":"assistant",
                                  "selected_variant":{
                                    "variant_id":"variant-assistant",
                                    "parts":[
                                        {"payload":{"type":"reasoning","text":"thinking"}},
                                        {"payload":{"type":"markdown_block","markdown":"hello **markdown**"}}
                                    ]
                                  }
                                }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        val userMessages = state.messages.filter { it.sender == MessageSender.USER }
        val assistantMessages = state.messages.filter { it.id == "node-assistant:variant-assistant" }

        assertEquals("conversation-stream", state.conversationId)
        assertEquals(1, userMessages.size)
        assertEquals(1, assistantMessages.size)
        assertEquals("node-user:variant-user", userMessages.single().id)
        assertEquals("node-user", userMessages.single().nodeId)
        assertEquals("variant-user", userMessages.single().variantId)
        assertEquals(listOf(UiMessagePart(type = "text", text = "stream me")), userMessages.single().parts)
        assertEquals(listOf("text"), userMessages.single().partTypes)
        assertTrue(
            state.messages.none { it.sender == MessageSender.USER && it.nodeId == null && it.content == "stream me" }
        )
    }

    @Test
    fun `stream completion without explicit completed event keeps typed generation in flight`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_snapshot",
                          "payload":{
                            "event":"conversation_snapshot",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-user",
                                "nodes":[
                                {
                                  "node_id":"node-user",
                                  "role":"user",
                                  "selected_variant":{
                                    "variant_id":"variant-user",
                                    "parts":[
                                        {"payload":{"type":"text","text":"stream me"}}
                                    ]
                                  }
                                }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        assertEquals(ChatGenerationPhase.STREAMING, state.generation.phase)
        assertTrue(state.isTyping)
    }

    @Test
    fun `typed generation completed event exits typing state`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_started",
                          "payload":{
                            "event":"generation_started",
                            "data":{"node_id":"node-user","variant_id":"variant-user"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_completed",
                          "payload":{
                            "event":"generation_completed",
                            "data":{"node_id":"node-user","variant_id":"variant-user"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        assertEquals(ChatGenerationPhase.COMPLETED, state.generation.phase)
        assertFalse(state.isTyping)
    }

    @Test
    fun `non empty snapshot only reconciles optimistic placeholder against matching latest user turn`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            snapshotEnvelope = snapshotEnvelope(
                conversationId = "conversation-stream",
                nodeId = "node-user-old",
                role = "user",
                text = "older prompt",
            ),
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_snapshot",
                          "payload":{
                            "event":"conversation_snapshot",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-user-new",
                                "nodes":[
                                {
                                  "node_id":"node-user-old",
                                  "role":"user",
                                  "selected_variant":{
                                    "variant_id":"variant-user-old",
                                    "parts":[
                                        {"payload":{"type":"text","text":"older prompt"}}
                                    ]
                                  }
                                },
                                {
                                  "node_id":"node-assistant-old",
                                  "role":"assistant",
                                  "selected_variant":{
                                    "variant_id":"variant-assistant-old",
                                    "parts":[
                                        {"payload":{"type":"markdown_block","markdown":"older reply"}}
                                    ]
                                  }
                                },
                                {
                                  "node_id":"node-user-new",
                                  "role":"user",
                                  "selected_variant":{
                                    "variant_id":"variant-user-new",
                                    "parts":[
                                        {"payload":{"type":"text","text":"latest prompt"}}
                                    ]
                                  }
                                }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.recoverConversation("conversation-stream")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onInputChanged("latest prompt")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val userMessages = viewModel.detailState.value.messages.filter { it.sender == MessageSender.USER }
        assertEquals(listOf("older prompt", "latest prompt"), userMessages.map { it.content })
        assertEquals(
            listOf("node-user-old:variant-user-old", "node-user-new:variant-user-new"),
            userMessages.map { it.id }
        )
        assertTrue(userMessages.none { it.nodeId == null })
    }

    @Test
    fun `send message ignores second submit while first request is active`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(streamEvents = emptyFlow())
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("same text")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onInputChanged("same text")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val userMessages = viewModel.detailState.value.messages.filter { it.sender == MessageSender.USER }

        assertEquals(1, repository.observeStreamRequests.size)
        assertEquals(1, userMessages.size)
        assertEquals("same text", userMessages.single().content)
        assertEquals(ChatGenerationPhase.REQUESTING, viewModel.detailState.value.generation.phase)
    }

    @Test
    fun `retry after pre snapshot failure does not leave stale optimistic user placeholder`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEventQueue = listOf(
                flow {
                    emit(HubStreamEvent.Error(HubStreamFailureException("fail before snapshot")))
                },
                flow {
                    emit(HubStreamEvent.Opened)
                    emit(
                        HubStreamEvent.Message(
                            event = "chat_event",
                            data = """
                            {
                              "conversation_id":"conversation-stream",
                              "event_name":"conversation_snapshot",
                              "payload":{
                                "event":"conversation_snapshot",
                                "data":{
                                  "branch":{
                                    "cursor_node_id":"node-assistant",
                                    "nodes":[
                                    {
                                      "node_id":"node-user",
                                      "role":"user",
                                      "selected_variant":{
                                        "variant_id":"variant-user",
                                        "parts":[
                                            {"payload":{"type":"text","text":"same text"}}
                                        ]
                                      }
                                    },
                                    {
                                      "node_id":"node-assistant",
                                      "role":"assistant",
                                      "selected_variant":{
                                        "variant_id":"variant-assistant",
                                        "parts":[
                                            {"payload":{"type":"text","text":"ok"}}
                                        ]
                                      }
                                    }
                                    ]
                                  }
                                }
                              }
                            }
                            """.trimIndent()
                        )
                    )
                    emit(HubStreamEvent.Completed)
                },
            ),
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("same text")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.detailState.value.messages.none {
                it.sender == MessageSender.USER && it.nodeId == null
            }
        )

        viewModel.onInputChanged("same text")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val userMessages = viewModel.detailState.value.messages.filter { it.sender == MessageSender.USER }
        assertEquals(2, repository.observeStreamRequests.size)
        assertEquals(1, userMessages.size)
        assertEquals("node-user:variant-user", userMessages.single().id)
        assertTrue(
            viewModel.detailState.value.messages.none {
                it.sender == MessageSender.USER && it.nodeId == null
            }
        )
    }

    @Test
    fun `stream relay error exits streaming and appends one visible network error`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(HubStreamEvent.Error(HubRelayErrorException("upstream exploded")))
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        val errorMessages = state.messages.filter { it.content.contains("upstream exploded") }
        assertEquals(ChatGenerationPhase.FAILED, state.generation.phase)
        assertEquals(1, errorMessages.size)
        assertTrue(state.messages.last().content.contains("upstream exploded"))
    }

    @Test
    fun `stream network failure exits streaming and keeps no retry guidance visible`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Error(
                        HubStreamFailureException(
                            "Hub SSE network failure — no auto retry; resend or reopen the conversation manually"
                        )
                    )
                )
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        val errorMessages = state.messages.filter { it.content.contains("no auto retry") }
        assertEquals(ChatGenerationPhase.FAILED, state.generation.phase)
        assertEquals(1, errorMessages.size)
        assertTrue(state.messages.last().content.contains("no auto retry"))
    }

    @Test
    fun `stream node upsert replaces same assistant message with final selected variant truth`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_snapshot",
                          "payload":{
                            "event":"conversation_snapshot",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-stream",
                                "nodes":[
                                {
                                  "node_id":"node-stream",
                                  "role":"assistant",
                                  "selected_variant":{
                                    "variant_id":"variant-stream",
                                    "parts":[
                                        {"payload":{"type":"reasoning","text":"thinking"}},
                                        {"payload":{"type":"markdown_block","markdown":"hello **markdown**"}}
                                    ]
                                  }
                                }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_started",
                          "payload":{
                            "event":"generation_started",
                            "data":{"node_id":"node-stream","variant_id":"variant-stream"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_part_delta",
                          "payload":{
                            "event":"generation_part_delta",
                            "data":{
                              "node_id":"node-stream",
                              "variant_id":"variant-stream",
                              "appended_parts":[
                                {"payload":{"type":"markdown_block","markdown":"hello **markdown**"}}
                              ]
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_node_upsert",
                          "payload":{
                            "event":"conversation_node_upsert",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-stream",
                                "node":{
                                  "node_id":"node-stream",
                                  "role":"assistant",
                                  "selected_variant":{
                                    "variant_id":"variant-stream",
                                    "parts":[
                                        {"payload":{"type":"reasoning","text":"thinking"}},
                                        {"payload":{"type":"markdown_block","markdown":"hello **markdown**"}}
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.detailState.value.messages
        val streamedMessages = messages.filter { it.id == "node-stream:variant-stream" }
        assertEquals("conversation-stream", viewModel.detailState.value.conversationId)
        assertEquals(1, streamedMessages.size)
        assertEquals("node-stream:variant-stream", streamedMessages.single().id)
        assertEquals("hello **markdown**", streamedMessages.single().content)
        assertEquals("thinking", streamedMessages.single().reasoning)
        assertEquals("node-stream", streamedMessages.single().nodeId)
        assertEquals("variant-stream", streamedMessages.single().variantId)
        assertEquals(
            listOf(
                UiMessagePart(type = "reasoning", text = "thinking"),
                UiMessagePart(type = "markdown_block", text = "hello **markdown**"),
            ),
            streamedMessages.single().parts
        )
        assertEquals(listOf("reasoning", "markdown_block"), streamedMessages.single().partTypes)
    }

    @Test
    fun `stream node upsert replaces selected variant on existing node without duplicate bubble`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_snapshot",
                          "payload":{
                            "event":"conversation_snapshot",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-stream",
                                "nodes":[
                                {
                                  "node_id":"node-stream",
                                  "role":"assistant",
                                  "selected_variant":{
                                    "variant_id":"variant-old",
                                    "parts":[
                                        {"payload":{"type":"text","text":"old"}}
                                    ]
                                  }
                                }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_node_upsert",
                          "payload":{
                            "event":"conversation_node_upsert",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-stream",
                                "node":{
                                  "node_id":"node-stream",
                                  "role":"assistant",
                                  "selected_variant":{
                                    "variant_id":"variant-new",
                                    "parts":[
                                        {"payload":{"type":"text","text":"new"}}
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val streamedMessages = viewModel.detailState.value.messages.filter { it.nodeId == "node-stream" }
        assertEquals(1, streamedMessages.size)
        assertEquals("node-stream:variant-new", streamedMessages.single().id)
        assertEquals("new", streamedMessages.single().content)
        assertEquals("variant-new", streamedMessages.single().variantId)
    }

    @Test
    fun `regenerate lifecycle switches to the new assistant variant and completes cleanly`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            streamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_snapshot",
                          "payload":{
                            "event":"conversation_snapshot",
                            "data":{
                              "branch":{
                                "cursor_node_id":"node-stream",
                                "nodes":[
                                {
                                  "node_id":"node-stream",
                                  "role":"assistant",
                                  "selected_variant":{
                                    "variant_id":"variant-old",
                                    "parts":[
                                        {"payload":{"type":"text","text":"old"}}
                                    ]
                                  }
                                }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_node_upsert",
                          "payload":{
                            "event":"conversation_node_upsert",
                            "data":{
                              "node":{
                                "node_id":"node-stream",
                                "role":"assistant",
                                "selected_variant":{
                                  "variant_id":"variant-new",
                                  "parts":[
                                      {"payload":{"type":"reasoning","text":"thinking again"}},
                                      {"payload":{"type":"text","text":"new"}}
                                  ]
                                }
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_started",
                          "payload":{
                            "event":"generation_started",
                            "data":{"node_id":"node-stream","variant_id":"variant-new"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_part_delta",
                          "payload":{
                            "event":"generation_part_delta",
                            "data":{
                              "node_id":"node-stream",
                              "variant_id":"variant-new",
                              "appended_parts":[
                                {"payload":{"type":"reasoning","text":"thinking again"}},
                                {"payload":{"type":"text","text":"new"}}
                              ]
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_completed",
                          "payload":{
                            "event":"generation_completed",
                            "data":{"node_id":"node-stream","variant_id":"variant-new"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)

        viewModel.onInputChanged("stream me")
        viewModel.sendMessage()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.detailState.value
        val streamedMessages = state.messages.filter { it.nodeId == "node-stream" }
        assertEquals(1, streamedMessages.size)
        assertEquals("node-stream:variant-new", streamedMessages.single().id)
        assertEquals("variant-new", streamedMessages.single().variantId)
        assertEquals("new", streamedMessages.single().content)
        assertEquals("thinking again", streamedMessages.single().reasoning)
        assertEquals(ChatGenerationPhase.COMPLETED, state.generation.phase)
        assertNull(state.generation.activeMessageKey)
    }

    @Test
    fun `regenerate assistant command posts current conversation node and replaces selected variant`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            snapshotEnvelope = snapshotEnvelope(
                conversationId = "conversation-stream",
                nodeId = "node-stream",
                role = "assistant",
                text = "old",
            ),
            regenerateStreamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_node_upsert",
                          "payload":{
                            "event":"conversation_node_upsert",
                            "data":{
                              "node":{
                                "node_id":"node-stream",
                                "role":"assistant",
                                "selected_variant":{
                                  "variant_id":"variant-2",
                                  "parts":[
                                      {"payload":{"type":"text","text":"new branch"}}
                                  ]
                                }
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_started",
                          "payload":{
                            "event":"generation_started",
                            "data":{"node_id":"node-stream","variant_id":"variant-2"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"generation_completed",
                          "payload":{
                            "event":"generation_completed",
                            "data":{"node_id":"node-stream","variant_id":"variant-2"}
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.recoverConversation("conversation-stream")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.regenerateAssistant("node-stream")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(HubRegenerateRequest("conversation-stream", "node-stream")),
            repository.regenerateRequests,
        )
        val message = viewModel.detailState.value.messages.single()
        assertEquals("node-stream:variant-2", message.id)
        assertEquals("variant-2", message.variantId)
        assertEquals("new branch", message.content)
        assertEquals(ChatGenerationPhase.COMPLETED, viewModel.detailState.value.generation.phase)
    }

    @Test
    fun `select assistant variant command persists selection and updates the visible branch`() = runTest(dispatcher) {
        val repository = FakeHubChatRepository(
            snapshotEnvelope = RustChatEventEnvelope(
                conversationId = "conversation-stream",
                kind = RustChatEventKind.CONVERSATION_SNAPSHOT,
                event = "conversation_snapshot",
                data = JSONObject(
                    """
                    {
                      "branch":{
                        "cursor_node_id":"node-stream",
                        "nodes":[
                          {
                            "node_id":"node-stream",
                            "role":"assistant",
                            "selected_variant":{
                              "variant_id":"variant-2",
                              "parts":[
                                {"payload":{"type":"text","text":"new branch"}}
                              ]
                            }
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                ),
            ),
            selectVariantStreamEvents = flow {
                emit(HubStreamEvent.Opened)
                emit(
                    HubStreamEvent.Message(
                        event = "chat_event",
                        data = """
                        {
                          "conversation_id":"conversation-stream",
                          "event_name":"conversation_node_upsert",
                          "payload":{
                            "event":"conversation_node_upsert",
                            "data":{
                              "node":{
                                "node_id":"node-stream",
                                "role":"assistant",
                                "selected_variant":{
                                  "variant_id":"variant-1",
                                  "parts":[
                                      {"payload":{"type":"text","text":"old branch"}}
                                  ]
                                }
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                )
                emit(HubStreamEvent.Completed)
            }
        )
        val recoveryStore = FakeConversationRecoveryStore(null)
        val viewModel = ChatViewModel(repository, recoveryStore)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.recoverConversation("conversation-stream")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectAssistantVariant("node-stream", "variant-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(HubSelectVariantRequest("conversation-stream", "node-stream", "variant-1")),
            repository.selectVariantRequests,
        )
        val message = viewModel.detailState.value.messages.single()
        assertEquals("node-stream:variant-1", message.id)
        assertEquals("variant-1", message.variantId)
        assertEquals("old branch", message.content)
    }

    private fun snapshotEnvelope(
        conversationId: String,
        nodeId: String,
        role: String,
        text: String,
    ): RustChatEventEnvelope {
        val data = JSONObject(
            """
            {
              "branch": {
                "cursor_node_id": "$nodeId",
                "nodes": [
                  {
                    "node_id": "$nodeId",
                    "role": "$role",
                    "selected_variant": {
                      "variant_id": "variant-1",
                      "parts": [
                        { "payload": { "type": "text", "text": "$text" } }
                      ]
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )
        return RustChatEventEnvelope(
            conversationId = conversationId,
            kind = RustChatEventKind.CONVERSATION_SNAPSHOT,
            event = "conversation_snapshot",
            data = data,
        )
    }
}

private class FakeHubChatRepository(
    private val conversations: List<HubConversationSummary> = emptyList(),
    private val snapshotEnvelope: RustChatEventEnvelope? = null,
    private val failSnapshot: Boolean = false,
    private val streamEvents: Flow<HubStreamEvent> = emptyFlow(),
    private val regenerateStreamEvents: Flow<HubStreamEvent> = emptyFlow(),
    private val selectVariantStreamEvents: Flow<HubStreamEvent> = emptyFlow(),
    streamEventQueue: List<Flow<HubStreamEvent>> = emptyList(),
) : HubChatRepository {
    val observeStreamRequests = mutableListOf<HubSendMessageRequest>()
    val regenerateRequests = mutableListOf<HubRegenerateRequest>()
    val selectVariantRequests = mutableListOf<HubSelectVariantRequest>()
    private val streamEventQueue = ArrayDeque(streamEventQueue)

    override suspend fun sendMessage(request: HubSendMessageRequest): HubSendMessageResponse {
        return HubSendMessageResponse("", "", "")
    }

    override fun observeStream(request: HubSendMessageRequest): Flow<HubStreamEvent> {
        observeStreamRequests += request
        return streamEventQueue.removeFirstOrNull() ?: streamEvents
    }

    override fun regenerateAssistant(request: HubRegenerateRequest): Flow<HubStreamEvent> {
        regenerateRequests += request
        return regenerateStreamEvents
    }

    override fun selectVariant(request: HubSelectVariantRequest): Flow<HubStreamEvent> {
        selectVariantRequests += request
        return selectVariantStreamEvents
    }

    override suspend fun listConversations(): List<HubConversationSummary> = conversations

    override suspend fun fetchConversationSnapshot(conversationId: String): RustChatEventEnvelope? {
        if (failSnapshot) error("snapshot failure")
        return snapshotEnvelope
    }
}

private class FakeConversationRecoveryStore(
    private var currentConversationId: String?,
    private var sceneAnchor: RecoverySceneAnchor? = null,
) : RecoveryStore {
    var savedConversationId: String? = currentConversationId
        private set
    val savedSceneAnchor: RecoverySceneAnchor?
        get() = sceneAnchor

    override suspend fun lastConversationId(): String? = currentConversationId

    override suspend fun saveLastConversationId(conversationId: String) {
        currentConversationId = conversationId
        savedConversationId = conversationId
    }

    override suspend fun clearLastConversationId() {
        currentConversationId = null
        savedConversationId = null
    }

    override suspend fun loadSceneAnchor(conversationId: String): RecoverySceneAnchor? {
        return sceneAnchor?.takeIf { it.conversationId == conversationId }
    }

    override suspend fun saveSceneAnchor(anchor: RecoverySceneAnchor) {
        sceneAnchor = anchor
    }

    override suspend fun clearSceneAnchor(conversationId: String) {
        if (sceneAnchor?.conversationId == conversationId) {
            sceneAnchor = null
        }
    }
}
