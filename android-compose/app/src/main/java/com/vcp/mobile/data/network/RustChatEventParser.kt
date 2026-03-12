package com.vcp.mobile.data.network

import org.json.JSONArray
import org.json.JSONObject

enum class RustChatEventKind(val wireName: String) {
    CONVERSATION_LIST_INVALIDATE("conversation_list_invalidate"),
    CONVERSATION_SNAPSHOT("conversation_snapshot"),
    CONVERSATION_NODE_UPSERT("conversation_node_upsert"),
    CONVERSATION_NODE_SELECT("conversation_node_select"),
    CONVERSATION_META_UPDATE("conversation_meta_update"),
    GENERATION_STARTED("generation_started"),
    GENERATION_PART_DELTA("generation_part_delta"),
    GENERATION_COMPLETED("generation_completed"),
    GENERATION_FAILED("generation_failed"),
    GENERATION_CANCELLED("generation_cancelled"),
    TOOL_CALL_STARTED("tool_call_started"),
    TOOL_CALL_COMPLETED("tool_call_completed"),
    TOOL_CALL_FAILED("tool_call_failed"),
    TOOL_CALL_CANCELLED("tool_call_cancelled"),
    DRAFT_UPDATED("draft_updated"),
    DRAFT_CLEARED("draft_cleared"),
    AUTH_QR_PLACEHOLDER("auth_qr_placeholder"),
    ENGINE_ERROR("engine_error");

    companion object {
        fun fromWireName(wireName: String): RustChatEventKind? =
            values().firstOrNull { it.wireName == wireName.trim() }
    }
}

enum class RustEventErrorKind {
    PROVIDER,
    TOOL,
    TRANSPORT,
    VALIDATION,
    INTERNAL,
    UNKNOWN,
}

enum class RustToolCallPhase {
    STARTED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class RustChatEventEnvelope(
    val conversationId: String?,
    val kind: RustChatEventKind,
    val event: String,
    val data: JSONObject,
    val eventId: String? = null,
    val schemaFamily: String? = null,
    val schemaMajor: Int? = null,
    val schemaMinor: Int? = null,
    val emittedAt: String? = null,
)

data class RustStreamIdentity(
    val nodeId: String,
    val variantId: String
) {
    val messageKey: String
        get() = "$nodeId:$variantId"
}

data class RustMessageDelta(
    val parts: List<RustMessagePart> = emptyList(),
    val appendedText: String = "",
    val appendedReasoning: String = "",
    val partTypes: List<String> = emptyList(),
)

data class RustMessagePart(
    val type: String,
    val text: String = "",
    val language: String? = null,
    val title: String? = null,
    val url: String? = null,
    val mime: String? = null,
    val state: String? = null,
)

data class RustEventError(
    val kind: RustEventErrorKind,
    val code: String? = null,
    val message: String,
    val retriable: Boolean? = null,
)

data class RustToolCallEvent(
    val identity: RustStreamIdentity,
    val toolCallId: String,
    val toolName: String,
    val phase: RustToolCallPhase,
    val argumentsJson: String? = null,
    val error: RustEventError? = null,
    val message: String? = null,
)

data class RustSnapshotMessage(
    val identity: RustStreamIdentity,
    val role: String,
    val delta: RustMessageDelta
)

object RustChatEventParser {

    fun parseEnvelope(raw: String): RustChatEventEnvelope? {
        return runCatching {
            val root = JSONObject(raw)
            val payload = root.optJSONObject("payload") ?: return null
            val event = root.optString("event_name").trim()
                .ifBlank { payload.optString("event").trim() }
            val data = payload.optJSONObject("data")
                ?: root.optJSONObject("data")
                ?: JSONObject()
            val schema = root.optJSONObject("schema")
            if (event.isEmpty()) {
                null
            } else {
                val kind = RustChatEventKind.fromWireName(event) ?: return null
                RustChatEventEnvelope(
                    conversationId = root.optString("conversation_id").takeIf { it.isNotBlank() },
                    kind = kind,
                    event = event,
                    data = data,
                    eventId = root.optString("event_id").takeIf { it.isNotBlank() },
                    schemaFamily = schema?.optString("family")?.takeIf { it.isNotBlank() },
                    schemaMajor = schema?.takeIf { it.has("major") }?.optInt("major"),
                    schemaMinor = schema?.takeIf { it.has("minor") }?.optInt("minor"),
                    emittedAt = root.optString("emitted_at").takeIf { it.isNotBlank() },
                )
            }
        }.getOrNull()
    }

    fun extractSnapshotMessages(data: JSONObject): List<RustSnapshotMessage> {
        val branch = data.optJSONObject("branch") ?: return emptyList()
        val nodes = branch.optJSONArray("nodes") ?: return emptyList()
        val results = mutableListOf<RustSnapshotMessage>()

        for (nodeIndex in 0 until nodes.length()) {
            val snapshotNode = nodes.optJSONObject(nodeIndex) ?: continue
            val message = extractSnapshotNodeMessage(snapshotNode) ?: continue
            results += message
        }

        return results
    }

    fun extractSnapshotMessage(data: JSONObject): RustSnapshotMessage? {
        return extractSnapshotMessages(data).firstOrNull()
    }

    fun extractGenerationIdentity(data: JSONObject): RustStreamIdentity? {
        val nodeId = data.optString("node_id").trim()
        val variantId = data.optString("variant_id").trim()
        if (nodeId.isBlank() || variantId.isBlank()) return null
        return RustStreamIdentity(nodeId = nodeId, variantId = variantId)
    }

    fun extractNodeUpsertMessage(data: JSONObject): RustSnapshotMessage? {
        val snapshotNode = data.optJSONObject("node") ?: return null
        return extractSnapshotNodeMessage(snapshotNode)
    }

    fun extractPartDelta(data: JSONObject): RustMessageDelta {
        return extractParts(data.optJSONArray("appended_parts"))
    }

    fun extractEventError(data: JSONObject): RustEventError? {
        val error = data.optJSONObject("error")
        if (error != null) {
            val message = error.optString("message").trim()
            if (message.isBlank()) return null
            return RustEventError(
                kind = parseErrorKind(error.optString("kind")),
                code = error.optString("code").takeIf { it.isNotBlank() },
                message = message,
                retriable = error.takeIf { it.has("retriable") }?.optBoolean("retriable"),
            )
        }

        val message = data.optString("message").trim()
        if (message.isBlank()) return null
        return RustEventError(
            kind = RustEventErrorKind.UNKNOWN,
            message = message,
        )
    }

    fun extractToolCallEvent(
        kind: RustChatEventKind,
        data: JSONObject,
    ): RustToolCallEvent? {
        val phase = when (kind) {
            RustChatEventKind.TOOL_CALL_STARTED -> RustToolCallPhase.STARTED
            RustChatEventKind.TOOL_CALL_COMPLETED -> RustToolCallPhase.COMPLETED
            RustChatEventKind.TOOL_CALL_FAILED -> RustToolCallPhase.FAILED
            RustChatEventKind.TOOL_CALL_CANCELLED -> RustToolCallPhase.CANCELLED
            else -> return null
        }
        val identity = extractGenerationIdentity(data) ?: return null
        val toolCallId = data.optString("tool_call_id").trim()
        val toolName = data.optString("tool_name").trim()
        if (toolCallId.isBlank() || toolName.isBlank()) return null

        return RustToolCallEvent(
            identity = identity,
            toolCallId = toolCallId,
            toolName = toolName,
            phase = phase,
            argumentsJson = data.optString("arguments_json").takeIf { it.isNotBlank() },
            error = extractEventError(data),
            message = data.optString("message").takeIf { it.isNotBlank() },
        )
    }

    private fun extractParts(parts: JSONArray?): RustMessageDelta {
        if (parts == null || parts.length() == 0) {
            return RustMessageDelta()
        }

        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val partTypes = mutableListOf<String>()
        val orderedParts = mutableListOf<RustMessagePart>()

        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val payload = part.optJSONObject("payload") ?: continue
            val type = payload.optString("type")
            if (type.isNotBlank()) {
                partTypes += type
            }
            when (type) {
                "text" -> {
                    val text = payload.optString("text")
                    orderedParts += RustMessagePart(type = type, text = text)
                    textBuilder.append(text)
                }
                "reasoning" -> {
                    val text = payload.optString("text")
                    orderedParts += RustMessagePart(type = type, text = text)
                    reasoningBuilder.append(text)
                }
                "markdown_block" -> {
                    val markdown = payload.optString("markdown")
                    orderedParts += RustMessagePart(type = type, text = markdown)
                    textBuilder.append(markdown)
                }
                "code_block" -> {
                    val language = payload.optString("language").takeIf { it.isNotBlank() }
                    val code = payload.optString("code")
                    orderedParts += RustMessagePart(
                        type = type,
                        text = code,
                        language = language,
                    )
                    if (language != null) {
                        textBuilder.append("```").append(language).append('\n')
                    } else {
                        textBuilder.append("```\n")
                    }
                    textBuilder.append(code)
                    if (!code.endsWith("\n")) {
                        textBuilder.append('\n')
                    }
                    textBuilder.append("```")
                }
                "tool_call" -> {
                    val toolName = payload.optString("tool_name").trim()
                    val argumentsJson = payload.optString("arguments_json")
                    orderedParts += RustMessagePart(
                        type = type,
                        text = argumentsJson,
                        language = toolName.takeIf { it.isNotBlank() },
                    )
                }
                "tool_result" -> {
                    val toolName = payload.optString("tool_name").trim()
                    val resultJson = payload.optString("result_json")
                    orderedParts += RustMessagePart(
                        type = type,
                        text = resultJson,
                        language = toolName.takeIf { it.isNotBlank() },
                    )
                }
                "image" -> {
                    val alt = payload.optNonBlankString("alt")
                    val url = payload.optNonBlankString("url")
                    val mime = payload.optNonBlankString("mime")
                    val summaryText = buildSummaryText(listOfNotNull(alt, url, mime))
                    orderedParts += RustMessagePart(
                        type = type,
                        text = "",
                        title = alt,
                        url = url,
                        mime = mime,
                    )
                    textBuilder.append(summaryText)
                }
                "document" -> {
                    val fileName = payload.optNonBlankString("file_name")
                        ?: payload.optNonBlankString("name")
                    val url = payload.optNonBlankString("url")
                    val mime = payload.optNonBlankString("mime")
                    val summaryText = buildSummaryText(listOfNotNull(fileName, url, mime))
                    orderedParts += RustMessagePart(
                        type = type,
                        text = "",
                        title = fileName,
                        url = url,
                        mime = mime,
                    )
                    textBuilder.append(summaryText)
                }
                "tool" -> {
                    val toolName = payload.optNonBlankString("tool_name")
                    val state = payload.optNonBlankString("state")
                    val inputJson = payload.optNonBlankString("input_json")
                        ?: payload.optNonBlankString("arguments_json")
                    val outputJson = payload.optNonBlankString("output_json")
                        ?: payload.optNonBlankString("result_json")
                    val errorMessage = payload.optNonBlankString("error_message")
                    orderedParts += RustMessagePart(
                        type = type,
                        text = buildSummaryText(
                            listOfNotNull(
                                outputJson,
                                errorMessage,
                                if (outputJson == null && errorMessage == null) inputJson else null,
                            )
                        ),
                        title = toolName,
                        state = state,
                    )
                    textBuilder.append(orderedParts.last().text)
                }
                "error" -> {
                    val message = payload.optString("message")
                    orderedParts += RustMessagePart(type = type, text = message)
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append(message)
                    }
                }
            }
        }

        return RustMessageDelta(
            parts = orderedParts,
            appendedText = textBuilder.toString(),
            appendedReasoning = reasoningBuilder.toString(),
            partTypes = partTypes,
        )
    }

    private fun extractSnapshotNodeMessage(snapshotNode: JSONObject): RustSnapshotMessage? {
        val nodeId = snapshotNode.optString("node_id").trim()
        if (nodeId.isBlank()) return null
        val role = snapshotNode.optString("role").trim().ifBlank { HUB_ROLE_ASSISTANT }

        val selectedVariant = snapshotNode.optJSONObject("selected_variant") ?: return null
        val variantId = selectedVariant.optString("variant_id").trim()
        if (variantId.isBlank()) return null

        return RustSnapshotMessage(
            identity = RustStreamIdentity(nodeId = nodeId, variantId = variantId),
            role = role,
            delta = extractParts(selectedVariant.optJSONArray("parts"))
        )
    }

    private fun parseErrorKind(raw: String): RustEventErrorKind {
        return when (raw.trim().lowercase()) {
            "provider" -> RustEventErrorKind.PROVIDER
            "tool" -> RustEventErrorKind.TOOL
            "transport" -> RustEventErrorKind.TRANSPORT
            "validation" -> RustEventErrorKind.VALIDATION
            "internal" -> RustEventErrorKind.INTERNAL
            else -> RustEventErrorKind.UNKNOWN
        }
    }
}

private fun JSONObject.optNonBlankString(name: String): String? =
    optString(name).takeIf { it.isNotBlank() }

private fun buildSummaryText(lines: List<String>): String = lines.joinToString("\n")
