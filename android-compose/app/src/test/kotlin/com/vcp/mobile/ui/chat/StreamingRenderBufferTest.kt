package com.vcp.mobile.ui.chat

import com.vcp.mobile.data.network.AstStreamNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingRenderBufferTest {

    @Test
    fun `buffer keeps ast only for active message key`() {
        val buffer = StreamingRenderBuffer()

        val doc = buffer.appendAst(
            messageKey = "node-1:variant-1",
            incoming = listOf(
                AstStreamNode(type = "paragraph", text = "hello ast")
            )
        )

        assertEquals(1, doc?.nodes?.size)
        assertEquals(1, buffer.currentDocumentFor("node-1:variant-1")?.nodes?.size)
        assertNull(buffer.currentDocumentFor("node-2:variant-2"))
    }

    @Test
    fun `buffer resets when message key changes`() {
        val buffer = StreamingRenderBuffer()
        buffer.appendAst(
            messageKey = "node-1:variant-1",
            incoming = listOf(AstStreamNode(type = "paragraph", text = "first"))
        )

        buffer.onMessageKeyChanged("node-2:variant-2")

        assertNull(buffer.currentDocumentFor("node-1:variant-1"))
        assertNull(buffer.currentDocumentFor("node-2:variant-2"))
    }
}
