package com.vcp.mobile.data.network

import com.vcp.mobile.testing.RichContentSmokeFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RustChatEventParserTest {

    @Test
    fun `extractSnapshotMessage returns stable identity and delta`() {
        val json = JSONObject(
            """
            {
              "branch": {
                "cursor_node_id": "node-1",
                "nodes": [
                {
                  "node_id": "node-1",
                  "role": "assistant",
                  "selected_variant": {
                    "variant_id": "variant-1",
                    "parts": [
                        {
                          "payload": {
                            "type": "reasoning",
                            "text": "thinking"
                          }
                        },
                        {
                          "payload": {
                            "type": "text",
                            "text": "hello"
                          }
                        }
                    ]
                  }
                }
                ]
              }
            }
            """.trimIndent()
        )

        val snapshot = RustChatEventParser.extractSnapshotMessage(json)

        assertNotNull(snapshot)
        assertEquals("node-1:variant-1", snapshot?.identity?.messageKey)
        assertEquals("assistant", snapshot?.role)
        assertEquals("node-1", snapshot?.cursorNodeId)
        assertEquals("hello", snapshot?.delta?.appendedText)
        assertEquals("thinking", snapshot?.delta?.appendedReasoning)
        assertEquals(listOf("reasoning", "text"), snapshot?.delta?.partTypes)
        assertEquals(
            listOf(
                RustMessagePart(type = "reasoning", text = "thinking"),
                RustMessagePart(type = "text", text = "hello"),
            ),
            snapshot?.delta?.parts
        )
    }

    @Test
    fun `extractSnapshotMessages expands multiple nodes from the selected branch shape`() {
        val json = JSONObject(
            """
            {
              "branch": {
                "cursor_node_id": "node-2",
                "nodes": [
                {
                  "node_id": "node-1",
                  "role": "assistant",
                  "selected_variant": {
                    "variant_id": "variant-1b",
                    "parts": [
                        { "payload": { "type": "reasoning", "text": "r1" } },
                        { "payload": { "type": "text", "text": "new" } }
                    ]
                  }
                },
                {
                  "node_id": "node-2",
                  "role": "user",
                  "selected_variant": {
                    "variant_id": "variant-2a",
                    "parts": [
                        { "payload": { "type": "text", "text": "tail" } }
                    ]
                  }
                }
                ]
              }
            }
            """.trimIndent()
        )

        val snapshots = RustChatEventParser.extractSnapshotMessages(json)

        assertEquals(2, snapshots.size)
        assertEquals("node-1:variant-1b", snapshots[0].identity.messageKey)
        assertEquals("assistant", snapshots[0].role)
        assertEquals("new", snapshots[0].delta.appendedText)
        assertEquals("r1", snapshots[0].delta.appendedReasoning)
        assertEquals(listOf("reasoning", "text"), snapshots[0].delta.partTypes)
        assertEquals(
            listOf(
                RustMessagePart(type = "reasoning", text = "r1"),
                RustMessagePart(type = "text", text = "new"),
            ),
            snapshots[0].delta.parts
        )
        assertEquals("node-2:variant-2a", snapshots[1].identity.messageKey)
        assertEquals("user", snapshots[1].role)
        assertEquals("tail", snapshots[1].delta.appendedText)
        assertEquals(listOf("text"), snapshots[1].delta.partTypes)
        assertEquals(
            listOf(RustMessagePart(type = "text", text = "tail")),
            snapshots[1].delta.parts
        )
    }

    @Test
    fun `extractGenerationIdentity returns null when ids missing`() {
        val json = JSONObject("""{ "node_id": "", "variant_id": "variant-1" }""")

        val identity = RustChatEventParser.extractGenerationIdentity(json)

        assertNull(identity)
    }

    @Test
    fun `extractNodeUpsertMessage keeps explicit branch cursor truth`() {
        val json = JSONObject(
            """
            {
              "branch": {
                "cursor_node_id": "node-leaf",
                "node": {
                  "node_id": "node-leaf",
                  "role": "assistant",
                  "selected_variant": {
                    "variant_id": "variant-leaf",
                    "parts": [
                      { "payload": { "type": "text", "text": "hello" } }
                    ]
                  }
                }
              }
            }
            """.trimIndent()
        )

        val snapshot = RustChatEventParser.extractNodeUpsertMessage(json)

        assertNotNull(snapshot)
        assertEquals("node-leaf:variant-leaf", snapshot?.identity?.messageKey)
        assertEquals("node-leaf", snapshot?.cursorNodeId)
        assertEquals("hello", snapshot?.delta?.appendedText)
    }

    @Test
    fun `extractSnapshotMessage supports markdown and code block payloads`() {
        val json = JSONObject(
            """
            {
              "branch": {
                "cursor_node_id": "node-rich",
                "nodes": [
                {
                  "node_id": "node-rich",
                  "role": "assistant",
                  "selected_variant": {
                    "variant_id": "variant-rich",
                    "parts": [
                        {
                          "payload": {
                            "type": "reasoning",
                            "text": "thinking"
                          }
                        },
                        {
                          "payload": {
                            "type": "markdown_block",
                            "markdown": "hello **markdown**"
                          }
                        },
                        {
                          "payload": {
                            "type": "code_block",
                            "language": "kotlin",
                            "code": "println(1)"
                          }
                        }
                    ]
                  }
                }
                ]
              }
            }
            """.trimIndent()
        )

        val snapshot = RustChatEventParser.extractSnapshotMessage(json)

        assertNotNull(snapshot)
        assertEquals("thinking", snapshot?.delta?.appendedReasoning)
        assertEquals(
            "hello **markdown**```kotlin\nprintln(1)\n```",
            snapshot?.delta?.appendedText
        )
        assertEquals(
            listOf("reasoning", "markdown_block", "code_block"),
            snapshot?.delta?.partTypes
        )
        assertEquals(
            listOf(
                RustMessagePart(type = "reasoning", text = "thinking"),
                RustMessagePart(type = "markdown_block", text = "hello **markdown**"),
                RustMessagePart(type = "code_block", text = "println(1)", language = "kotlin"),
            ),
            snapshot?.delta?.parts
        )
    }

    @Test
    fun `extractPartDelta supports markdown and code block payloads`() {
        val json = JSONObject(
            """
            {
              "appended_parts": [
                {
                  "payload": {
                    "type": "markdown_block",
                    "markdown": "hello **markdown**"
                  }
                },
                {
                  "payload": {
                    "type": "code_block",
                    "language": "rust",
                    "code": "println!(\"ok\");"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val delta = RustChatEventParser.extractPartDelta(json)

        assertEquals(
            "hello **markdown**```rust\nprintln!(\"ok\");\n```",
            delta.appendedText
        )
        assertEquals("", delta.appendedReasoning)
        assertEquals(listOf("markdown_block", "code_block"), delta.partTypes)
        assertEquals(
            listOf(
                RustMessagePart(type = "markdown_block", text = "hello **markdown**"),
                RustMessagePart(type = "code_block", text = "println!(\"ok\");", language = "rust"),
            ),
            delta.parts
        )
    }

    @Test
    fun `extractPartDelta preserves core typed parts beyond markdown`() {
        val json = JSONObject(
            """
            {
              "appended_parts": [
                {
                  "payload": {
                    "type": "image",
                    "url": "https://cdn.example.com/cat.png",
                    "alt": "cat preview",
                    "mime": "image/png"
                  }
                },
                {
                  "payload": {
                    "type": "document",
                    "file_name": "spec.pdf",
                    "url": "file:///spec.pdf",
                    "mime": "application/pdf"
                  }
                },
                {
                  "payload": {
                    "type": "tool",
                    "tool_name": "search_web",
                    "state": "completed",
                    "input_json": "{\"query\":\"rust\"}",
                    "output_json": "{\"items\":1}"
                  }
                },
                {
                  "payload": {
                    "type": "error",
                    "message": "upstream exploded"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val delta = RustChatEventParser.extractPartDelta(json)

        assertEquals(
            listOf("image", "document", "tool", "error"),
            delta.partTypes
        )
        assertEquals(
            listOf(
                RustMessagePart(
                    type = "image",
                    text = "",
                    title = "cat preview",
                    url = "https://cdn.example.com/cat.png",
                    mime = "image/png",
                ),
                RustMessagePart(
                    type = "document",
                    text = "",
                    title = "spec.pdf",
                    url = "file:///spec.pdf",
                    mime = "application/pdf",
                ),
                RustMessagePart(
                    type = "tool",
                    text = "{\"items\":1}",
                    title = "search_web",
                    state = "completed",
                ),
                RustMessagePart(type = "error", text = "upstream exploded"),
            ),
            delta.parts
        )
        assertEquals(
            "cat preview\nhttps://cdn.example.com/cat.png\nimage/png" +
                "spec.pdf\nfile:///spec.pdf\napplication/pdf" +
                "{\"items\":1}" +
                "upstream exploded",
            delta.appendedText
        )
    }

    @Test
    fun `extractNodeUpsertMessage reads selected variant rich parts`() {
        val json = JSONObject(
            """
            {
              "node": {
                "node_id": "node-upsert",
                "role": "assistant",
                "selected_variant": {
                  "variant_id": "variant-upsert",
                  "parts": [
                      {
                        "payload": {
                          "type": "reasoning",
                          "text": "thinking"
                        }
                      },
                      {
                        "payload": {
                          "type": "markdown_block",
                          "markdown": "hello **upsert**"
                        }
                      }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val snapshot = RustChatEventParser.extractNodeUpsertMessage(json)

        assertNotNull(snapshot)
        assertEquals("node-upsert:variant-upsert", snapshot?.identity?.messageKey)
        assertEquals("hello **upsert**", snapshot?.delta?.appendedText)
        assertEquals("thinking", snapshot?.delta?.appendedReasoning)
        assertEquals(listOf("reasoning", "markdown_block"), snapshot?.delta?.partTypes)
        assertEquals(
            listOf(
                RustMessagePart(type = "reasoning", text = "thinking"),
                RustMessagePart(type = "markdown_block", text = "hello **upsert**"),
            ),
            snapshot?.delta?.parts
        )
    }

    @Test
    fun `extractPartDelta smoke fixture keeps markdown code and supported document ingestion observable`() {
        val delta = RustChatEventParser.extractPartDelta(
            JSONObject(RichContentSmokeFixtures.documentIngestionJson)
        )

        assertEquals(
            listOf(
                "markdown_block",
                "code_block",
                "document",
                "document",
                "document",
                "document",
                "document",
            ),
            delta.partTypes,
        )
        assertEquals(
            RichContentSmokeFixtures.expectedCompatibilityContent(),
            delta.appendedText,
        )
        assertEquals(
            listOf(
                "text/plain",
                "text/markdown",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ),
            delta.parts.filter { it.type == "document" }.map { it.mime },
        )
        assertEquals(
            listOf("notes.txt", "guide.md", "spec.pdf", "brief.docx", "deck.pptx"),
            delta.parts.filter { it.type == "document" }.map { it.title },
        )
    }

    @Test
    fun `parseEnvelope resolves canonical typed event kind`() {
        val envelope = RustChatEventParser.parseEnvelope(
            """
            {
              "schema": { "family": "chat_event", "major": 1, "minor": 0 },
              "event_id": "event-1",
              "event_name": "tool_call_started",
              "conversation_id": "conversation-1",
              "payload": {
                "event": "tool_call_started",
                "data": {
                  "node_id": "node-1",
                  "variant_id": "variant-1",
                  "tool_call_id": "tool-call-1",
                  "tool_name": "search",
                  "arguments_json": "{\"query\":\"rust\"}"
                }
              }
            }
            """.trimIndent()
        )

        assertNotNull(envelope)
        assertEquals(RustChatEventKind.TOOL_CALL_STARTED, envelope?.kind)
        assertEquals("tool_call_started", envelope?.event)
    }

    @Test
    fun `parseEnvelope rejects envelopes without canonical event_name`() {
        val envelope = RustChatEventParser.parseEnvelope(
            """
            {
              "conversation_id": "conversation-1",
              "payload": {
                "event": "tool_call_started",
                "data": {
                  "node_id": "node-1",
                  "variant_id": "variant-1"
                }
              }
            }
            """.trimIndent()
        )

        assertNull(envelope)
    }

    @Test
    fun `parseEnvelope rejects mismatched payload event tag`() {
        val envelope = RustChatEventParser.parseEnvelope(
            """
            {
              "event_name": "tool_call_started",
              "payload": {
                "event": "generation_started",
                "data": {
                  "node_id": "node-1",
                  "variant_id": "variant-1"
                }
              }
            }
            """.trimIndent()
        )

        assertNull(envelope)
    }

    @Test
    fun `extractEventError reads typed error payload semantics`() {
        val json = JSONObject(
            """
            {
              "error": {
                "kind": "provider",
                "code": "rate_limit",
                "message": "provider throttled the request",
                "retriable": true
              }
            }
            """.trimIndent()
        )

        val error = RustChatEventParser.extractEventError(json)

        assertNotNull(error)
        assertEquals(RustEventErrorKind.PROVIDER, error?.kind)
        assertEquals("rate_limit", error?.code)
        assertEquals("provider throttled the request", error?.message)
        assertEquals(true, error?.retriable)
    }

    @Test
    fun `extractEventError ignores ad hoc top level message fallback`() {
        val json = JSONObject(
            """
            {
              "message": "provider throttled the request"
            }
            """.trimIndent()
        )

        val error = RustChatEventParser.extractEventError(json)

        assertNull(error)
    }

    @Test
    fun `extractToolCallEvent reads explicit tool lifecycle payload`() {
        val json = JSONObject(
            """
            {
              "node_id": "node-1",
              "variant_id": "variant-1",
              "tool_call_id": "tool-call-1",
              "tool_name": "search",
              "arguments_json": "{\"query\":\"rust\"}"
            }
            """.trimIndent()
        )

        val event = RustChatEventParser.extractToolCallEvent(
            kind = RustChatEventKind.TOOL_CALL_STARTED,
            data = json,
        )

        assertNotNull(event)
        assertEquals("node-1:variant-1", event?.identity?.messageKey)
        assertEquals("tool-call-1", event?.toolCallId)
        assertEquals("search", event?.toolName)
        assertEquals(RustToolCallPhase.STARTED, event?.phase)
        assertEquals("{\"query\":\"rust\"}", event?.argumentsJson)
    }

    @Test
    fun `extractPartDelta preserves tool and error part types without flattening into text`() {
        val json = JSONObject(
            """
            {
              "appended_parts": [
                {
                  "payload": {
                    "type": "tool_call",
                    "tool_name": "search",
                    "arguments_json": "{\"query\":\"rust\"}"
                  }
                },
                {
                  "payload": {
                    "type": "tool_result",
                    "tool_name": "search",
                    "result_json": "{\"hits\":1}"
                  }
                },
                {
                  "payload": {
                    "type": "error",
                    "message": "tool failed"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val delta = RustChatEventParser.extractPartDelta(json)

        assertEquals("", delta.appendedText)
        assertEquals("", delta.appendedReasoning)
        assertEquals(listOf("tool_call", "tool_result", "error"), delta.partTypes)
        assertEquals(
            listOf(
                RustMessagePart(
                    type = "tool_call",
                    text = "{\"query\":\"rust\"}",
                    language = "search",
                ),
                RustMessagePart(
                    type = "tool_result",
                    text = "{\"hits\":1}",
                    language = "search",
                ),
                RustMessagePart(type = "error", text = "tool failed"),
            ),
            delta.parts
        )
    }

    @Test
    fun `extractPartDelta keeps stable part identity for streaming merge`() {
        val json = JSONObject(
            """
            {
              "appended_parts": [
                {
                  "id": "part-reasoning-1",
                  "order_index": 0,
                  "payload": {
                    "type": "reasoning",
                    "text": "thinking"
                  }
                },
                {
                  "id": "part-tool-1",
                  "order_index": 1,
                  "payload": {
                    "type": "tool",
                    "tool_call_id": "tool-call-1",
                    "tool_name": "search",
                    "state": "started",
                    "input_json": "{\"query\":\"rust\"}"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val delta = RustChatEventParser.extractPartDelta(json)

        assertEquals(
            listOf(
                RustMessagePart(
                    type = "reasoning",
                    text = "thinking",
                    partId = "part-reasoning-1",
                    orderIndex = 0,
                ),
                RustMessagePart(
                    type = "tool",
                    text = "{\"query\":\"rust\"}",
                    title = "search",
                    state = "started",
                    partId = "part-tool-1",
                    orderIndex = 1,
                    toolCallId = "tool-call-1",
                ),
            ),
            delta.parts
        )
    }
}
