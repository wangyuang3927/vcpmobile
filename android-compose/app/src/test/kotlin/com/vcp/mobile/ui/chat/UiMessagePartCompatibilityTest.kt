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
}
