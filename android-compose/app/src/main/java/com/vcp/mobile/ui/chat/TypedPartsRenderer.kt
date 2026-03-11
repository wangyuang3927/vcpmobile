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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vcp.mobile.ui.markdown.MarkdownAstContent

@Composable
fun TypedPartsRenderer(
    message: ChatMessage,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val parts = message.rendererParts()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parts.forEach { part ->
            when (part.type.trim().lowercase()) {
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

                "markdown_block" -> {
                    val shouldUseAst = message.ast != null &&
                        parts.count { it.type.equals("markdown_block", ignoreCase = true) } == 1
                    if (shouldUseAst) {
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
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        val codeText = buildString {
                            if (!part.language.isNullOrBlank()) {
                                append(part.language).append('\n')
                            }
                            append(part.text)
                        }
                        Text(
                            text = codeText,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
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
