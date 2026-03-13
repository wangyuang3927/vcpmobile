package com.vcp.mobile.data.network

data class HubAgentIdentityConfig(
    val name: String,
    val avatarUri: String? = null,
    val description: String? = null,
)

data class HubAgentPromptVariable(
    val key: String,
    val label: String? = null,
    val value: String,
    val description: String? = null,
)

data class HubAgentPromptConfig(
    val systemPrompt: String = "",
    val promptMode: String = "system_only",
    val messageTemplate: String? = null,
    val placeholders: List<HubAgentPromptVariable> = emptyList(),
)

data class HubAgentModelConfig(
    val providerLocalId: String? = null,
    val presetLocalId: String? = null,
    val modelId: String? = null,
)

data class HubAgentRequestConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxOutputTokens: Int? = null,
    val reasoningEffort: String? = null,
)

data class HubAgentMemoryConfig(
    val useConversationMemory: Boolean = true,
    val pinTopLevelFacts: Boolean = false,
)

data class HubAgentToolPermission(
    val toolId: String,
    val enabled: Boolean = true,
)

data class HubAgentToolConfig(
    val enableLocalTools: Boolean = true,
    val overrides: List<HubAgentToolPermission> = emptyList(),
)

data class HubAgentGroupConfig(
    val roleLabel: String? = null,
    val aliases: List<String> = emptyList(),
    val mentionTags: List<String> = emptyList(),
    val respondToMentions: Boolean = true,
    val allowAutoRelay: Boolean = false,
)

data class HubAgentConfig(
    val id: String,
    val identity: HubAgentIdentityConfig,
    val prompt: HubAgentPromptConfig = HubAgentPromptConfig(),
    val model: HubAgentModelConfig = HubAgentModelConfig(),
    val request: HubAgentRequestConfig = HubAgentRequestConfig(),
    val memory: HubAgentMemoryConfig = HubAgentMemoryConfig(),
    val tools: HubAgentToolConfig = HubAgentToolConfig(),
    val group: HubAgentGroupConfig = HubAgentGroupConfig(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class HubBridgeError(
    val kind: String,
    val code: String? = null,
    val message: String,
    val retriable: Boolean = false,
)

data class HubBridgeFailure(
    val statusCode: Int,
    val statusMessage: String,
    val error: HubBridgeError,
)

sealed interface HubAgentMutationResult {
    data class Success(
        val agent: HubAgentConfig,
        val statusCode: Int,
    ) : HubAgentMutationResult

    data class Failure(
        val failure: HubBridgeFailure,
    ) : HubAgentMutationResult
}

class HubBridgeFailureException(
    val failure: HubBridgeFailure,
) : IllegalStateException(
    "${failure.statusCode} ${failure.statusMessage}: ${failure.error.kind}/${failure.error.code ?: "unknown"} ${failure.error.message}"
)
