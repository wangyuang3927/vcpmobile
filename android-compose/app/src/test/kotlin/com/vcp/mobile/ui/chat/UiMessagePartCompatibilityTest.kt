package com.vcp.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiMessagePartCompatibilityTest {

    @Test
    fun `compatibility projection preserves ordered content and reasoning`() {
        val projection = listOf(
            UiMessagePart(type = "reasoning", text = "thinking"),
            UiMessagePart(type = "markdown_block", text = "hello **world**"),
            UiMessagePart(type = "code_block", text = "println(1)", language = "kotlin"),
            UiMessagePart(type = "text", text = " tail"),
        ).toCompatibilityProjection()

        assertEquals("thinking", projection.reasoning)
        assertEquals(
            "hello **world**```kotlin\nprintln(1)\n``` tail",
            projection.content
        )
        assertEquals(
            listOf("reasoning", "markdown_block", "code_block", "text"),
            projection.partTypes
        )
    }

    @Test
    fun `compatibility projection preserves code block whitespace without language`() {
        val projection = listOf(
            UiMessagePart(
                type = "code_block",
                text = "fun main() {\n    println(\"ok\")\n}",
            ),
        ).toCompatibilityProjection()

        assertEquals(
            "```\nfun main() {\n    println(\"ok\")\n}\n```",
            projection.content,
        )
        assertNull(projection.reasoning)
        assertEquals(listOf("code_block"), projection.partTypes)
    }

    @Test
    fun `compatibility projection returns empty defaults when no parts`() {
        val projection = emptyList<UiMessagePart>().toCompatibilityProjection()

        assertEquals("", projection.content)
        assertNull(projection.reasoning)
        assertEquals(emptyList<String>(), projection.partTypes)
    }

    @Test
    fun `renderer parts fall back to legacy fields when typed parts absent`() {
        val message = ChatMessage(
            sender = MessageSender.AGENT,
            content = "legacy body",
            reasoning = "legacy thinking",
            partTypes = listOf("markdown_block"),
        )

        assertEquals(
            listOf(
                UiMessagePart(type = "reasoning", text = "legacy thinking"),
                UiMessagePart(type = "markdown_block", text = "legacy body"),
            ),
            message.rendererParts()
        )
    }

    @Test
    fun `compatibility projection keeps typed image document tool and error summaries`() {
        val projection = listOf(
            UiMessagePart(
                type = "image",
                title = "cat preview",
                url = "https://cdn.example.com/cat.png",
                mime = "image/png",
            ),
            UiMessagePart(
                type = "document",
                title = "spec.pdf",
                url = "file:///spec.pdf",
                mime = "application/pdf",
            ),
            UiMessagePart(
                type = "tool",
                title = "search_web",
                state = "completed",
                text = "{\"items\":1}",
            ),
            UiMessagePart(type = "error", text = "upstream exploded"),
        ).toCompatibilityProjection()

        assertEquals(
            "cat preview\nhttps://cdn.example.com/cat.png\nimage/png" +
                "spec.pdf\nfile:///spec.pdf\napplication/pdf" +
                "search_web · completed\n{\"items\":1}" +
                "upstream exploded",
            projection.content
        )
        assertNull(projection.reasoning)
        assertEquals(
            listOf("image", "document", "tool", "error"),
            projection.partTypes
        )
    }
}
