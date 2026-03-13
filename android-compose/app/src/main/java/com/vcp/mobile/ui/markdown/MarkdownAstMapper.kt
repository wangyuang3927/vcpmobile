package com.vcp.mobile.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
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
            MarkdownCodeBlock(
                code = node.content,
                language = node.language,
            )
        }

        is MarkdownAstNode.ListItem -> {
            ListItemRow(node = node)
        }

        is MarkdownAstNode.Quote -> {
            QuoteBlock(content = node.content)
        }

        is MarkdownAstNode.Link -> {
            MarkdownLink(
                label = node.label,
                destination = node.destination,
            )
        }

        is MarkdownAstNode.Table -> {
            MarkdownTable(node = node)
        }

        is MarkdownAstNode.Math -> {
            MathBlock(node = node)
        }

        is MarkdownAstNode.InlineCode -> {
            InlineCodeChip(content = node.content)
        }
    }
}

@Composable
fun MarkdownCodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val codeStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!language.isNullOrBlank()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                    )
                }

                Text(
                    text = code,
                    style = codeStyle,
                    color = contentColor,
                    softWrap = false,
                )
            }
        }
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

@Composable
private fun ListItemRow(node: MarkdownAstNode.ListItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = checkboxPrefix(node.checked) ?: listPrefix(index = node.index),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = node.content,
            modifier = Modifier.weight(1f, fill = true),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun QuoteBlock(content: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(99.dp),
                ),
        )
        Text(
            text = content,
            modifier = Modifier.weight(1f, fill = true),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MarkdownLink(
    label: String,
    destination: String,
) {
    val uriHandler = LocalUriHandler.current

    Text(
        text = label,
        modifier = Modifier.clickable {
            runCatching { uriHandler.openUri(destination) }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
}

@Composable
private fun MarkdownTable(node: MarkdownAstNode.Table) {
    val columnCount = maxOf(
        node.headers.size,
        node.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (columnCount == 0) {
        return
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .horizontalScroll(scrollState)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (node.headers.isNotEmpty()) {
            TableRow(
                cells = node.headers,
                columnCount = columnCount,
                header = true,
            )
        }
        node.rows.forEach { row ->
            TableRow(
                cells = row,
                columnCount = columnCount,
                header = false,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    columnCount: Int,
    header: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(columnCount) { index ->
            Surface(
                color = if (header) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = cells.getOrNull(index).orEmpty(),
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = if (header) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                )
            }
        }
    }
}

@Composable
private fun MathBlock(node: MarkdownAstNode.Math) {
    if (node.isBlock) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = node.expression,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        return
    }

    InlineCodeChip(content = node.expression)
}

@Composable
private fun InlineCodeChip(content: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

private fun checkboxPrefix(checked: Boolean?): String? {
    return when (checked) {
        true -> "☑ "
        false -> "☐ "
        null -> null
    }
}
