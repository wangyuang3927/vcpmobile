package com.vcp.mobile.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vcp.mobile.data.network.HubPairingExchangeResult
import com.vcp.mobile.data.repository.HubPairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: HubPairingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    fun onPairingSessionIdChanged(value: String) {
        updateForm { copy(pairingSessionId = value, pairingSessionIdError = null) }
    }

    fun onNamespaceChanged(value: String) {
        updateForm { copy(namespace = value, namespaceError = null) }
    }

    fun onBootstrapTokenChanged(value: String) {
        updateForm { copy(bootstrapToken = value, bootstrapTokenError = null) }
    }

    fun onDeviceNameChanged(value: String) {
        updateForm { copy(deviceName = value, deviceNameError = null) }
    }

    fun onDevicePublicKeyChanged(value: String) {
        updateForm { copy(devicePublicKey = value, devicePublicKeyError = null) }
    }

    fun clearResult() {
        _state.update {
            it.copy(
                successState = null,
                failureState = null,
                statusMessage = "扫码后确认配对参数，再发起 mobile pairing exchange。",
            )
        }
    }

    fun retryPairing() {
        submitPairing()
    }

    fun submitPairing() {
        val validatedForm = validateForm(_state.value.form)
        if (validatedForm != null) {
            _state.update { it.copy(form = validatedForm) }
            return
        }

        val request = _state.value.form.toRequest()
        _state.update {
            it.copy(
                isSubmitting = true,
                successState = null,
                failureState = null,
                statusMessage = "正在与 Hub bridge 交换 mobile token 并注册 trusted device…",
            )
        }

        viewModelScope.launch {
            runCatching { repository.exchangePairing(request) }
                .onSuccess { result -> handlePairingResult(result) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            failureState = PairingFailureState(
                                pairingSessionId = request.pairingSessionId,
                                namespace = request.namespace,
                                code = "pairing_exchange_transport_error",
                                message = error.message ?: "配对请求失败",
                                retriable = true,
                            ),
                            statusMessage = "配对失败，可直接重试或修改参数后再次提交。",
                        )
                    }
                }
        }
    }

    private fun handlePairingResult(result: HubPairingExchangeResult) {
        when (result) {
            is HubPairingExchangeResult.Success -> {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        successState = result.response.toPairingSuccessState(),
                        failureState = null,
                        statusMessage = "配对完成，mobile token 与 resume anchor 已返回到当前 UI 状态。",
                    )
                }
            }

            is HubPairingExchangeResult.Failure -> {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        successState = null,
                        failureState = result.response.toPairingFailureState(),
                        statusMessage = if (result.response.error.retriable) {
                            "配对被拒绝，但可直接重试。"
                        } else {
                            "配对被拒绝，请检查扫码结果或重新扫描后再试。"
                        },
                    )
                }
            }
        }
    }

    private fun updateForm(transform: PairingFormState.() -> PairingFormState) {
        _state.update { current ->
            current.copy(form = current.form.transform())
        }
    }

    private fun validateForm(form: PairingFormState): PairingFormState? {
        var hasError = false
        var nextForm = form

        if (form.pairingSessionId.isBlank()) {
            hasError = true
            nextForm = nextForm.copy(pairingSessionIdError = "扫码结果缺少会话 ID")
        }
        if (form.namespace.isBlank()) {
            hasError = true
            nextForm = nextForm.copy(namespaceError = "扫码结果缺少 namespace")
        }
        if (form.bootstrapToken.isBlank()) {
            hasError = true
            nextForm = nextForm.copy(bootstrapTokenError = "扫码结果缺少 bootstrap token")
        }
        if (form.deviceName.isBlank()) {
            hasError = true
            nextForm = nextForm.copy(deviceNameError = "设备名称不能为空")
        }
        if (form.devicePublicKey.isBlank()) {
            hasError = true
            nextForm = nextForm.copy(devicePublicKeyError = "设备公钥不能为空")
        }

        return nextForm.takeIf { hasError }
    }
}
