package com.vcp.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vcp.mobile.ui.markdown.MarkdownAstContent
import com.vcp.mobile.ui.markdown.MarkdownCodeBlock

@Composable
fun TypedPartsRenderer(
    message: ChatMessage,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val parts = message.rendererParts()
    val shouldUseAstBody = message.ast != null && parts.supportsAstBodyRendering()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parts.forEach { part ->
            when (part.normalizedType()) {
                "reasoning" -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = part.text,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.74f),
                        )
                    }
                }

                "text", "markdown_block" -> {
                    if (shouldUseAstBody) {
                        CompositionLocalProvider(LocalContentColor provides textColor) {
                            MarkdownAstContent(
                                document = message.ast!!,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Text(
                            text = part.text,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                "code_block" -> {
                    MarkdownCodeBlock(
                        code = part.text,
                        language = part.language,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                "image" -> {
                    TypedPartCard(
                        title = part.title ?: "图片",
                        body = part.url ?: part.text,
                        meta = part.mime,
                        textColor = textColor,
                    )
                }

                "document" -> {
                    TypedPartCard(
                        title = part.title ?: "文档",
                        body = part.url ?: part.text,
                        meta = part.mime,
                        textColor = textColor,
                    )
                }

                "tool" -> {
                    TypedPartCard(
                        title = part.title?.let { "工具: $it" } ?: "工具",
                        body = part.text,
                        meta = part.state,
                        textColor = textColor,
                    )
                }

                "tool_call" -> {
                    TypedPartCard(
                        title = part.language?.let { "工具调用: $it" } ?: "工具调用",
                        body = part.text,
                        meta = "调用参数",
                        textColor = textColor,
                    )
                }

                "tool_result" -> {
                    TypedPartCard(
                        title = part.language?.let { "工具结果: $it" } ?: "工具结果",
                        body = part.text,
                        meta = "执行结果",
                        textColor = textColor,
                    )
                }

                "error" -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = part.text,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                else -> {
                    Text(
                        text = part.text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (parts.isEmpty() && message.content.isNotBlank()) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TypedPartCard(
    title: String,
    body: String?,
    meta: String?,
    textColor: Color,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
            )
            meta?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            body?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
