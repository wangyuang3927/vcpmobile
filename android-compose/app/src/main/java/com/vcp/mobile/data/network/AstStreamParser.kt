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
    val checked: Boolean? = null,
    val url: String? = null,
    val display: Boolean? = null,
    val headers: List<String>? = null,
    val rows: List<List<String>>? = null,
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

                "code", "code_block", "fenced_code" -> {
                    node.text?.let {
                        markdownNodes += MarkdownAstNode.Code(
                            content = it,
                            language = node.lang,
                        )
                    }
                    previousOrderedListItem = false
                    orderedIndex = 0
                }

                "listItem", "list_item", "task_list_item", "taskItem", "checkbox" -> {
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
                            checked = node.checked,
                        )
                    }
                    previousOrderedListItem = node.ordered == true
                    if (!previousOrderedListItem) {
                        orderedIndex = 0
                    }
                }

                "quote", "blockquote", "block_quote" -> {
                    node.text?.takeIf { it.isNotBlank() }?.let {
                        markdownNodes += MarkdownAstNode.Quote(content = it)
                    }
                    previousOrderedListItem = false
                    orderedIndex = 0
                }

                "link" -> {
                    val destination = node.url?.takeIf { it.isNotBlank() }
                    val label = node.text?.takeIf { it.isNotBlank() } ?: destination
                    if (destination != null && label != null) {
                        markdownNodes += MarkdownAstNode.Link(
                            label = label,
                            destination = destination,
                        )
                    }
                    previousOrderedListItem = false
                    orderedIndex = 0
                }

                "inline_code", "inlineCode" -> {
                    node.text?.takeIf { it.isNotBlank() }?.let {
                        markdownNodes += MarkdownAstNode.InlineCode(content = it)
                    }
                    previousOrderedListItem = false
                    orderedIndex = 0
                }

                "math", "math_block", "block_math", "inline_math" -> {
                    node.text?.takeIf { it.isNotBlank() }?.let {
                        markdownNodes += MarkdownAstNode.Math(
                            expression = it,
                            isBlock = node.display == true ||
                                node.type == "math_block" ||
                                node.type == "block_math",
                        )
                    }
                    previousOrderedListItem = false
                    orderedIndex = 0
                }

                "table" -> {
                    tableNodeOrNull(node)?.let(markdownNodes::add)
                    previousOrderedListItem = false
                    orderedIndex = 0
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
            checked = nodeObject.optBooleanOrNull("checked"),
            url = nodeObject.optNullableString("url") ?: nodeObject.optNullableString("href"),
            display = nodeObject.optBooleanOrNull("display"),
            headers = nodeObject.optStringList("headers")
                ?: nodeObject.optStringList("header")
                ?: nodeObject.optStringList("columns"),
            rows = nodeObject.optStringMatrix("rows")
                ?: nodeObject.optStringMatrix("cells"),
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

private fun tableNodeOrNull(node: AstStreamNode): MarkdownAstNode.Table? {
    val structuredTable = if (!node.headers.isNullOrEmpty() || !node.rows.isNullOrEmpty()) {
        MarkdownAstNode.Table(
            headers = node.headers.orEmpty(),
            rows = node.rows.orEmpty(),
        )
    } else {
        null
    }

    if (structuredTable != null) {
        return structuredTable
    }

    val text = node.text?.takeIf { it.isNotBlank() } ?: return null
    return parseMarkdownTable(text)
}

private fun parseMarkdownTable(text: String): MarkdownAstNode.Table? {
    val lines = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (lines.size < 2) {
        return null
    }

    val headers = parseTableRow(lines[0])
    if (headers.isEmpty() || !isMarkdownTableDivider(lines[1])) {
        return null
    }

    val rows = lines.drop(2).mapNotNull { row ->
        parseTableRow(row).takeIf { it.isNotEmpty() }
    }
    return MarkdownAstNode.Table(
        headers = headers,
        rows = rows,
    )
}

private fun parseTableRow(line: String): List<String> {
    return line
        .trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun isMarkdownTableDivider(line: String): Boolean {
    val cells = parseTableRow(line)
    if (cells.isEmpty()) {
        return false
    }

    return cells.all { cell ->
        cell.all { it == '-' || it == ':' }
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

private fun JSONObject.optStringList(key: String): List<String>? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return (opt(key) as? JSONArray)?.toStringList()
}

private fun JSONObject.optStringMatrix(key: String): List<List<String>>? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return (opt(key) as? JSONArray)?.toStringMatrix()
}

private fun JSONArray.toStringList(): List<String> {
    return buildList {
        for (index in 0 until length()) {
            val value = opt(index)
            when (value) {
                null,
                JSONObject.NULL -> Unit
                is String -> add(value)
                else -> add(value.toString())
            }
        }
    }
}

private fun JSONArray.toStringMatrix(): List<List<String>> {
    return buildList {
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is JSONArray -> add(value.toStringList())
                is JSONObject -> {
                    val cells = value.optStringList("cells") ?: value.optStringList("columns")
                    if (cells != null) {
                        add(cells)
                    }
                }
                is String -> {
                    val parsed = parseTableRow(value)
                    if (parsed.isNotEmpty()) {
                        add(parsed)
                    }
                }
            }
        }
    }
}
