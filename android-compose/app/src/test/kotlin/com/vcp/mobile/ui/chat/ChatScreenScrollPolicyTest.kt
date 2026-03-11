package com.vcp.mobile.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenScrollPolicyTest {

    @Test
    fun `stick to bottom when list is still near bottom`() {
        assertTrue(shouldStickToBottom(lastVisibleIndex = 8, lastMessageIndex = 9))
    }

    @Test
    fun `do not force scroll when user is reading older messages`() {
        assertFalse(shouldStickToBottom(lastVisibleIndex = 5, lastMessageIndex = 9))
    }

    @Test
    fun `short conversations still auto stick`() {
        assertTrue(shouldStickToBottom(lastVisibleIndex = 0, lastMessageIndex = 1))
    }
}
