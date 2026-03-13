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
        const val HUB_AGENTS_PATH = "/api/agents"
        const val HUB_PROVIDER_CATALOG_PATH = "/api/providers/catalog"
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

    override suspend fun listProviderCatalog(): List<HubProviderCatalogEntry> {
        val httpRequest = Request.Builder()
            .url("$baseUrl$HUB_PROVIDER_CATALOG_PATH")
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
                throw IllegalStateException(
                    "Hub provider catalog failed: ${response.code} ${response.message} $errorBody"
                )
            }

            val bodyText = response.body?.string().orEmpty()
            return parseProviderCatalog(bodyText)
        }
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
                            resumeAnchor = item.optJSONObject("resume_anchor")
                                ?.let { anchor ->
                                    anchor.optString("message_id")
                                        .takeIf { it.isNotBlank() }
                                        ?.let { messageId ->
                                            HubResumeAnchor(
                                                messageId = messageId,
                                                nodeId = anchor.optString("node_id")
                                                    .takeIf { it.isNotBlank() },
                                                variantId = anchor.optString("variant_id")
                                                    .takeIf { it.isNotBlank() },
                                            )
                                        }
                                },
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

    override suspend fun listAgents(): List<HubAgentConfig> {
        val httpRequest = jsonRequestBuilder("$baseUrl$HUB_AGENTS_PATH")
            .get()
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw parseBridgeFailureException(
                    statusCode = response.code,
                    statusMessage = response.message,
                    raw = raw,
                    operation = "Hub agent list"
                )
            }
            return parseAgentList(raw)
        }
    }

    override suspend fun getAgent(agentId: String): HubAgentConfig {
        val httpRequest = jsonRequestBuilder("$baseUrl$HUB_AGENTS_PATH/$agentId")
            .get()
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw parseBridgeFailureException(
                    statusCode = response.code,
                    statusMessage = response.message,
                    raw = raw,
                    operation = "Hub agent fetch"
                )
            }
            return parseAgentConfig(raw)
        }
    }

    override suspend fun createAgent(agent: HubAgentConfig): HubAgentMutationResult {
        return executeAgentMutation(
            path = HUB_AGENTS_PATH,
            method = "POST",
            body = agent.toUpstreamJsonBody(),
        )
    }

    override suspend fun updateAgent(agentId: String, agent: HubAgentConfig): HubAgentMutationResult {
        return executeAgentMutation(
            path = "$HUB_AGENTS_PATH/$agentId",
            method = "PUT",
            body = agent.toUpstreamJsonBody(),
        )
    }

    override suspend fun deleteAgent(agentId: String): HubAgentMutationResult {
        return executeAgentMutation(
            path = "$HUB_AGENTS_PATH/$agentId",
            method = "DELETE",
        )
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

    private fun executeAgentMutation(
        path: String,
        method: String,
        body: String? = null,
    ): HubAgentMutationResult {
        val requestBuilder = jsonRequestBuilder("$baseUrl$path")
        when (method) {
            "POST" -> requestBuilder.post((body ?: "{}").toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            "PUT" -> requestBuilder.put((body ?: "{}").toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            "DELETE" -> requestBuilder.delete()
            else -> error("unsupported method $method")
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                return HubAgentMutationResult.Success(
                    agent = parseAgentConfig(raw),
                    statusCode = response.code,
                )
            }

            return runCatching {
                HubAgentMutationResult.Failure(
                    parseBridgeFailure(
                        statusCode = response.code,
                        statusMessage = response.message,
                        raw = raw,
                    )
                )
            }.getOrElse {
                throw IllegalStateException(
                    "Hub agent mutation failed: ${response.code} ${response.message} $raw",
                    it
                )
            }
        }
    }

    private fun jsonRequestBuilder(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .apply {
                addHeader("Accept", CONTENT_TYPE_JSON)
                addHeader("Content-Type", CONTENT_TYPE_JSON)
                bearerTokenProvider().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
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

    internal fun parseAgentList(raw: String): List<HubAgentConfig> {
        return runCatching {
            val agents = JSONArray(raw)
            buildList {
                for (index in 0 until agents.length()) {
                    val item = agents.optJSONObject(index) ?: continue
                    add(parseAgentConfig(item))
                }
            }
        }.getOrElse {
            throw IllegalStateException("Hub agent list payload malformed", it)
        }
    }

    internal fun parseAgentConfig(raw: String): HubAgentConfig {
        return runCatching {
            parseAgentConfig(JSONObject(raw))
        }.getOrElse {
            throw IllegalStateException("Hub agent payload malformed", it)
        }
    }

    internal fun parseBridgeFailure(
        statusCode: Int,
        statusMessage: String,
        raw: String,
    ): HubBridgeFailure {
        return runCatching {
            val body = JSONObject(raw)
            val errorBody = body.optJSONObject("error")
                ?: error("missing error object")
            HubBridgeFailure(
                statusCode = statusCode,
                statusMessage = statusMessage,
                error = HubBridgeError(
                    kind = errorBody.optString("kind"),
                    code = errorBody.optNullableString("code"),
                    message = errorBody.optString("message"),
                    retriable = errorBody.optBoolean("retriable", false),
                )
            )
        }.getOrElse {
            throw IllegalStateException("Hub bridge error payload malformed", it)
        }
    }

    internal fun parseProviderCatalog(raw: String): List<HubProviderCatalogEntry> {
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val models = item.optJSONArray("models")
                    add(
                        HubProviderCatalogEntry(
                            providerLocalId = item.optString("provider_local_id"),
                            displayName = item.optString("display_name"),
                            avatarUri = item.optString("avatar_uri").takeIf { it.isNotBlank() },
                            adapterKind = item.optString("adapter_kind"),
                            defaultModelId = item.optString("default_model_id")
                                .takeIf { it.isNotBlank() },
                            models = buildList {
                                for (modelIndex in 0 until (models?.length() ?: 0)) {
                                    val model = models?.optJSONObject(modelIndex) ?: continue
                                    add(
                                        HubProviderCatalogModel(
                                            modelId = model.optString("model_id"),
                                            displayName = model.optString("display_name")
                                                .takeIf { it.isNotBlank() },
                                            enabled = model.optBoolean("enabled", true),
                                            isDefault = model.optBoolean("is_default", false),
                                        )
                                    )
                                }
                            }
                        )
                    )
                }
            }
        }.getOrElse {
            throw IllegalStateException("Hub provider catalog payload malformed", it)
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

    private fun parseAgentConfig(json: JSONObject): HubAgentConfig {
        val identity = json.optJSONObject("identity") ?: JSONObject()
        val prompt = json.optJSONObject("prompt") ?: JSONObject()
        val promptPlaceholders = prompt.optJSONArray("placeholders")
        val model = json.optJSONObject("model") ?: JSONObject()
        val request = json.optJSONObject("request") ?: JSONObject()
        val memory = json.optJSONObject("memory") ?: JSONObject()
        val tools = json.optJSONObject("tools") ?: JSONObject()
        val overrides = tools.optJSONArray("overrides")
        val group = json.optJSONObject("group") ?: JSONObject()

        return HubAgentConfig(
            id = json.optString("id"),
            identity = HubAgentIdentityConfig(
                name = identity.optString("name"),
                avatarUri = identity.optNullableString("avatar_uri"),
                description = identity.optNullableString("description"),
            ),
            prompt = HubAgentPromptConfig(
                systemPrompt = prompt.optString("system_prompt"),
                promptMode = prompt.optString("prompt_mode").ifBlank { "system_only" },
                messageTemplate = prompt.optNullableString("message_template"),
                placeholders = buildList {
                    for (index in 0 until (promptPlaceholders?.length() ?: 0)) {
                        val item = promptPlaceholders?.optJSONObject(index) ?: continue
                        add(
                            HubAgentPromptVariable(
                                key = item.optString("key"),
                                label = item.optNullableString("label"),
                                value = item.optString("value"),
                                description = item.optNullableString("description"),
                            )
                        )
                    }
                },
            ),
            model = HubAgentModelConfig(
                providerLocalId = model.optNullableString("provider_local_id"),
                presetLocalId = model.optNullableString("preset_local_id"),
                modelId = model.optNullableString("model_id"),
            ),
            request = HubAgentRequestConfig(
                temperature = request.optNullableFloat("temperature"),
                topP = request.optNullableFloat("top_p"),
                maxOutputTokens = request.optNullableInt("max_output_tokens"),
                reasoningEffort = request.optNullableString("reasoning_effort"),
            ),
            memory = HubAgentMemoryConfig(
                useConversationMemory = memory.optBoolean("use_conversation_memory", true),
                pinTopLevelFacts = memory.optBoolean("pin_top_level_facts", false),
            ),
            tools = HubAgentToolConfig(
                enableLocalTools = tools.optBoolean("enable_local_tools", true),
                overrides = buildList {
                    for (index in 0 until (overrides?.length() ?: 0)) {
                        val item = overrides?.optJSONObject(index) ?: continue
                        add(
                            HubAgentToolPermission(
                                toolId = item.optString("tool_id"),
                                enabled = item.optBoolean("enabled", true),
                            )
                        )
                    }
                },
            ),
            group = HubAgentGroupConfig(
                roleLabel = group.optNullableString("role_label"),
                aliases = group.optStringArray("aliases"),
                mentionTags = group.optStringArray("mention_tags"),
                respondToMentions = group.optBoolean("respond_to_mentions", true),
                allowAutoRelay = group.optBoolean("allow_auto_relay", false),
            ),
            createdAt = json.optNullableString("created_at"),
            updatedAt = json.optNullableString("updated_at"),
        )
    }

    private fun parseBridgeFailureException(
        statusCode: Int,
        statusMessage: String,
        raw: String,
        operation: String,
    ): Throwable {
        return runCatching {
            HubBridgeFailureException(
                parseBridgeFailure(
                    statusCode = statusCode,
                    statusMessage = statusMessage,
                    raw = raw,
                )
            )
        }.getOrElse {
            IllegalStateException("$operation failed: $statusCode $statusMessage $raw", it)
        }
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
            .put("stream", stream)
            .put("messages", messagesJson)
            .apply {
                providerLocalId?.takeIf { it.isNotBlank() }?.let {
                    put("provider_local_id", it)
                }
                modelId?.takeIf { it.isNotBlank() }?.let {
                    put("model_id", it)
                }
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

    private fun HubAgentConfig.toUpstreamJsonBody(): String {
        return JSONObject()
            .put("id", id)
            .put(
                "identity",
                JSONObject()
                    .put("name", identity.name)
                    .apply {
                        identity.avatarUri?.takeIf { it.isNotBlank() }?.let { put("avatar_uri", it) }
                        identity.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                    }
            )
            .put(
                "prompt",
                JSONObject()
                    .put("system_prompt", prompt.systemPrompt)
                    .put("prompt_mode", prompt.promptMode)
                    .apply {
                        prompt.messageTemplate?.takeIf { it.isNotBlank() }?.let {
                            put("message_template", it)
                        }
                        put(
                            "placeholders",
                            JSONArray().apply {
                                prompt.placeholders.forEach { placeholder ->
                                    put(
                                        JSONObject()
                                            .put("key", placeholder.key)
                                            .put("value", placeholder.value)
                                            .apply {
                                                placeholder.label?.takeIf { it.isNotBlank() }?.let {
                                                    put("label", it)
                                                }
                                                placeholder.description?.takeIf { it.isNotBlank() }?.let {
                                                    put("description", it)
                                                }
                                            }
                                    )
                                }
                            }
                        )
                    }
            )
            .put(
                "model",
                JSONObject().apply {
                    model.providerLocalId?.takeIf { it.isNotBlank() }?.let {
                        put("provider_local_id", it)
                    }
                    model.presetLocalId?.takeIf { it.isNotBlank() }?.let {
                        put("preset_local_id", it)
                    }
                    model.modelId?.takeIf { it.isNotBlank() }?.let {
                        put("model_id", it)
                    }
                }
            )
            .put(
                "request",
                JSONObject().apply {
                    request.temperature?.let { put("temperature", it.toDouble()) }
                    request.topP?.let { put("top_p", it.toDouble()) }
                    request.maxOutputTokens?.let { put("max_output_tokens", it) }
                    request.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
                        put("reasoning_effort", it)
                    }
                }
            )
            .put(
                "memory",
                JSONObject()
                    .put("use_conversation_memory", memory.useConversationMemory)
                    .put("pin_top_level_facts", memory.pinTopLevelFacts)
            )
            .put(
                "tools",
                JSONObject()
                    .put("enable_local_tools", tools.enableLocalTools)
                    .put(
                        "overrides",
                        JSONArray().apply {
                            tools.overrides.forEach { override ->
                                put(
                                    JSONObject()
                                        .put("tool_id", override.toolId)
                                        .put("enabled", override.enabled)
                                )
                            }
                        }
                    )
            )
            .put(
                "group",
                JSONObject()
                    .put("aliases", JSONArray(group.aliases))
                    .put("mention_tags", JSONArray(group.mentionTags))
                    .put("respond_to_mentions", group.respondToMentions)
                    .put("allow_auto_relay", group.allowAutoRelay)
                    .apply {
                        group.roleLabel?.takeIf { it.isNotBlank() }?.let { put("role_label", it) }
                    }
            )
            .apply {
                createdAt?.takeIf { it.isNotBlank() }?.let { put("created_at", it) }
                updatedAt?.takeIf { it.isNotBlank() }?.let { put("updated_at", it) }
            }
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

private fun JSONObject.optNullableString(key: String): String? {
    return if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}

private fun JSONObject.optNullableFloat(key: String): Float? {
    return if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
}

private fun JSONObject.optNullableInt(key: String): Int? {
    return if (has(key) && !isNull(key)) optInt(key) else null
}

internal class HubRelayErrorException(message: String) : IllegalStateException(
    message.ifBlank { "Hub relay.error" }
)

internal class HubStreamFailureException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal class HubSnapshotParseException(message: String) : IllegalStateException(message)
