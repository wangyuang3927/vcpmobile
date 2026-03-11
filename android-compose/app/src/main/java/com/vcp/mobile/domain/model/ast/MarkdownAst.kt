package com.vcp.mobile.domain.model.ast

/**
 * Markdown 文档根节点。
 *
 * Args:
 *   nodes: 按文档顺序排列的 AST 节点列表。
 */
data class MarkdownDocument(
    val nodes: List<MarkdownAstNode>
)

/**
 * Markdown AST 节点统一定义（当前仅包含 Chat UI 所需最小集合）。
 */
sealed interface MarkdownAstNode {
    /**
     * 普通文本节点。
     *
     * Args:
     *   content: 纯文本内容。
     */
    data class Text(
        val content: String
    ) : MarkdownAstNode

    /**
     * 标题节点。
     *
     * Args:
     *   level: 标题级别，取值范围建议 1~6。
     *   content: 标题文本。
     */
    data class Heading(
        val level: Int,
        val content: String
    ) : MarkdownAstNode

    /**
     * 代码块节点。
     *
     * Args:
     *   content: 代码文本。
     *   language: 可选语言标识（如 "kotlin"）。
     */
    data class Code(
        val content: String,
        val language: String? = null
    ) : MarkdownAstNode

    /**
     * 列表项节点。
     *
     * Args:
     *   content: 列表项文本。
     *   index: 有序列表序号，null 表示无序列表。
     */
    data class ListItem(
        val content: String,
        val index: Int? = null
    ) : MarkdownAstNode
}
