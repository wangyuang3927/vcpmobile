package com.vcp.mobile.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vcp.mobile.domain.model.ast.MarkdownAstNode
import com.vcp.mobile.domain.model.ast.MarkdownDocument

/**
 * Chat UI 可直接调用的 Markdown AST 渲染入口。
 *
 * Args:
 *   document: Markdown 文档 AST。
 *   modifier: 外层布局修饰符。
 */
@Composable
fun MarkdownAstContent(
    document: MarkdownDocument,
    modifier: Modifier = Modifier
) {
    MarkdownAstContent(
        nodes = document.nodes,
        modifier = modifier
    )
}

/**
 * Chat UI 可直接调用的 Markdown AST 渲染入口（节点列表版本）。
 *
 * Args:
 *   nodes: 需要渲染的 AST 节点列表。
 *   modifier: 外层布局修饰符。
 */
@Composable
fun MarkdownAstContent(
    nodes: List<MarkdownAstNode>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        nodes.forEach { node ->
            RenderAstNode(node = node)
        }
    }
}

@Composable
private fun RenderAstNode(node: MarkdownAstNode) {
    when (node) {
        is MarkdownAstNode.Text -> {
            Text(
                text = node.content,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        is MarkdownAstNode.Heading -> {
            Text(
                text = node.content,
                style = headingStyle(level = node.level)
            )
        }

        is MarkdownAstNode.Code -> {
            CodeBlock(
                code = node.content,
                language = node.language
            )
        }

        is MarkdownAstNode.ListItem -> {
            Text(
                text = listPrefix(index = node.index) + node.content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun CodeBlock(
    code: String,
    language: String?
) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val codeStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = bgColor, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }

        Text(
            text = code,
            style = codeStyle,
            color = contentColor
        )
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle {
    return when (level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        4 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
}

private fun listPrefix(index: Int?): String {
    return if (index == null) {
        "• "
    } else {
        "$index. "
    }
}
