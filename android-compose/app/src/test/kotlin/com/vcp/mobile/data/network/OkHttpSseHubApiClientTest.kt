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
