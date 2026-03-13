package com.vcp.mobile.data.network

import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hub API 客户端（OkHttp + SSE）。
 *
 * 当前首发策略：
 * - `relay.error` 视为终止性协议错误：上报并结束流，不做自动重试。
 * - 网络 / HTTP 失败视为终止性传输错误：上报并结束流，不做自动重试。
 * - 普通事件里的坏 role / 坏 JSON 仅降级为 `role = null`，不打断流。
 * - snapshot hydrate 为单次拉取；坏 payload 直接抛出显式异常，由上层决定手动重开/恢复。
 */
class OkHttpSseHubApiClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val bearerTokenProvider: () -> String = { "" }
) : HubApiClient {

    companion object {
        const val HUB_CHAT_PATH = "/api/chat"
        const val HUB_CHAT_REGENERATE_PATH = "/api/chat/regenerate"
        const val HUB_CHAT_SELECT_VARIANT_PATH = "/api/chat/select-variant"
        const val HUB_CONVERSATIONS_PATH = "/api/chat/conversations"
        const val HUB_CATALOG_PATH = "/api/chat/catalog"
        const val HUB_CHAT_STREAM_PATH = "/api/chat/stream"
        const val HUB_PROMPT_PREVIEW_PATH = "/api/agents/prompt-preview"
        const val HUB_PAIRING_EXCHANGE_PATH = "/api/pairing/exchange"
        const val CONTENT_TYPE_JSON = "application/json"
        const val ACCEPT_SSE = "text/event-stream"

        private const val EVENT_RELAY_DONE = "relay.done"
        private const val EVENT_RELAY_ERROR = "relay.error"
    }

    override suspend fun sendMessage(request: HubSendMessageRequest): HubSendMessageResponse {
        val httpRequest = Request.Builder()
            .url("$baseUrl$HUB_CHAT_PATH")
            .post(request.toUpstreamJsonBody().toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .apply {
                addHeader("Accept", CONTENT_TYPE_JSON)
                addHeader("Content-Type", CONTENT_TYPE_JSON)
                bearerTokenProvider().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw IllegalStateException("Hub request failed: ${response.code} ${response.message} $errorBody")
            }

            val bodyText = response.body?.string().orEmpty()
            return HubSendMessageResponse(
                requestId = response.header("x-request-id").orEmpty(),
                assistantMessage = bodyText,
                rawBody = bodyText
            )
        }
    }

    override fun streamEvents(request: HubSendMessageRequest): Flow<HubStreamEvent> = callbackFlow {
        openSseStream(
            buildSsePostRequest(
                path = HUB_CHAT_PATH,
                body = request.toUpstreamJsonBody(),
            )
        )
    }

    override fun regenerateAssistant(request: HubRegenerateRequest): Flow<HubStreamEvent> = callbackFlow {
        openSseStream(
            buildSsePostRequest(
                path = HUB_CHAT_REGENERATE_PATH,
                body = request.toUpstreamJsonBody(),
            )
        )
    }

    override fun selectVariant(request: HubSelectVariantRequest): Flow<HubStreamEvent> = callbackFlow {
        openSseStream(
            buildSsePostRequest(
                path = HUB_CHAT_SELECT_VARIANT_PATH,
                body = request.toUpstreamJsonBody(),
            )
        )
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<HubStreamEvent>.openSseStream(
        eventRequest: Request
    ) {

        val listener = object : EventSourceListener() {
            var terminalEventDispatched = false

            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(HubStreamEvent.Opened)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                when (val event = dispatchSseEvent(type, data)) {
                    HubStreamEvent.Completed -> {
                        emitTerminal(event)
                        eventSource.cancel()
                        close()
                    }

                    is HubStreamEvent.Error -> {
                        emitTerminal(event)
                        eventSource.cancel()
                        close()
                    }

                    else -> trySend(event)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                emitTerminal(HubStreamEvent.Completed)
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val throwable = classifyStreamFailure(t = t, response = response)
                emitTerminal(HubStreamEvent.Error(throwable))
                close()
            }

            private fun emitTerminal(event: HubStreamEvent) {
                if (terminalEventDispatched) return
                terminalEventDispatched = true
                trySend(event)
            }
        }

        val eventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(eventRequest, listener)

        awaitClose {
            eventSource.cancel()
        }
    }

    private fun buildSsePostRequest(path: String, body: String): Request {
        return Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .apply {
                addHeader("Accept", ACCEPT_SSE)
                addHeader("Content-Type", CONTENT_TYPE_JSON)
                bearerTokenProvider().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()
    }

    override suspend fun listConversations(): List<HubConversationSummary> {
        return runCatching { requestConversationList(HUB_CATALOG_PATH) }
            .recoverCatching { requestConversationList(HUB_CONVERSATIONS_PATH) }
            .getOrThrow()
    }

    private fun requestConversationList(path: String): List<HubConversationSummary> {
        val httpRequest = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .apply {
                addHeader("Accept", CONTENT_TYPE_JSON)
                bearerTokenProvider().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw IllegalStateException("Hub list failed [$path]: ${response.code} ${response.message} $errorBody")
            }

            val bodyText = response.body?.string().orEmpty()
            val array = JSONArray(bodyText)
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        HubConversationSummary(
                            conversationId = item.optString("conversation_id"),
                            title = item.optString("title"),
                            updatedAt = item.optString("updated_at"),
                            generationState = item.optString("generation_state"),
                            currentCursor = item.optString("current_cursor").takeIf { it.isNotBlank() },
                            summary = item.optString("summary").takeIf { it.isNotBlank() },
                            pinned = item.optBoolean("pinned", false),
                            isRecoverable = item.optBoolean("is_recoverable", true),
                            nodeCount = item.optInt("node_count", 0),
                        )
                    )
                }
            }
        }
    }

    override suspend fun fetchConversationSnapshot(conversationId: String): RustChatEventEnvelope? {
        val httpRequest = Request.Builder()
            .url("$baseUrl$HUB_CHAT_STREAM_PATH/$conversationId")
            .get()
            .apply {
                addHeader("Accept", ACCEPT_SSE)
                bearerTokenProvider().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw IllegalStateException("Hub hydrate failed: ${response.code} ${response.message} $errorBody")
            }

            val raw = response.body?.string().orEmpty()
            return parseSnapshotEnvelope(raw)
        }
    }

    override suspend fun previewResolvedPrompt(request: HubPromptPreviewRequest): HubResolvedPromptPreview {
        val httpRequest = Request.Builder()
            .url("$baseUrl$HUB_PROMPT_PREVIEW_PATH")
            .post(request.toUpstreamJsonBody().toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .apply {
                addHeader("Accept", CONTENT_TYPE_JSON)
                addHeader("Content-Type", CONTENT_TYPE_JSON)
                bearerTokenProvider().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw IllegalStateException(
                    "Hub prompt preview failed: ${response.code} ${response.message} $errorBody"
                )
            }

            val raw = response.body?.string().orEmpty()
            return parseResolvedPromptPreview(raw)
        }
    }

    override suspend fun exchangePairing(request: HubPairingExchangeRequest): HubPairingExchangeResult {
        val httpRequest = Request.Builder()
            .url("$baseUrl$HUB_PAIRING_EXCHANGE_PATH")
            .post(request.toUpstreamJsonBody().toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .apply {
                addHeader("Accept", CONTENT_TYPE_JSON)
                addHeader("Content-Type", CONTENT_TYPE_JSON)
                bearerTokenProvider().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            return if (response.isSuccessful) {
                HubPairingExchangeResult.Success(parsePairingExchangeSuccess(raw))
            } else {
                runCatching { parsePairingExchangeFailure(raw) }
                    .map { HubPairingExchangeResult.Failure(it) }
                    .getOrElse {
                        throw IllegalStateException(
                            "Hub pairing exchange failed: ${response.code} ${response.message} $raw",
                            it
                        )
                    }
            }
        }
    }

    internal fun parseSnapshotEnvelope(raw: String): RustChatEventEnvelope? {
        val payload = raw.lineSequence()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ").trim() }
            .firstOrNull()
            ?: return null

        return RustChatEventParser.parseEnvelope(payload)
            ?: throw HubSnapshotParseException(
                "Hub snapshot payload malformed; manual reopen required"
            )
    }

    internal fun parseResolvedPromptPreview(raw: String): HubResolvedPromptPreview {
        return runCatching {
            val preview = JSONObject(raw).optJSONObject("preview")
                ?: error("missing preview object")
            val records = preview.optJSONArray("records")
            HubResolvedPromptPreview(
                rawPrompt = preview.optString("raw_prompt"),
                resolvedPrompt = preview.optString("resolved_prompt"),
                records = buildList {
                    for (index in 0 until (records?.length() ?: 0)) {
                        val item = records?.optJSONObject(index) ?: continue
                        add(
                            HubPromptPreviewRecord(
                                key = item.optString("key"),
                                value = item.optString("value"),
                                category = item.optString("category"),
                                source = item.optString("source"),
                                status = item.optString("status"),
                            )
                        )
                    }
                },
                unresolvedTokens = preview.optStringArray("unresolved_tokens"),
                partialTokens = preview.optStringArray("partial_tokens"),
            )
        }.getOrElse {
            throw IllegalStateException("Hub prompt preview payload malformed", it)
        }
    }

    internal fun parsePairingExchangeSuccess(raw: String): HubPairingExchangeSuccessResponse {
        return runCatching {
            val body = JSONObject(raw)
            val mobileToken = body.optJSONObject("mobile_token")
                ?: error("missing mobile_token object")
            val trustedDevice = body.optJSONObject("trusted_device")
                ?: error("missing trusted_device object")
            val resumeAnchor = body.optJSONObject("resume_anchor")
                ?: error("missing resume_anchor object")
            HubPairingExchangeSuccessResponse(
                pairingSessionId = body.optString("pairing_session_id"),
                namespace = body.optString("namespace"),
                status = body.optString("status"),
                mobileToken = HubPairingMobileToken(
                    accessToken = mobileToken.optString("access_token"),
                    tokenType = mobileToken.optString("token_type"),
                    expiresAt = mobileToken.optString("expires_at"),
                ),
                trustedDevice = HubPairingTrustedDevice(
                    trustedDeviceId = trustedDevice.optString("trusted_device_id"),
                    deviceName = trustedDevice.optString("device_name"),
                    devicePlatform = trustedDevice.optString("device_platform"),
                ),
                resumeAnchor = HubPairingResumeAnchor(
                    anchor = resumeAnchor.optString("anchor"),
                    expiresAt = resumeAnchor.optString("expires_at"),
                ),
            )
        }.getOrElse {
            throw IllegalStateException("Hub pairing exchange success payload malformed", it)
        }
    }

    internal fun parsePairingExchangeFailure(raw: String): HubPairingExchangeFailureResponse {
        return runCatching {
            val body = JSONObject(raw)
            val errorBody = body.optJSONObject("error")
                ?: error("missing error object")
            HubPairingExchangeFailureResponse(
                pairingSessionId = body.optString("pairing_session_id").takeIf { it.isNotBlank() },
                namespace = body.optString("namespace").takeIf { it.isNotBlank() },
                status = body.optString("status"),
                error = HubPairingExchangeError(
                    code = errorBody.optString("code"),
                    message = errorBody.optString("message"),
                    retriable = errorBody.optBoolean("retriable", false),
                ),
            )
        }.getOrElse {
            throw IllegalStateException("Hub pairing exchange failure payload malformed", it)
        }
    }

    internal fun dispatchSseEvent(eventName: String?, data: String): HubStreamEvent {
        val normalizedEvent = eventName?.trim().orEmpty().ifEmpty { "message" }

        return when (normalizedEvent) {
            EVENT_RELAY_DONE -> HubStreamEvent.Completed
            EVENT_RELAY_ERROR -> HubStreamEvent.Error(
                HubRelayErrorException(data.ifBlank { "Hub relay.error" })
            )

            else -> HubStreamEvent.Message(
                event = normalizedEvent,
                data = data,
                role = resolveRole(data)
            )
        }
    }

    internal fun classifyStreamFailure(t: Throwable?, response: Response?): Throwable {
        if (t is HubRelayErrorException || t is HubSnapshotParseException) {
            return t
        }

        val code = response?.code
        val message = response?.message.orEmpty().trim()

        return when {
            code != null -> HubStreamFailureException(
                message = buildString {
                    append("Hub SSE stream failed")
                    append(" (HTTP ").append(code)
                    if (message.isNotBlank()) {
                        append(' ').append(message)
                    }
                    append(") — no auto retry; resend or reopen the conversation manually")
                },
                cause = t
            )

            t is IOException -> HubStreamFailureException(
                message = "Hub SSE network failure — no auto retry; resend or reopen the conversation manually",
                cause = t
            )

            t != null -> HubStreamFailureException(
                message = "Hub SSE stream terminated unexpectedly — no auto retry; resend or reopen the conversation manually",
                cause = t
            )

            else -> HubStreamFailureException(
                message = "Hub SSE stream failed — no auto retry; resend or reopen the conversation manually"
            )
        }
    }

    private fun resolveRole(data: String): String? {
        return runCatching {
            val json = JSONObject(data)
            json.optString("role").takeIf { it.isNotBlank() }
        }.getOrNull()?.toMessageSender()?.toRole()
    }

    private fun HubSendMessageRequest.toUpstreamJsonBody(): String {
        val messagesJson = JSONArray().apply {
            messages.forEach { message ->
                put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", message.content)
                )
            }
        }

        return JSONObject()
            .put("model", model)
            .put("stream", stream)
            .put("messages", messagesJson)
            .apply {
                conversationId?.takeIf { it.isNotBlank() }?.let { put("conversation_id", it) }
            }
            .toString()
    }

    private fun HubRegenerateRequest.toUpstreamJsonBody(): String {
        return JSONObject()
            .put("conversation_id", conversationId)
            .put("node_id", nodeId)
            .toString()
    }

    private fun HubSelectVariantRequest.toUpstreamJsonBody(): String {
        return JSONObject()
            .put("conversation_id", conversationId)
            .put("node_id", nodeId)
            .put("variant_id", variantId)
            .toString()
    }

    private fun HubPromptPreviewRequest.toUpstreamJsonBody(): String {
        val placeholdersJson = JSONArray().apply {
            placeholders.forEach { placeholder ->
                put(
                    JSONObject()
                        .put("key", placeholder.key)
                        .put("value", placeholder.value)
                        .put("category", placeholder.category)
                        .put("source", placeholder.source)
                )
            }
        }

        return JSONObject()
            .put("raw_prompt", rawPrompt)
            .put("placeholders", placeholdersJson)
            .toString()
    }

    private fun HubPairingExchangeRequest.toUpstreamJsonBody(): String {
        return JSONObject()
            .put("pairing_session_id", pairingSessionId)
            .put("namespace", namespace)
            .put("bootstrap_token", bootstrapToken)
            .put("device_name", deviceName)
            .put("device_platform", devicePlatform)
            .put("device_public_key", devicePublicKey)
            .toString()
    }
}

private fun JSONObject.optStringArray(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

internal class HubRelayErrorException(message: String) : IllegalStateException(
    message.ifBlank { "Hub relay.error" }
)

internal class HubStreamFailureException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal class HubSnapshotParseException(message: String) : IllegalStateException(message)
