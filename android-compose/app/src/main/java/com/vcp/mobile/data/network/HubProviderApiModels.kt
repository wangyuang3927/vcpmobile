package com.vcp.mobile.data.network

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val DEFAULT_PROVIDER_API_KEY_HEADER = "Authorization"

enum class HubProviderAdapterKind(
    val wireName: String,
    val label: String,
) {
    OPENAI_COMPATIBLE("openai_compatible", "OpenAI Compatible"),
    GOOGLE_COMPATIBLE("google_compatible", "Google Compatible"),
    ANTHROPIC_COMPATIBLE("anthropic_compatible", "Anthropic Compatible"),
    VCPTOOLBOX("vcptoolbox", "VCPToolBox");

    companion object {
        fun fromWireName(value: String?): HubProviderAdapterKind {
            return entries.firstOrNull { it.wireName == value } ?: OPENAI_COMPATIBLE
        }
    }
}

enum class HubProviderAuthType(
    val wireName: String,
    val label: String,
) {
    NONE("none", "None"),
    BEARER_TOKEN("bearer_token", "Bearer Token"),
    API_KEY("api_key", "API Key"),
    BASIC("basic", "Basic Auth");

    companion object {
        fun fromWireName(value: String?): HubProviderAuthType {
            return entries.firstOrNull { it.wireName == value } ?: NONE
        }
    }
}

data class HubProviderHeader(
    val name: String,
    val value: String,
)

data class HubProviderBodyFragment(
    val pointer: String,
    val valueJson: String,
)

data class HubProviderModelCatalogEntry(
    val modelId: String,
    val displayName: String? = null,
    val enabled: Boolean = true,
)

data class HubProviderModelCatalog(
    val defaultModel: String? = null,
    val entries: List<HubProviderModelCatalogEntry> = emptyList(),
)

data class HubProviderPreset(
    val localId: String = "",
    val name: String,
    val description: String? = null,
    val modelId: String? = null,
    val headers: List<HubProviderHeader> = emptyList(),
    val bodyFragments: List<HubProviderBodyFragment> = emptyList(),
)

data class HubProviderAuthConfig(
    val type: HubProviderAuthType = HubProviderAuthType.NONE,
    val token: String = "",
    val headerName: String = DEFAULT_PROVIDER_API_KEY_HEADER,
    val value: String = "",
    val username: String = "",
    val password: String = "",
    val hasStoredSecret: Boolean = false,
    val hasStoredPassword: Boolean = false,
)

data class HubProviderConfig(
    val localId: String = "",
    val adapterKind: HubProviderAdapterKind = HubProviderAdapterKind.OPENAI_COMPATIBLE,
    val displayName: String,
    val avatarUri: String? = null,
    val baseUrl: String,
    val auth: HubProviderAuthConfig = HubProviderAuthConfig(),
    val modelCatalog: HubProviderModelCatalog = HubProviderModelCatalog(),
    val customHeaders: List<HubProviderHeader> = emptyList(),
    val customBodyFragments: List<HubProviderBodyFragment> = emptyList(),
    val presets: List<HubProviderPreset> = emptyList(),
    val defaultPresetLocalId: String? = null,
    val referenceAliases: List<String> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

sealed interface HubProviderMutationResult {
    data class Success(
        val provider: HubProviderConfig,
        val statusCode: Int,
    ) : HubProviderMutationResult

    data class Failure(
        val failure: HubBridgeFailure,
    ) : HubProviderMutationResult
}

internal fun HubProviderConfig.toUpstreamJsonBody(): String {
    return JSONObject()
        .put("local_id", localId)
        .put("adapter_kind", adapterKind.wireName)
        .put("display_name", displayName)
        .apply {
            avatarUri?.takeIf { it.isNotBlank() }?.let { put("avatar_uri", it) }
        }
        .put("base_url", baseUrl)
        .put("auth", auth.toJson())
        .put("model_catalog", modelCatalog.toJson())
        .put("custom_headers", JSONArray().apply {
            customHeaders.forEach { header ->
                put(
                    JSONObject()
                        .put("name", header.name)
                        .put("value", header.value)
                )
            }
        })
        .put("custom_body_fragments", JSONArray().apply {
            customBodyFragments.forEach { fragment ->
                put(
                    JSONObject()
                        .put("pointer", fragment.pointer)
                        .put("value", fragment.valueJson.toJsonValue())
                )
            }
        })
        .put("presets", JSONArray().apply {
            presets.forEach { preset ->
                put(
                    JSONObject()
                        .put("local_id", preset.localId)
                        .put("name", preset.name)
                        .apply {
                            preset.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                            preset.modelId?.takeIf { it.isNotBlank() }?.let { put("model_id", it) }
                        }
                        .put("headers", JSONArray().apply {
                            preset.headers.forEach { header ->
                                put(
                                    JSONObject()
                                        .put("name", header.name)
                                        .put("value", header.value)
                                )
                            }
                        })
                        .put("body_fragments", JSONArray().apply {
                            preset.bodyFragments.forEach { fragment ->
                                put(
                                    JSONObject()
                                        .put("pointer", fragment.pointer)
                                        .put("value", fragment.valueJson.toJsonValue())
                                )
                            }
                        })
                )
            }
        })
        .apply {
            defaultPresetLocalId?.takeIf { it.isNotBlank() }?.let { put("default_preset_local_id", it) }
        }
        .put("reference_aliases", JSONArray(referenceAliases))
        .toString()
}

private fun HubProviderAuthConfig.toJson(): JSONObject {
    return when (type) {
        HubProviderAuthType.NONE -> JSONObject().put("type", type.wireName)
        HubProviderAuthType.BEARER_TOKEN -> JSONObject()
            .put("type", type.wireName)
            .put("token", token)
        HubProviderAuthType.API_KEY -> JSONObject()
            .put("type", type.wireName)
            .put("header_name", headerName)
            .put("value", value)
        HubProviderAuthType.BASIC -> JSONObject()
            .put("type", type.wireName)
            .put("username", username)
            .put("password", password)
    }
}

private fun HubProviderModelCatalog.toJson(): JSONObject {
    return JSONObject()
        .apply {
            defaultModel?.takeIf { it.isNotBlank() }?.let { put("default_model", it) }
        }
        .put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("model_id", entry.modelId)
                        .apply {
                            entry.displayName?.takeIf { it.isNotBlank() }?.let { put("display_name", it) }
                        }
                        .put("enabled", entry.enabled)
                )
            }
        })
}

private fun String.toJsonValue(): Any? {
    return runCatching { JSONTokener(this).nextValue() }
        .getOrElse { this }
}
