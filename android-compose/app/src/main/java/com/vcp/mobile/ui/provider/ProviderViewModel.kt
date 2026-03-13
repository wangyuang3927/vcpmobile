package com.vcp.mobile.ui.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vcp.mobile.data.network.HubBridgeError
import com.vcp.mobile.data.network.HubBridgeFailure
import com.vcp.mobile.data.network.HubProviderMutationResult
import com.vcp.mobile.data.repository.HubProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ProviderViewModel @Inject constructor(
    private val repository: HubProviderRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProviderEditorState())
    val state: StateFlow<ProviderEditorState> = _state.asStateFlow()

    private var editingBaseProvider: com.vcp.mobile.data.network.HubProviderConfig? = null

    init {
        refreshProviders(initialLoad = true)
    }

    fun refreshProviders(initialLoad: Boolean = false) {
        _state.update {
            it.copy(
                isLoadingProviders = true,
                errorMessage = if (initialLoad) null else it.errorMessage,
            )
        }
        viewModelScope.launch {
            runCatching { repository.listProviders() }
                .onSuccess { providers ->
                    _state.update {
                        it.copy(
                            providers = sortProviders(providers),
                            isLoadingProviders = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoadingProviders = false,
                            errorMessage = error.message ?: "加载 provider 列表失败",
                        )
                    }
                }
        }
    }

    fun startCreateFlow() {
        editingBaseProvider = null
        _state.update {
            it.copy(
                form = newProviderFormState(),
                isEditorLoading = false,
                isSaving = false,
                isDeleting = false,
                errorMessage = null,
                statusMessage = null,
            )
        }
    }

    fun startEditFlow(providerLocalId: String) {
        _state.update {
            it.copy(
                isEditorLoading = true,
                errorMessage = null,
                statusMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { repository.getProvider(providerLocalId) }
                .onSuccess { provider ->
                    editingBaseProvider = provider
                    _state.update {
                        it.copy(
                            form = provider.toProviderFormState(),
                            isEditorLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isEditorLoading = false,
                            errorMessage = error.message ?: "加载 provider 详情失败",
                        )
                    }
                }
        }
    }

    fun closeEditor() {
        editingBaseProvider = null
        _state.update {
            it.copy(
                form = null,
                isEditorLoading = false,
                isSaving = false,
                isDeleting = false,
                errorMessage = null,
            )
        }
    }

    fun clearMessage() {
        _state.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun onDisplayNameChanged(value: String) {
        updateForm { copy(displayName = value, displayNameError = null) }
    }

    fun onAvatarUriChanged(value: String) {
        updateForm { copy(avatarUri = value) }
    }

    fun onBaseUrlChanged(value: String) {
        updateForm { copy(baseUrl = value, baseUrlError = null) }
    }

    fun onAdapterKindChanged(value: com.vcp.mobile.data.network.HubProviderAdapterKind) {
        updateForm { copy(adapterKind = value) }
    }

    fun onAuthTypeChanged(value: com.vcp.mobile.data.network.HubProviderAuthType) {
        updateForm {
            copy(
                authType = value,
                authTokenError = null,
                authHeaderNameError = null,
                authValueError = null,
                authUsernameError = null,
                authPasswordError = null,
            )
        }
    }

    fun onAuthTokenChanged(value: String) {
        updateForm { copy(authToken = value, authTokenError = null) }
    }

    fun onAuthHeaderNameChanged(value: String) {
        updateForm { copy(authHeaderName = value, authHeaderNameError = null) }
    }

    fun onAuthValueChanged(value: String) {
        updateForm { copy(authValue = value, authValueError = null) }
    }

    fun onAuthUsernameChanged(value: String) {
        updateForm { copy(authUsername = value, authUsernameError = null) }
    }

    fun onAuthPasswordChanged(value: String) {
        updateForm { copy(authPassword = value, authPasswordError = null) }
    }

    fun onModelLinesChanged(value: String) {
        updateForm { copy(modelLines = value, modelLinesError = null, defaultModelError = null) }
    }

    fun onDefaultModelIdChanged(value: String) {
        updateForm { copy(defaultModelId = value, defaultModelError = null) }
    }

    fun saveProvider() {
        val form = _state.value.form ?: return
        val validatedForm = validate(form)
        if (validatedForm != form) {
            _state.update { it.copy(form = validatedForm) }
            if (hasValidationErrors(validatedForm)) {
                return
            }
        }

        _state.update { it.copy(isSaving = true, errorMessage = null, statusMessage = null) }

        viewModelScope.launch {
            val result = runCatching {
                when (form.mode) {
                    ProviderEditorMode.CREATE -> repository.createProvider(form.toNewProviderConfig())
                    ProviderEditorMode.EDIT -> {
                        val base = editingBaseProvider
                            ?: return@runCatching HubProviderMutationResult.Failure(
                                failure = HubBridgeFailure(
                                    statusCode = 409,
                                    statusMessage = "Missing base provider",
                                    error = HubBridgeError(
                                        kind = "state_error",
                                        code = "missing_provider_base",
                                        message = "编辑态缺少原始 provider 配置",
                                        retriable = false,
                                    )
                                )
                            )
                        repository.updateProvider(base.localId, base.withFormEdits(form))
                    }
                }
            }

            result
                .onSuccess { mutation -> handleMutationResult(mutation, form) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = error.message ?: "保存 provider 失败",
                        )
                    }
                }
        }
    }

    fun deleteProvider() {
        val form = _state.value.form ?: return
        if (form.isCreate) return
        _state.update { it.copy(isDeleting = true, errorMessage = null, statusMessage = null) }

        viewModelScope.launch {
            runCatching { repository.deleteProvider(form.providerLocalId) }
                .onSuccess { mutation ->
                    when (mutation) {
                        is HubProviderMutationResult.Success -> {
                            editingBaseProvider = null
                            _state.update { current ->
                                current.copy(
                                    providers = current.providers.filterNot {
                                        it.localId == mutation.provider.localId
                                    },
                                    form = null,
                                    isDeleting = false,
                                    statusMessage = "Provider 已删除",
                                )
                            }
                        }

                        is HubProviderMutationResult.Failure -> {
                            _state.update {
                                it.copy(
                                    isDeleting = false,
                                    errorMessage = mutation.failure.error.message,
                                )
                            }
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = error.message ?: "删除 provider 失败",
                        )
                    }
                }
        }
    }

    private fun handleMutationResult(
        mutation: HubProviderMutationResult,
        form: ProviderFormState,
    ) {
        when (mutation) {
            is HubProviderMutationResult.Success -> {
                editingBaseProvider = mutation.provider
                _state.update { current ->
                    val updatedProviders = current.providers
                        .filterNot { it.localId == mutation.provider.localId }
                        .plus(mutation.provider)
                    current.copy(
                        providers = sortProviders(updatedProviders),
                        isSaving = false,
                        form = null,
                        statusMessage = if (form.isCreate) "Provider 已创建" else "Provider 已更新",
                    )
                }
            }

            is HubProviderMutationResult.Failure -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = mutation.failure.error.message,
                    )
                }
            }
        }
    }

    private fun validate(form: ProviderFormState): ProviderFormState {
        var nextForm = form.copy(
            displayNameError = null,
            baseUrlError = null,
            authTokenError = null,
            authHeaderNameError = null,
            authValueError = null,
            authUsernameError = null,
            authPasswordError = null,
            modelLinesError = null,
            defaultModelError = null,
        )

        if (form.displayName.trim().isEmpty()) {
            nextForm = nextForm.copy(displayNameError = "名称不能为空")
        }

        val baseUrl = form.baseUrl.trim()
        if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            nextForm = nextForm.copy(baseUrlError = "Base URL 必须以 http:// 或 https:// 开头")
        }

        val models = parseModelLines(form.modelLines)
        if (models.isEmpty() || models.any { it.modelId.trim().isEmpty() }) {
            nextForm = nextForm.copy(
                modelLinesError = "至少填写一个模型，每行格式为 model_id 或 model_id | 展示名"
            )
        }

        val defaultModelId = form.defaultModelId.trim()
        if (defaultModelId.isNotEmpty() && models.none { it.modelId == defaultModelId }) {
            nextForm = nextForm.copy(defaultModelError = "默认模型必须存在于模型列表中")
        }

        nextForm = when (form.authType) {
            com.vcp.mobile.data.network.HubProviderAuthType.NONE -> nextForm
            com.vcp.mobile.data.network.HubProviderAuthType.BEARER_TOKEN -> {
                if (form.authToken.trim().isEmpty()) {
                    nextForm.copy(authTokenError = "Bearer Token 不能为空")
                } else {
                    nextForm
                }
            }
            com.vcp.mobile.data.network.HubProviderAuthType.API_KEY -> {
                var updated = nextForm
                if (form.authHeaderName.trim().isEmpty()) {
                    updated = updated.copy(authHeaderNameError = "Header 名称不能为空")
                }
                if (form.authValue.trim().isEmpty()) {
                    updated = updated.copy(authValueError = "API Key 不能为空")
                }
                updated
            }
            com.vcp.mobile.data.network.HubProviderAuthType.BASIC -> {
                var updated = nextForm
                if (form.authUsername.trim().isEmpty()) {
                    updated = updated.copy(authUsernameError = "用户名不能为空")
                }
                if (form.authPassword.isEmpty()) {
                    updated = updated.copy(authPasswordError = "密码不能为空")
                }
                updated
            }
        }

        return nextForm
    }

    private fun hasValidationErrors(form: ProviderFormState): Boolean {
        return listOf(
            form.displayNameError,
            form.baseUrlError,
            form.authTokenError,
            form.authHeaderNameError,
            form.authValueError,
            form.authUsernameError,
            form.authPasswordError,
            form.modelLinesError,
            form.defaultModelError,
        ).any { it != null }
    }

    private fun updateForm(transform: ProviderFormState.() -> ProviderFormState) {
        _state.update { current ->
            current.copy(form = current.form?.transform())
        }
    }
}
