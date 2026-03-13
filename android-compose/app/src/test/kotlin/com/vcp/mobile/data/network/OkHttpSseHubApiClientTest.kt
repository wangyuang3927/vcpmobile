package com.vcp.mobile.data.network

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OkHttpSseHubApiClientTest {

    private val client = OkHttpSseHubApiClient(
        okHttpClient = OkHttpClient(),
        baseUrl = "http://localhost:4001"
    )

    @Test
    fun `dispatchSseEvent maps relay done to completed`() {
        val event = client.dispatchSseEvent("relay.done", "")

        assertTrue(event is HubStreamEvent.Completed)
    }

    @Test
    fun `dispatchSseEvent maps relay error to terminal error`() {
        val event = client.dispatchSseEvent("relay.error", "upstream exploded")

        assertTrue(event is HubStreamEvent.Error)
        val throwable = (event as HubStreamEvent.Error).throwable
        assertTrue(throwable is HubRelayErrorException)
        assertEquals("upstream exploded", throwable.message)
    }

    @Test
    fun `dispatchSseEvent maps generic event to message with resolved role`() {
        val event = client.dispatchSseEvent(
            "chat_event",
            """{"role":"assistant","payload":{"event":"generation_started"}}"""
        )

        assertTrue(event is HubStreamEvent.Message)
        event as HubStreamEvent.Message
        assertEquals("chat_event", event.event)
        assertEquals(HUB_ROLE_ASSISTANT, event.role)
    }

    @Test
    fun `dispatchSseEvent tolerates malformed role json`() {
        val event = client.dispatchSseEvent("chat_event", "{not-json")

        assertTrue(event is HubStreamEvent.Message)
        event as HubStreamEvent.Message
        assertEquals("chat_event", event.event)
        assertNull(event.role)
        assertEquals("{not-json", event.data)
    }

    @Test
    fun `classifyStreamFailure labels network failures as no-retry`() {
        val throwable = client.classifyStreamFailure(IOException("socket closed"), null)

        assertTrue(throwable is HubStreamFailureException)
        assertTrue(throwable.message.orEmpty().contains("no auto retry"))
        assertTrue(throwable.cause is IOException)
    }

    @Test
    fun `classifyStreamFailure labels http failures as no-retry`() {
        val response = response(code = 503, message = "Service Unavailable")

        val throwable = client.classifyStreamFailure(null, response)

        assertTrue(throwable is HubStreamFailureException)
        assertTrue(throwable.message.orEmpty().contains("HTTP 503 Service Unavailable"))
        assertTrue(throwable.message.orEmpty().contains("no auto retry"))
    }

    @Test
    fun `parseSnapshotEnvelope returns first sse payload`() {
        val envelope = client.parseSnapshotEnvelope(
            """
            event: chat_event
            data: {"schema":{"family":"chat_event","major":1,"minor":0},"event_id":"evt-1","event_name":"conversation_snapshot","conversation_id":"conv-1","emitted_at":"2026-03-12T00:00:00Z","payload":{"event":"conversation_snapshot","data":{"nodes":[]}}}

            event: relay.done
            data: {}
            """.trimIndent()
        )

        assertEquals("conv-1", envelope?.conversationId)
        assertEquals("conversation_snapshot", envelope?.event)
        assertEquals("evt-1", envelope?.eventId)
        assertEquals("chat_event", envelope?.schemaFamily)
        assertEquals(1, envelope?.schemaMajor)
        assertEquals(0, envelope?.schemaMinor)
        assertEquals("2026-03-12T00:00:00Z", envelope?.emittedAt)
    }

    @Test
    fun `parseSnapshotEnvelope throws on envelopes missing canonical event name`() {
        try {
            client.parseSnapshotEnvelope(
                """
                event: chat_event
                data: {"conversation_id":"conv-1","payload":{"event":"conversation_snapshot","data":{"nodes":[]}}}
                """.trimIndent()
            )
            error("expected snapshot parse failure")
        } catch (error: HubSnapshotParseException) {
            assertTrue(error.message.orEmpty().contains("malformed"))
        }
    }

    @Test
    fun `parseSnapshotEnvelope throws on malformed payload`() {
        try {
            client.parseSnapshotEnvelope("data: {not-json}\n")
            error("expected parse failure")
        } catch (error: HubSnapshotParseException) {
            assertTrue(error.message.orEmpty().contains("malformed"))
        }
    }

    @Test
    fun `parseResolvedPromptPreview returns stable prompt and provenance shape`() {
        val preview = client.parseResolvedPromptPreview(
            """
            {
              "preview": {
                "raw_prompt": "{{char}} waves at {{sticker_wave}}",
                "resolved_prompt": "Analyst waves at {{sticker_wave}}",
                "records": [
                  {
                    "key": "char",
                    "value": "Analyst",
                    "category": "agent",
                    "source": "agent_profile",
                    "status": "applied"
                  },
                  {
                    "key": "sticker_wave",
                    "value": ":wave:",
                    "category": "sticker_media",
                    "source": "sticker_pack",
                    "status": "deferred"
                  }
                ],
                "unresolved_tokens": ["{{missing_value}}"],
                "partial_tokens": ["{{half"]
              }
            }
            """.trimIndent()
        )

        assertEquals("{{char}} waves at {{sticker_wave}}", preview.rawPrompt)
        assertEquals("Analyst waves at {{sticker_wave}}", preview.resolvedPrompt)
        assertEquals(listOf("agent", "sticker_media"), preview.records.map { it.category })
        assertEquals(listOf("applied", "deferred"), preview.records.map { it.status })
        assertEquals(listOf("{{missing_value}}"), preview.unresolvedTokens)
        assertEquals(listOf("{{half"), preview.partialTokens)
    }

    @Test
    fun `parseProviderCatalog returns provider and model lists`() {
        val catalog = client.parseProviderCatalog(
            """
            [
              {
                "provider_local_id": "provider_local_123",
                "display_name": "OpenAI",
                "avatar_uri": "https://example.com/openai.png",
                "adapter_kind": "openai_compatible",
                "default_model_id": "gpt-4.1-mini",
                "models": [
                  {
                    "model_id": "gpt-4.1-mini",
                    "display_name": "GPT-4.1 mini",
                    "enabled": true,
                    "is_default": true
                  },
                  {
                    "model_id": "gpt-4.1",
                    "display_name": "GPT-4.1",
                    "enabled": false,
                    "is_default": false
                  }
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, catalog.size)
        assertEquals("provider_local_123", catalog.first().providerLocalId)
        assertEquals("OpenAI", catalog.first().displayName)
        assertEquals("gpt-4.1-mini", catalog.first().defaultModelId)
        assertEquals(2, catalog.first().models.size)
        assertTrue(catalog.first().models.first().isDefault)
        assertFalse(catalog.first().models.last().enabled)
    }

    @Test
    fun `parsePairingExchangeSuccess returns stable token and resume anchor shape`() {
        val response = client.parsePairingExchangeSuccess(
            """
            {
              "pairing_session_id": "pairing-session-1",
              "namespace": "workspace-alpha",
              "status": "paired",
              "mobile_token": {
                "access_token": "mobile-token",
                "token_type": "bearer",
                "expires_at": "2026-03-13T12:00:00Z"
              },
              "trusted_device": {
                "trusted_device_id": "trusted-device-1",
                "device_name": "Pixel 9",
                "device_platform": "android"
              },
              "resume_anchor": {
                "anchor": "resume-anchor-1",
                "expires_at": "2026-03-20T12:00:00Z"
              }
            }
            """.trimIndent()
        )

        assertEquals("pairing-session-1", response.pairingSessionId)
        assertEquals("workspace-alpha", response.namespace)
        assertEquals("paired", response.status)
        assertEquals("mobile-token", response.mobileToken.accessToken)
        assertEquals("trusted-device-1", response.trustedDevice.trustedDeviceId)
        assertEquals("resume-anchor-1", response.resumeAnchor.anchor)
    }

    @Test
    fun `parsePairingExchangeFailure returns explicit pairing failure shape`() {
        val response = client.parsePairingExchangeFailure(
            """
            {
              "pairing_session_id": "pairing-session-1",
              "namespace": "workspace-alpha",
              "status": "rejected",
              "error": {
                "code": "bootstrap_token_expired",
                "message": "bootstrap token expired",
                "retriable": false
              }
            }
            """.trimIndent()
        )

        assertEquals("pairing-session-1", response.pairingSessionId)
        assertEquals("workspace-alpha", response.namespace)
        assertEquals("rejected", response.status)
        assertEquals("bootstrap_token_expired", response.error.code)
        assertEquals(false, response.error.retriable)
    }

    @Test
    fun `parseAgentConfig returns frozen mobile_v1 document shape`() {
        val agent = client.parseAgentConfig(
            """
            {
              "id": "8f5a4569-4494-41d3-8201-ecf5efcdd4a5",
              "identity": {
                "name": "Planner",
                "avatar_uri": "file:///planner.png",
                "description": "Plans the next move"
              },
              "prompt": {
                "system_prompt": "You are a planner.",
                "prompt_mode": "system_and_message_template",
                "message_template": "Plan for {{goal}}",
                "placeholders": [
                  {
                    "key": "goal",
                    "label": "Goal",
                    "value": "Ship the bridge contract",
                    "description": "Current objective"
                  }
                ]
              },
              "model": {
                "provider_local_id": "provider_local_demo",
                "preset_local_id": "provider_preset_local_balanced",
                "model_id": "gpt-4.1-mini"
              },
              "request": {
                "temperature": 0.7,
                "top_p": 0.9,
                "max_output_tokens": 1024,
                "reasoning_effort": "medium"
              },
              "memory": {
                "use_conversation_memory": true,
                "pin_top_level_facts": true
              },
              "tools": {
                "enable_local_tools": false,
                "overrides": [
                  {
                    "tool_id": "search",
                    "enabled": false
                  }
                ]
              },
              "group": {
                "role_label": "Analyst",
                "aliases": ["planner"],
                "mention_tags": ["ops"],
                "respond_to_mentions": true,
                "allow_auto_relay": true
              },
              "created_at": "2026-03-13T00:00:00Z",
              "updated_at": "2026-03-13T01:00:00Z"
            }
            """.trimIndent()
        )

        assertEquals("Planner", agent.identity.name)
        assertEquals("system_and_message_template", agent.prompt.promptMode)
        assertEquals("goal", agent.prompt.placeholders.single().key)
        assertEquals("provider_local_demo", agent.model.providerLocalId)
        assertEquals(0.7f, agent.request.temperature ?: 0f, 0.0001f)
        assertEquals(0.9f, agent.request.topP ?: 0f, 0.0001f)
        assertEquals(1024, agent.request.maxOutputTokens)
        assertEquals("medium", agent.request.reasoningEffort)
        assertFalse(agent.tools.enableLocalTools)
        assertEquals(listOf("planner"), agent.group.aliases)
        assertEquals("2026-03-13T01:00:00Z", agent.updatedAt)
    }

    @Test
    fun `parseBridgeFailure returns structured validation feedback`() {
        val failure = client.parseBridgeFailure(
            statusCode = 400,
            statusMessage = "Bad Request",
            raw = """
            {
              "error": {
                "kind": "validation",
                "code": "agent_config_invalid",
                "message": "prompt.system_prompt must be non-empty",
                "retriable": false
              }
            }
            """.trimIndent()
        )

        assertEquals(400, failure.statusCode)
        assertEquals("validation", failure.error.kind)
        assertEquals("agent_config_invalid", failure.error.code)
        assertEquals("prompt.system_prompt must be non-empty", failure.error.message)
        assertFalse(failure.error.retriable)
    }

    @Test
    fun `createAgent returns explicit validation failure payload instead of opaque exception`() = runBlocking {
        val agentClient = OkHttpSseHubApiClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body(
                            """
                            {
                              "error": {
                                "kind": "validation",
                                "code": "agent_config_invalid",
                                "message": "identity.name must be non-empty",
                                "retriable": false
                              }
                            }
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            baseUrl = "http://localhost:4001"
        )

        val result = agentClient.createAgent(
            HubAgentConfig(
                id = "8f5a4569-4494-41d3-8201-ecf5efcdd4a5",
                identity = HubAgentIdentityConfig(name = ""),
                prompt = HubAgentPromptConfig(systemPrompt = "You are a planner.")
            )
        )

        assertTrue(result is HubAgentMutationResult.Failure)
        val failure = (result as HubAgentMutationResult.Failure).failure
        assertEquals(400, failure.statusCode)
        assertEquals("agent_config_invalid", failure.error.code)
        assertEquals("identity.name must be non-empty", failure.error.message)
    }

    @Test
    fun `listAgents parses Rust-owned truth document array`() = runBlocking {
        val agentClient = OkHttpSseHubApiClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """
                            [
                              {
                                "id": "8f5a4569-4494-41d3-8201-ecf5efcdd4a5",
                                "identity": {
                                  "name": "Planner"
                                },
                                "prompt": {
                                  "system_prompt": "You are a planner."
                                },
                                "created_at": "2026-03-13T00:00:00Z",
                                "updated_at": "2026-03-13T01:00:00Z"
                              }
                            ]
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            baseUrl = "http://localhost:4001"
        )

        val agents = agentClient.listAgents()

        assertEquals(1, agents.size)
        assertEquals("Planner", agents.single().identity.name)
        assertEquals("You are a planner.", agents.single().prompt.systemPrompt)
    }

    @Test
    fun `exchangePairing returns failure result for explicit API failure payload`() = runBlocking {
        val pairingClient = OkHttpSseHubApiClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(501)
                        .message("Not Implemented")
                        .body(
                            """
                            {
                              "pairing_session_id": "pairing-session-1",
                              "namespace": "workspace-alpha",
                              "status": "rejected",
                              "error": {
                                "code": "pairing_exchange_not_ready",
                                "message": "pairing exchange contract is frozen, but token issuance is implemented in TES-43",
                                "retriable": true
                              }
                            }
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            baseUrl = "http://localhost:4001"
        )

        val result = pairingClient.exchangePairing(
            HubPairingExchangeRequest(
                pairingSessionId = "pairing-session-1",
                namespace = "workspace-alpha",
                bootstrapToken = "bootstrap-secret",
                deviceName = "Pixel 9",
                devicePublicKey = "base64-public-key",
            )
        )

        assertTrue(result is HubPairingExchangeResult.Failure)
        val failure = (result as HubPairingExchangeResult.Failure).response
        assertEquals("pairing_exchange_not_ready", failure.error.code)
        assertEquals(true, failure.error.retriable)
    }

    @Test
    fun `fetchConversationSnapshot throws on malformed payload`() = runBlocking {
        val malformedClient = OkHttpSseHubApiClient(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("data: {not-json}\n\n".toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
                .build(),
            baseUrl = "http://localhost:4001"
        )

        try {
            malformedClient.fetchConversationSnapshot("conv-1")
            error("expected snapshot parse failure")
        } catch (error: HubSnapshotParseException) {
            assertTrue(error.message.orEmpty().contains("manual reopen required"))
        }
    }

    private fun response(code: Int, message: String): Response {
        return Response.Builder()
            .request(
                Request.Builder()
                    .url("http://localhost:4001/api/chat")
                    .build()
            )
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()
    }
}
