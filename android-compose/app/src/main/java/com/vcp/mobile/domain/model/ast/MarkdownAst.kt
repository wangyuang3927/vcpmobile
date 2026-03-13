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
        val index: Int? = null,
        val checked: Boolean? = null,
    ) : MarkdownAstNode

    /**
     * 引用块节点。
     *
     * Args:
     *   content: 引用文本。
     */
    data class Quote(
        val content: String
    ) : MarkdownAstNode

    /**
     * 独立链接节点。
     *
     * Args:
     *   label: 展示文本。
     *   destination: 链接目标地址。
     */
    data class Link(
        val label: String,
        val destination: String
    ) : MarkdownAstNode

    /**
     * 表格节点。
     *
     * Args:
     *   headers: 表头单元格。
     *   rows: 数据行。
     */
    data class Table(
        val headers: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
    ) : MarkdownAstNode

    /**
     * 数学公式节点。
     *
     * Args:
     *   expression: 公式内容。
     *   isBlock: 是否按块级公式显示。
     */
    data class Math(
        val expression: String,
        val isBlock: Boolean = false,
    ) : MarkdownAstNode

    /**
     * 行内代码节点。
     *
     * Args:
     *   content: 代码内容。
     */
    data class InlineCode(
        val content: String
    ) : MarkdownAstNode
}
