package com.vcp.mobile.ui.agent

import com.vcp.mobile.data.network.HubAgentConfig
import com.vcp.mobile.data.network.HubAgentIdentityConfig
import com.vcp.mobile.data.network.HubAgentPromptConfig
import java.util.UUID

enum class AgentEditorMode {
    CREATE,
    EDIT,
}

data class AgentFormState(
    val mode: AgentEditorMode,
    val agentId: String,
    val name: String = "",
    val avatarUri: String = "",
    val systemPrompt: String = "",
    val nameError: String? = null,
    val promptError: String? = null,
) {
    val isCreate: Boolean = mode == AgentEditorMode.CREATE
}

data class AgentEditorState(
    val agents: List<HubAgentConfig> = emptyList(),
    val isLoadingAgents: Boolean = false,
    val isEditorLoading: Boolean = false,
    val isSaving: Boolean = false,
    val form: AgentFormState? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

internal fun newAgentFormState(): AgentFormState {
    return AgentFormState(
        mode = AgentEditorMode.CREATE,
        agentId = UUID.randomUUID().toString(),
    )
}

internal fun HubAgentConfig.toAgentFormState(): AgentFormState {
    return AgentFormState(
        mode = AgentEditorMode.EDIT,
        agentId = id,
        name = identity.name,
        avatarUri = identity.avatarUri.orEmpty(),
        systemPrompt = prompt.systemPrompt,
    )
}

internal fun AgentFormState.toNewAgentConfig(): HubAgentConfig {
    return HubAgentConfig(
        id = agentId,
        identity = HubAgentIdentityConfig(
            name = name.trim(),
            avatarUri = avatarUri.trim().ifBlank { null },
        ),
        prompt = HubAgentPromptConfig(
            systemPrompt = systemPrompt.trim(),
        ),
    )
}

internal fun HubAgentConfig.withFormEdits(form: AgentFormState): HubAgentConfig {
    return copy(
        identity = identity.copy(
            name = form.name.trim(),
            avatarUri = form.avatarUri.trim().ifBlank { null },
        ),
        prompt = prompt.copy(
            systemPrompt = form.systemPrompt.trim(),
        ),
    )
}

internal fun sortAgents(agents: List<HubAgentConfig>): List<HubAgentConfig> {
    return agents.sortedWith(
        compareByDescending<HubAgentConfig> { it.updatedAt.orEmpty() }
            .thenBy { it.identity.name.lowercase() }
    )
}
