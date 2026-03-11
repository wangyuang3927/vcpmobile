package com.vcp.mobile.data.network

import com.vcp.mobile.domain.model.ast.MarkdownAstNode
import com.vcp.mobile.domain.model.ast.MarkdownDocument
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Hub AST 流节点（扁平化模型）。
 *
 * Args:
 *   index: 节点序号（可选）。
 *   id: Hub 节点 ID（可选）。
 *   parentId: 父节点 ID（可选）。
 *   type: 节点类型（paragraph/heading/code/listItem...）。
 *   text: 节点文本（可选）。
 *   depth: 标题层级（可选）。
 *   lang: 代码语言（可选）。
 *   ordered: 是否有序列表（可选）。
 */
data class AstStreamNode(
    val index: Int? = null,
    val id: String? = null,
    val parentId: String? = null,
    val type: String,
    val text: String? = null,
    val depth: Int? = null,
    val lang: String? = null,
    val ordered: Boolean? = null,
)

/**
 * Hub AstStreamNode 解析器：
 * - 支持 JSON（单对象/数组）与 NDJSON（按行）输入。
 * - 支持 AstStreamChunk（{ done, node }）或裸 AstStreamNode。
 * - 将 AstStreamNode 聚合映射为 MarkdownDocument。
 */
object AstStreamParser {

    /**
     * 解析 NDJSON 文本为 AstStreamNode 列表。
     */
    fun parseNdjson(ndjson: String): List<AstStreamNode> {
        return ndjson
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseJsonObjectOrNull(it) }
            .toList()
    }

    /**
     * 解析 JSON 文本为 AstStreamNode 列表。
     * 支持对象（单节点/Chunk）与数组。
     */
    fun parseJson(json: String): List<AstStreamNode> {
        val payload = json.trim()
        if (payload.isEmpty()) {
            return emptyList()
        }

        return try {
            if (payload.startsWith("[")) {
                parseJsonArray(JSONArray(payload))
            } else {
                listOfNotNull(parseJsonObject(JSONObject(payload)))
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    /**
     * 将 NDJSON 直接解析并聚合为 MarkdownDocument。
     */
    fun parseNdjsonToDocument(ndjson: String): MarkdownDocument {
        return toMarkdownDocument(parseNdjson(ndjson))
    }

    /**
     * 将 JSON 直接解析并聚合为 MarkdownDocument。
     */
    fun parseJsonToDocument(json: String): MarkdownDocument {
        return toMarkdownDocument(parseJson(json))
    }

    /**
     * AstStreamNode -> MarkdownDocument。
     */
    fun toMarkdownDocument(nodes: List<AstStreamNode>): MarkdownDocument {
        val markdownNodes = mutableListOf<MarkdownAstNode>()
        var orderedIndex = 0
        var previousOrderedListItem = false

        nodes.forEach { node ->
            when (node.type) {
                "paragraph", "text" -> {
                    node.text?.takeIf { it.isNotBlank() }?.let {
                        markdownNodes += MarkdownAstNode.Text(content = it)
                    }
                    previousOrderedListItem = false
                    orderedIndex = 0
                }

                "heading" -> {
                    node.text?.takeIf { it.isNotBlank() }?.let {
                        markdownNodes += MarkdownAstNode.Heading(
                            level = (node.depth ?: 1).coerceIn(1, 6),
                            content = it,
                        )
                    }
                    previousOrderedListItem = false
                    orderedIndex = 0
                }

                "code" -> {
                    node.text?.let {
                        markdownNodes += MarkdownAstNode.Code(
                            content = it,
                            language = node.lang,
                        )
                    }
                    previousOrderedListItem = false
                    orderedIndex = 0
                }

                "listItem", "list_item" -> {
                    node.text?.takeIf { it.isNotBlank() }?.let { text ->
                        val itemIndex = if (node.ordered == true) {
                            orderedIndex = if (previousOrderedListItem) orderedIndex + 1 else 1
                            orderedIndex
                        } else {
                            null
                        }
                        markdownNodes += MarkdownAstNode.ListItem(
                            content = text,
                            index = itemIndex,
                        )
                    }
                    previousOrderedListItem = node.ordered == true
                    if (!previousOrderedListItem) {
                        orderedIndex = 0
                    }
                }

                else -> {
                    previousOrderedListItem = false
                    orderedIndex = 0
                }
            }
        }

        return MarkdownDocument(nodes = markdownNodes)
    }

    private fun parseJsonArray(array: JSONArray): List<AstStreamNode> {
        val result = mutableListOf<AstStreamNode>()
        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject ?: continue
            parseJsonObject(item)?.let(result::add)
        }
        return result
    }

    private fun parseJsonObjectOrNull(line: String): AstStreamNode? {
        return try {
            parseJsonObject(JSONObject(line))
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseJsonObject(source: JSONObject): AstStreamNode? {
        val nodeObject = extractNodeObject(source) ?: return null
        val type = nodeObject.optString("type").trim()
        if (type.isEmpty()) {
            return null
        }

        return AstStreamNode(
            index = nodeObject.optIntOrNull("index"),
            id = nodeObject.optNullableString("id"),
            parentId = nodeObject.optNullableString("parentId"),
            type = type,
            text = nodeObject.optNullableString("text"),
            depth = nodeObject.optIntOrNull("depth"),
            lang = nodeObject.optNullableString("lang"),
            ordered = nodeObject.optBooleanOrNull("ordered"),
        )
    }

    private fun extractNodeObject(source: JSONObject): JSONObject? {
        return when {
            source.has("node") && !source.isNull("node") -> source.optJSONObject("node")
            source.optBoolean("done", false) && !source.has("type") -> null
            else -> source
        }
    }
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return optString(key)
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) {
        return null
    }
    val value = opt(key)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) {
        return null
    }
    val value = opt(key)
    return when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
}
