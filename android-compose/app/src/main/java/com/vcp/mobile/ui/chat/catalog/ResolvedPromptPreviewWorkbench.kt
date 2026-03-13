package com.vcp.mobile.ui.chat.catalog

import com.vcp.mobile.data.network.HubPromptPreviewRecord
import com.vcp.mobile.data.network.HubResolvedPromptPreview
import java.util.Locale

data class ResolvedPromptPreviewProjection(
    val rawPrompt: String,
    val resolvedPrompt: String,
    val appliedRecords: List<ResolvedPromptProvenanceRow>,
    val shadowedRecords: List<ResolvedPromptProvenanceRow>,
    val deferredRecords: List<ResolvedPromptProvenanceRow>,
    val unresolvedTokens: List<String>,
    val partialTokens: List<String>,
)

data class ResolvedPromptProvenanceRow(
    val key: String,
    val value: String,
    val category: String,
    val source: String,
    val status: String,
    val sourceLabel: String,
)

object ResolvedPromptPreviewWorkbench {

    fun project(preview: HubResolvedPromptPreview): ResolvedPromptPreviewProjection {
        val rows = preview.records.map { record -> record.toProvenanceRow() }
        return ResolvedPromptPreviewProjection(
            rawPrompt = preview.rawPrompt,
            resolvedPrompt = preview.resolvedPrompt,
            appliedRecords = rows.filter { it.status == STATUS_APPLIED },
            shadowedRecords = rows.filter { it.status == STATUS_SHADOWED },
            deferredRecords = rows.filter { it.status == STATUS_DEFERRED },
            unresolvedTokens = preview.unresolvedTokens,
            partialTokens = preview.partialTokens,
        )
    }

    private fun HubPromptPreviewRecord.toProvenanceRow(): ResolvedPromptProvenanceRow {
        return ResolvedPromptProvenanceRow(
            key = key,
            value = value,
            category = category,
            source = source,
            status = status.normalizedToken(),
            sourceLabel = "${category.toDisplayLabel()} / ${source.toDisplayLabel()}",
        )
    }
}

private const val STATUS_APPLIED = "applied"
private const val STATUS_SHADOWED = "shadowed"
private const val STATUS_DEFERRED = "deferred"

private fun String.normalizedToken(): String = trim().lowercase(Locale.ROOT)

private fun String.toDisplayLabel(): String {
    return normalizedToken()
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
}
