package com.vcp.mobile.data.network

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
              "nodes": [
                {
                  "node": {
                    "id": "node-1",
                    "role": "assistant",
                    "select_index": 0
                  },
                  "variants": [
                    {
                      "variant": {
                        "id": "variant-1"
                      },
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
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val snapshot = RustChatEventParser.extractSnapshotMessage(json)

        assertNotNull(snapshot)
        assertEquals("node-1:variant-1", snapshot?.identity?.messageKey)
        assertEquals("assistant", snapshot?.role)
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
    fun `extractSnapshotMessages expands multiple nodes and respects selected variant`() {
        val json = JSONObject(
            """
            {
              "nodes": [
                {
                  "node": {
                    "id": "node-1",
                    "role": "assistant",
                    "select_index": 1
                  },
                  "variants": [
                    {
                      "variant": { "id": "variant-1a" },
                      "parts": [
                        { "payload": { "type": "text", "text": "old" } }
                      ]
                    },
                    {
                      "variant": { "id": "variant-1b" },
                      "parts": [
                        { "payload": { "type": "reasoning", "text": "r1" } },
                        { "payload": { "type": "text", "text": "new" } }
                      ]
                    }
                  ]
                },
                {
                  "node": {
                    "id": "node-2",
                    "role": "user",
                    "select_index": 0
                  },
                  "variants": [
                    {
                      "variant": { "id": "variant-2a" },
                      "parts": [
                        { "payload": { "type": "text", "text": "tail" } }
                      ]
                    }
                  ]
                }
              ]
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
    fun `extractSnapshotMessage supports markdown and code block payloads`() {
        val json = JSONObject(
            """
            {
              "nodes": [
                {
                  "node": {
                    "id": "node-rich",
                    "role": "assistant",
                    "select_index": 0
                  },
                  "variants": [
                    {
                      "variant": { "id": "variant-rich" },
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
                  ]
                }
              ]
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
    fun `extractNodeUpsertMessage reads selected variant rich parts`() {
        val json = JSONObject(
            """
            {
              "node": {
                "node": {
                  "id": "node-upsert",
                  "role": "assistant",
                  "select_index": 0
                },
                "variants": [
                  {
                    "variant": { "id": "variant-upsert" },
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
                ]
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
}
