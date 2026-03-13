package com.vcp.mobile.ui.provider

import com.vcp.mobile.data.network.HubProviderAdapterKind
import com.vcp.mobile.data.network.HubProviderAuthConfig
import com.vcp.mobile.data.network.HubProviderAuthType
import com.vcp.mobile.data.network.HubProviderConfig
import com.vcp.mobile.data.network.HubProviderModelCatalog
import com.vcp.mobile.data.network.HubProviderModelCatalogEntry

enum class ProviderEditorMode {
    CREATE,
    EDIT,
}

data class ProviderFormState(
    val mode: ProviderEditorMode,
    val providerLocalId: String = "",
    val displayName: String = "",
    val avatarUri: String = "",
    val baseUrl: String = "",
    val adapterKind: HubProviderAdapterKind = HubProviderAdapterKind.OPENAI_COMPATIBLE,
    val authType: HubProviderAuthType = HubProviderAuthType.NONE,
    val authToken: String = "",
    val authHeaderName: String = "Authorization",
    val authValue: String = "",
    val authUsername: String = "",
    val authPassword: String = "",
    val hasStoredSecret: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val modelLines: String = "",
    val defaultModelId: String = "",
    val displayNameError: String? = null,
    val baseUrlError: String? = null,
    val authTokenError: String? = null,
    val authHeaderNameError: String? = null,
    val authValueError: String? = null,
    val authUsernameError: String? = null,
    val authPasswordError: String? = null,
    val modelLinesError: String? = null,
    val defaultModelError: String? = null,
) {
    val isCreate: Boolean = mode == ProviderEditorMode.CREATE
}

data class ProviderEditorState(
    val providers: List<HubProviderConfig> = emptyList(),
    val isLoadingProviders: Boolean = false,
    val isEditorLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val form: ProviderFormState? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

internal fun newProviderFormState(): ProviderFormState {
    return ProviderFormState(
        mode = ProviderEditorMode.CREATE,
    )
}

internal fun HubProviderConfig.toProviderFormState(): ProviderFormState {
    return ProviderFormState(
        mode = ProviderEditorMode.EDIT,
        providerLocalId = localId,
        displayName = displayName,
        avatarUri = avatarUri.orEmpty(),
        baseUrl = baseUrl,
        adapterKind = adapterKind,
        authType = auth.type,
        authHeaderName = auth.headerName,
        authUsername = auth.username,
        hasStoredSecret = auth.hasStoredSecret,
        hasStoredPassword = auth.hasStoredPassword,
        modelLines = modelCatalog.entries.joinToString(separator = "\n") { entry ->
            listOfNotNull(
                entry.modelId.takeIf { it.isNotBlank() },
                entry.displayName?.takeIf { it.isNotBlank() },
            ).joinToString(" | ")
        },
        defaultModelId = modelCatalog.defaultModel.orEmpty(),
    )
}

internal fun ProviderFormState.toNewProviderConfig(): HubProviderConfig {
    val models = parseModelLines(modelLines)
    return HubProviderConfig(
        localId = providerLocalId.trim(),
        adapterKind = adapterKind,
        displayName = displayName.trim(),
        avatarUri = avatarUri.trim().ifBlank { null },
        baseUrl = baseUrl.trim(),
        auth = buildAuthConfig(),
        modelCatalog = HubProviderModelCatalog(
            defaultModel = defaultModelId.trim().ifBlank { null },
            entries = models,
        ),
    )
}

internal fun HubProviderConfig.withFormEdits(form: ProviderFormState): HubProviderConfig {
    val models = parseModelLines(form.modelLines)
    return copy(
        adapterKind = form.adapterKind,
        displayName = form.displayName.trim(),
        avatarUri = form.avatarUri.trim().ifBlank { null },
        baseUrl = form.baseUrl.trim(),
        auth = form.buildAuthConfig(),
        modelCatalog = HubProviderModelCatalog(
            defaultModel = form.defaultModelId.trim().ifBlank { null },
            entries = models,
        ),
        customHeaders = customHeaders.map { it.copy() },
        customBodyFragments = customBodyFragments.map { it.copy() },
        presets = presets.map { preset ->
            preset.copy(
                headers = preset.headers.map { it.copy() },
                bodyFragments = preset.bodyFragments.map { it.copy() },
            )
        },
        referenceAliases = referenceAliases.toList(),
    )
}

internal fun sortProviders(providers: List<HubProviderConfig>): List<HubProviderConfig> {
    return providers.sortedWith(
        compareByDescending<HubProviderConfig> { it.updatedAt.orEmpty() }
            .thenBy { it.displayName.lowercase() }
    )
}

internal fun ProviderFormState.resolvePreferredModelId(): String? {
    val models = parseModelLines(modelLines)
    val preferred = defaultModelId.trim().takeIf { it.isNotEmpty() }
    if (preferred != null && models.any { it.modelId == preferred }) {
        return preferred
    }
    return models.firstOrNull()?.modelId
}

internal fun HubProviderConfig.resolvePreferredModelId(): String? {
    val preferred = modelCatalog.defaultModel?.takeIf { it.isNotBlank() }
    if (preferred != null && modelCatalog.entries.any { it.modelId == preferred }) {
        return preferred
    }
    return modelCatalog.entries.firstOrNull { it.enabled && it.modelId.isNotBlank() }?.modelId
        ?: modelCatalog.entries.firstOrNull { it.modelId.isNotBlank() }?.modelId
}

internal fun parseModelLines(raw: String): List<HubProviderModelCatalogEntry> {
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val pieces = line.split('|', limit = 2).map { it.trim() }
            HubProviderModelCatalogEntry(
                modelId = pieces.firstOrNull().orEmpty(),
                displayName = pieces.getOrNull(1)?.takeIf { it.isNotBlank() },
                enabled = true,
            )
        }
        .toList()
}

private fun ProviderFormState.buildAuthConfig(): HubProviderAuthConfig {
    return when (authType) {
        HubProviderAuthType.NONE -> HubProviderAuthConfig(type = authType)
        HubProviderAuthType.BEARER_TOKEN -> HubProviderAuthConfig(
            type = authType,
            token = authToken.trim(),
            hasStoredSecret = hasStoredSecret,
        )
        HubProviderAuthType.API_KEY -> HubProviderAuthConfig(
            type = authType,
            headerName = authHeaderName.trim().ifBlank { "Authorization" },
            value = authValue.trim(),
            hasStoredSecret = hasStoredSecret,
        )
        HubProviderAuthType.BASIC -> HubProviderAuthConfig(
            type = authType,
            username = authUsername.trim(),
            password = authPassword,
            hasStoredPassword = hasStoredPassword,
        )
    }
}
