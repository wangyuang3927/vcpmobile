package com.vcp.mobile.ui.chat.catalog

import com.vcp.mobile.ui.chat.ConversationCatalogFilter
import com.vcp.mobile.ui.chat.RecoverableConversation
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class ConversationCatalogProjection(
    val totalCount: Int,
    val visibleCount: Int,
    val activeConversation: RecoverableConversation?,
    val groupedConversations: List<ConversationCatalogSectionGroup>,
    val quickSwitchConversations: List<RecoverableConversation>,
    val emptyMessage: String?,
)

data class ConversationCatalogSectionGroup(
    val section: ConversationCatalogSection,
    val conversations: List<RecoverableConversation>,
)

enum class ConversationCatalogSection(
    val label: String,
    val priority: Int,
) {
    CURRENT("当前会话", 4),
    RECENT("最近会话", 3),
    OLDER("更早", 1),
}

object ConversationCatalogWorkbench {

    fun project(
        conversations: List<RecoverableConversation>,
        activeConversationId: String?,
        query: String,
        filter: ConversationCatalogFilter,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): ConversationCatalogProjection {
        val filteredConversations = conversations
            .filter { conversation ->
                query.isBlank() || conversation.title.contains(query, ignoreCase = true)
            }
            .filter { conversation ->
                when (filter) {
                    ConversationCatalogFilter.ALL -> true
                    ConversationCatalogFilter.IDLE -> conversation.generationState.isIdleLikeGenerationState()
                    ConversationCatalogFilter.FAILED -> conversation.generationState.isFailureGenerationState()
                }
            }

        val sorted = filteredConversations.sortedWith(conversationCatalogComparator(now))
        val grouped = sorted
            .groupBy { conversation -> conversation.toConversationSection(now) }
            .toSortedMap(compareByDescending { it.priority })
            .map { (section, items) ->
                ConversationCatalogSectionGroup(
                    section = section,
                    conversations = items,
                )
            }

        val quickSwitchConversations = conversations
            .sortedWith(conversationCatalogComparator(now))
            .filter { conversation ->
                !conversation.isCurrent &&
                    conversation.toConversationSection(now) == ConversationCatalogSection.RECENT
            }

        val emptyMessage = when {
            filteredConversations.isNotEmpty() -> null
            conversations.isEmpty() -> "当前还没有可恢复的历史会话。"
            query.isNotBlank() -> "没有匹配“$query”的最近会话。"
            else -> "当前筛选条件下没有可恢复会话。"
        }

        return ConversationCatalogProjection(
            totalCount = conversations.size,
            visibleCount = filteredConversations.size,
            activeConversation = conversations.firstOrNull { it.conversationId == activeConversationId },
            groupedConversations = grouped,
            quickSwitchConversations = quickSwitchConversations,
            emptyMessage = emptyMessage,
        )
    }
}

fun RecoverableConversation.toConversationSection(
    now: ZonedDateTime = ZonedDateTime.now(),
): ConversationCatalogSection {
    if (isCurrent) return ConversationCatalogSection.CURRENT
    return updatedAt.toConversationSection(now)
}

fun String.toConversationSection(
    now: ZonedDateTime = ZonedDateTime.now(),
): ConversationCatalogSection {
    return runCatching {
        val instant = Instant.parse(this)
        val time = instant.atZone(ZoneId.systemDefault())
        when {
            time.isAfter(now.minusDays(7)) -> ConversationCatalogSection.RECENT
            else -> ConversationCatalogSection.OLDER
        }
    }.getOrDefault(ConversationCatalogSection.OLDER)
}

private fun conversationCatalogComparator(
    now: ZonedDateTime,
): Comparator<RecoverableConversation> {
    return compareByDescending<RecoverableConversation> { it.toConversationSection(now).priority }
        .thenByDescending { it.workflowPriority() }
        .thenByDescending { it.updatedAt.toEpochMillisOrZero() }
}

private fun RecoverableConversation.workflowPriority(): Int = when {
    isCurrent -> 3
    generationState.isFailureGenerationState() -> 2
    generationState.isActiveGenerationState() -> 1
    else -> 0
}

private fun String.normalizedGenerationState(): String = trim().lowercase()

private fun String.isActiveGenerationState(): Boolean = normalizedGenerationState() in setOf(
    "requesting",
    "started",
    "streaming",
)

private fun String.isFailureGenerationState(): Boolean = normalizedGenerationState() == "failed"

private fun String.isIdleLikeGenerationState(): Boolean = normalizedGenerationState() in setOf(
    "idle",
    "completed",
    "cancelled",
)

private fun String.toEpochMillisOrZero(): Long {
    return runCatching {
        Instant.parse(this).toEpochMilli()
    }.getOrDefault(0L)
}
