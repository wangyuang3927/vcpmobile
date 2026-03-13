package com.vcp.mobile.ui.chat.catalog

import com.vcp.mobile.data.network.HubPromptPreviewRecord
import com.vcp.mobile.data.network.HubResolvedPromptPreview
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedPromptPreviewWorkbenchTest {

    @Test
    fun `project groups prompt provenance by status and formats source labels`() {
        val projection = ResolvedPromptPreviewWorkbench.project(
            HubResolvedPromptPreview(
                rawPrompt = "{{char}} waves at {{sticker_wave}}",
                resolvedPrompt = "Analyst waves at {{sticker_wave}}",
                records = listOf(
                    HubPromptPreviewRecord(
                        key = "char",
                        value = "Analyst",
                        category = "agent",
                        source = "agent_profile",
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
        )

        assertEquals("Analyst waves at {{sticker_wave}}", projection.resolvedPrompt)
        assertEquals(listOf("char"), projection.appliedRecords.map { it.key })
        assertEquals(listOf("char"), projection.shadowedRecords.map { it.key })
        assertEquals(listOf("sticker_wave"), projection.deferredRecords.map { it.key })
        assertEquals("Agent / Agent Profile", projection.appliedRecords.first().sourceLabel)
        assertEquals(
            "Sticker Media / Sticker Pack",
            projection.deferredRecords.first().sourceLabel
        )
        assertEquals(listOf("{{missing_value}}"), projection.unresolvedTokens)
        assertEquals(listOf("{{half"), projection.partialTokens)
    }
}
