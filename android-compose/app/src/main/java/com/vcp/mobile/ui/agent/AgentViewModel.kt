package com.vcp.mobile.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vcp.mobile.data.network.HubAgentConfig
import com.vcp.mobile.data.network.HubAgentMutationResult
import com.vcp.mobile.data.repository.HubAgentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val repository: HubAgentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentEditorState())
    val state: StateFlow<AgentEditorState> = _state.asStateFlow()

    private var editingBaseAgent: HubAgentConfig? = null

    init {
        refreshAgents(initialLoad = true)
    }

    fun refreshAgents(initialLoad: Boolean = false) {
        _state.update {
            it.copy(
                isLoadingAgents = true,
                errorMessage = if (initialLoad) null else it.errorMessage,
            )
        }
        viewModelScope.launch {
            runCatching { repository.listAgents() }
                .onSuccess { agents ->
                    _state.update {
                        it.copy(
                            agents = sortAgents(agents),
                            isLoadingAgents = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoadingAgents = false,
                            errorMessage = error.message ?: "加载 agent 列表失败",
                        )
                    }
                }
        }
    }

    fun startCreateFlow() {
        editingBaseAgent = null
        _state.update {
            it.copy(
                form = newAgentFormState(),
                isEditorLoading = false,
                isSaving = false,
                errorMessage = null,
                statusMessage = null,
            )
        }
    }

    fun startEditFlow(agentId: String) {
        _state.update {
            it.copy(
                isEditorLoading = true,
                errorMessage = null,
                statusMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { repository.getAgent(agentId) }
                .onSuccess { agent ->
                    editingBaseAgent = agent
                    _state.update {
                        it.copy(
                            form = agent.toAgentFormState(),
                            isEditorLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isEditorLoading = false,
                            errorMessage = error.message ?: "加载 agent 详情失败",
                        )
                    }
                }
        }
    }

    fun closeEditor() {
        editingBaseAgent = null
        _state.update {
            it.copy(
                form = null,
                isEditorLoading = false,
                isSaving = false,
                errorMessage = null,
            )
        }
    }

    fun onNameChanged(value: String) {
        updateForm { copy(name = value, nameError = null) }
    }

    fun onAvatarUriChanged(value: String) {
        updateForm { copy(avatarUri = value) }
    }

    fun onSystemPromptChanged(value: String) {
        updateForm { copy(systemPrompt = value, promptError = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun saveAgent() {
        val form = _state.value.form ?: return
        val name = form.name.trim()
        val systemPrompt = form.systemPrompt.trim()

        var hasError = false
        var nextForm = form
        if (name.isEmpty()) {
            hasError = true
            nextForm = nextForm.copy(nameError = "名称不能为空")
        }
        if (systemPrompt.isEmpty()) {
            hasError = true
            nextForm = nextForm.copy(promptError = "System prompt 不能为空")
        }
        if (hasError) {
            _state.update { it.copy(form = nextForm) }
            return
        }

        _state.update { it.copy(isSaving = true, errorMessage = null, statusMessage = null) }

        viewModelScope.launch {
            val result = runCatching {
                when (form.mode) {
                    AgentEditorMode.CREATE -> repository.createAgent(form.toNewAgentConfig())
                    AgentEditorMode.EDIT -> {
                        val base = editingBaseAgent
                            ?: return@runCatching HubAgentMutationResult.Failure(
                                failure = com.vcp.mobile.data.network.HubBridgeFailure(
                                    statusCode = 409,
                                    statusMessage = "Missing base agent",
                                    error = com.vcp.mobile.data.network.HubBridgeError(
                                        kind = "state_error",
                                        code = "missing_agent_base",
                                        message = "编辑态缺少原始 agent 配置",
                                        retriable = false,
                                    )
                                )
                            )
                        repository.updateAgent(base.id, base.withFormEdits(form))
                    }
                }
            }

            result
                .onSuccess { mutation -> handleMutationResult(mutation, form) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = error.message ?: "保存 agent 失败",
                        )
                    }
                }
        }
    }

    private fun handleMutationResult(
        mutation: HubAgentMutationResult,
        form: AgentFormState,
    ) {
        when (mutation) {
            is HubAgentMutationResult.Success -> {
                editingBaseAgent = mutation.agent
                _state.update { current ->
                    val updatedAgents = current.agents
                        .filterNot { it.id == mutation.agent.id }
                        .plus(mutation.agent)
                    current.copy(
                        agents = sortAgents(updatedAgents),
                        isSaving = false,
                        form = null,
                        statusMessage = if (form.isCreate) "Agent 已创建" else "Agent 已更新",
                    )
                }
            }

            is HubAgentMutationResult.Failure -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = mutation.failure.error.message,
                    )
                }
            }
        }
    }

    private fun updateForm(transform: AgentFormState.() -> AgentFormState) {
        _state.update { current ->
            current.copy(form = current.form?.transform())
        }
    }
}
