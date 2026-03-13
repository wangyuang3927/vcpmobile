package com.vcp.mobile.ui.chat.catalog

import com.vcp.mobile.data.network.HubPromptPreviewRecord
import com.vcp.mobile.data.network.HubResolvedPromptPreview
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedPromptPreviewWorkbenchTest {

    @Test
    fun `project groups prompt provenance by status and formats source labels`() {
        val projection = ResolvedPromptPreviewWorkbench.project(
            PlaceholderPreviewFixtures.precedencePreview()
        )

        assertEquals("Analyst waves at {{sticker_wave}} from team room", projection.resolvedPrompt)
        assertEquals(listOf("char", "room"), projection.appliedRecords.map { it.key })
        assertEquals(listOf("char"), projection.shadowedRecords.map { it.key })
        assertEquals(listOf("sticker_wave"), projection.deferredRecords.map { it.key })
        assertEquals("Agent / Agent Profile", projection.appliedRecords.first().sourceLabel)
        assertEquals("Generic / Conversation", projection.appliedRecords[1].sourceLabel)
        assertEquals(
            "Sticker Media / Sticker Pack",
            projection.deferredRecords.first().sourceLabel
        )
        assertEquals(listOf("{{missing_value}}"), projection.unresolvedTokens)
        assertEquals(listOf("{{half"), projection.partialTokens)
    }

    @Test
    fun `project preserves unresolved and partial fixture tokens without provenance rows`() {
        val projection = ResolvedPromptPreviewWorkbench.project(
            PlaceholderPreviewFixtures.unresolvedPreview()
        )

        assertEquals("Hello {{missing}} {{broken", projection.resolvedPrompt)
        assertEquals(emptyList<String>(), projection.appliedRecords.map { it.key })
        assertEquals(listOf("{{missing}}"), projection.unresolvedTokens)
        assertEquals(listOf("{{broken"), projection.partialTokens)
    }
}

private object PlaceholderPreviewFixtures {

    fun precedencePreview(): HubResolvedPromptPreview {
        return HubResolvedPromptPreview(
            rawPrompt = "{{char}} waves at {{sticker_wave}} from {{room}}",
            resolvedPrompt = "Analyst waves at {{sticker_wave}} from team room",
            records = listOf(
                HubPromptPreviewRecord(
                    key = "char",
                    value = "Analyst",
                    category = "agent",
                    source = "agent_profile",
                    status = "applied",
                ),
                HubPromptPreviewRecord(
                    key = "room",
                    value = "team room",
                    category = "generic",
                    source = "conversation",
                    status = "applied",
                ),
                HubPromptPreviewRecord(
                    key = "char",
                    value = "Fallback",
                    category = "static",
                    source = "static_registry",
                    status = "shadowed",
                ),
                HubPromptPreviewRecord(
                    key = "sticker_wave",
                    value = ":wave:",
                    category = "sticker_media",
                    source = "sticker_pack",
                    status = "deferred",
                ),
            ),
            unresolvedTokens = listOf("{{missing_value}}"),
            partialTokens = listOf("{{half"),
        )
    }

    fun unresolvedPreview(): HubResolvedPromptPreview {
        return HubResolvedPromptPreview(
            rawPrompt = "Hello {{missing}} {{broken",
            resolvedPrompt = "Hello {{missing}} {{broken",
            unresolvedTokens = listOf("{{missing}}"),
            partialTokens = listOf("{{broken"),
        )
    }
}
