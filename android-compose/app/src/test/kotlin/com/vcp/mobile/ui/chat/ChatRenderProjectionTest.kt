package com.vcp.mobile.ui.chat

import com.vcp.mobile.domain.model.ast.MarkdownDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderProjectionTest {

    @Test
    fun `markdown message prefers markdown mode and carries typed identity`() {
        val message = ChatMessage(
            id = "node-1:variant-1",
            sender = MessageSender.AGENT,
            content = "hello",
            reasoning = "thinking",
            ast = MarkdownDocument(emptyList()),
            nodeId = "node-1",
            variantId = "variant-1",
            partTypes = listOf("reasoning", "markdown_block"),
        )

        val projection = message.renderProjection()

        assertTrue(projection.identity.isTyped)
        assertEquals(ChatBodyMode.MARKDOWN, projection.bodyMode)
        assertEquals(listOf("思考", "Markdown"), projection.labels)
    }

    @Test
    fun `code only message falls back to code mode`() {
        val message = ChatMessage(
            sender = MessageSender.AGENT,
            content = "println(1)",
            nodeId = "node-2",
            variantId = "variant-2",
            partTypes = listOf("code_block"),
        )

        val projection = message.renderProjection()

        assertEquals(ChatBodyMode.CODE_FALLBACK, projection.bodyMode)
        assertEquals(listOf("代码"), projection.labels)
    }

    @Test
    fun `plain user message stays plain text`() {
        val message = ChatMessage(
            sender = MessageSender.USER,
            content = "hi",
        )

        val projection = message.renderProjection()

        assertEquals(ChatBodyMode.PLAIN_TEXT, projection.bodyMode)
        assertTrue(projection.labels.isEmpty())
    }

    @Test
    fun `parts take precedence over legacy partTypes for render projection`() {
        val message = ChatMessage(
            sender = MessageSender.AGENT,
            content = "println(1)",
            nodeId = "node-3",
            variantId = "variant-3",
            parts = listOf(
                UiMessagePart(type = "reasoning", text = "thinking"),
                UiMessagePart(type = "markdown_block", text = "hello **md**"),
            ),
            partTypes = listOf("code_block"),
        )

        val projection = message.renderProjection()

        assertEquals(ChatBodyMode.MARKDOWN, projection.bodyMode)
        assertEquals(listOf("思考", "Markdown"), projection.labels)
    }
}
