package com.vcp.mobile.ui.chat.catalog

import com.vcp.mobile.ui.chat.ConversationCatalogFilter
import com.vcp.mobile.ui.chat.RecoverableConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ConversationCatalogWorkbenchTest {

    private val fixedNow: ZonedDateTime = ZonedDateTime.of(
        2026, 3, 11, 12, 0, 0, 0, ZoneId.of("UTC")
    )

    @Test
    fun `projection groups current recent older and surfaces quick switch subset`() {
        val projection = ConversationCatalogWorkbench.project(
            conversations = listOf(
                RecoverableConversation(
                    conversationId = "current",
                    title = "Current",
                    updatedAt = "2026-03-11T11:00:00Z",
                    generationState = "idle",
                    isCurrent = true,
                ),
                RecoverableConversation(
                    conversationId = "recent-failed",
                    title = "Recent failed",
                    updatedAt = "2026-03-10T11:00:00Z",
                    generationState = "failed",
                ),
                RecoverableConversation(
                    conversationId = "older",
                    title = "Older",
                    updatedAt = "2026-02-20T11:00:00Z",
                    generationState = "idle",
                ),
            ),
            activeConversationId = "current",
            query = "",
            filter = ConversationCatalogFilter.ALL,
            now = fixedNow,
        )

        assertEquals(3, projection.totalCount)
        assertEquals(3, projection.visibleCount)
        assertEquals("current", projection.activeConversation?.conversationId)
        assertEquals(
            listOf(
                ConversationCatalogSection.CURRENT,
                ConversationCatalogSection.RECENT,
                ConversationCatalogSection.OLDER,
            ),
            projection.groupedConversations.map { it.section }
        )
        assertEquals(
            listOf("recent-failed"),
            projection.quickSwitchConversations.map { it.conversationId }
        )
        assertNull(projection.emptyMessage)
    }

    @Test
    fun `projection returns search empty state when query hides all conversations`() {
        val projection = ConversationCatalogWorkbench.project(
            conversations = listOf(
                RecoverableConversation(
                    conversationId = "c1",
                    title = "Alpha",
                    updatedAt = "2026-03-11T11:00:00Z",
                    generationState = "idle",
                )
            ),
            activeConversationId = null,
            query = "Beta",
            filter = ConversationCatalogFilter.ALL,
            now = fixedNow,
        )

        assertEquals(1, projection.totalCount)
        assertEquals(0, projection.visibleCount)
        assertTrue(projection.emptyMessage?.contains("Beta") == true)
    }

    @Test
    fun `quick switch excludes current conversation and keeps only recent alternatives`() {
        val projection = ConversationCatalogWorkbench.project(
            conversations = listOf(
                RecoverableConversation(
                    conversationId = "current",
                    title = "Current",
                    updatedAt = "2026-03-11T11:00:00Z",
                    generationState = "idle",
                    isCurrent = true,
                ),
                RecoverableConversation(
                    conversationId = "recent",
                    title = "Recent",
                    updatedAt = "2026-03-10T11:00:00Z",
                    generationState = "idle",
                ),
                RecoverableConversation(
                    conversationId = "older",
                    title = "Older",
                    updatedAt = "2026-02-01T11:00:00Z",
                    generationState = "idle",
                ),
            ),
            activeConversationId = "current",
            query = "",
            filter = ConversationCatalogFilter.ALL,
            now = fixedNow,
        )

        assertEquals(listOf("recent"), projection.quickSwitchConversations.map { it.conversationId })
    }
}
