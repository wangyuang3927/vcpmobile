package com.vcp.mobile.data.network

import org.json.JSONArray
import org.json.JSONObject

data class RustChatEventEnvelope(
    val conversationId: String?,
    val event: String,
    val data: JSONObject
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
            val event = payload.optString("event").trim()
            val data = payload.optJSONObject("data") ?: JSONObject()
            if (event.isEmpty()) {
                null
            } else {
                RustChatEventEnvelope(
                    conversationId = root.optString("conversation_id").takeIf { it.isNotBlank() },
                    event = event,
                    data = data
                )
            }
        }.getOrNull()
    }

    fun extractSnapshotMessages(data: JSONObject): List<RustSnapshotMessage> {
        val nodes = data.optJSONArray("nodes") ?: return emptyList()
        val results = mutableListOf<RustSnapshotMessage>()

        for (nodeIndex in 0 until nodes.length()) {
            val nodeBundle = nodes.optJSONObject(nodeIndex) ?: continue
            val message = extractNodeBundleMessage(nodeBundle) ?: continue
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
        val nodeBundle = data.optJSONObject("node") ?: return null
        return extractNodeBundleMessage(nodeBundle)
    }

    fun extractPartDelta(data: JSONObject): RustMessageDelta {
        return extractParts(data.optJSONArray("appended_parts"))
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
            }
        }

        return RustMessageDelta(
            parts = orderedParts,
            appendedText = textBuilder.toString(),
            appendedReasoning = reasoningBuilder.toString(),
            partTypes = partTypes,
        )
    }

    private fun extractNodeBundleMessage(nodeBundle: JSONObject): RustSnapshotMessage? {
        val node = nodeBundle.optJSONObject("node") ?: return null
        val nodeId = node.optString("id").trim()
        if (nodeId.isBlank()) return null
        val role = node.optString("role").trim().ifBlank { HUB_ROLE_ASSISTANT }

        val variants = nodeBundle.optJSONArray("variants") ?: return null
        if (variants.length() == 0) return null
        val selectIndex = node.optInt("select_index", 0).coerceAtLeast(0)
        val selectedVariant = variants.optJSONObject(selectIndex)
            ?: variants.optJSONObject(0)
            ?: return null
        val variant = selectedVariant.optJSONObject("variant") ?: return null
        val variantId = variant.optString("id").trim()
        if (variantId.isBlank()) return null

        return RustSnapshotMessage(
            identity = RustStreamIdentity(nodeId = nodeId, variantId = variantId),
            role = role,
            delta = extractParts(selectedVariant.optJSONArray("parts"))
        )
    }
}
