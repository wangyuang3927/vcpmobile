package com.vcp.mobile.ui.pairing

import com.vcp.mobile.data.network.HubPairingExchangeFailureResponse
import com.vcp.mobile.data.network.HubPairingExchangeRequest
import com.vcp.mobile.data.network.HubPairingExchangeSuccessResponse

internal const val DEFAULT_PAIRING_DEVICE_NAME = "Android device"
internal const val DEFAULT_PAIRING_DEVICE_PUBLIC_KEY =
    "YW5kcm9pZC1jb21wb3NlLWRlYnVnLXB1YmxpYy1rZXk="

data class PairingFormState(
    val pairingSessionId: String = "",
    val namespace: String = "",
    val bootstrapToken: String = "",
    val deviceName: String = DEFAULT_PAIRING_DEVICE_NAME,
    val devicePublicKey: String = DEFAULT_PAIRING_DEVICE_PUBLIC_KEY,
    val pairingSessionIdError: String? = null,
    val namespaceError: String? = null,
    val bootstrapTokenError: String? = null,
    val deviceNameError: String? = null,
    val devicePublicKeyError: String? = null,
) {
    val canSubmit: Boolean = pairingSessionId.isNotBlank() &&
        namespace.isNotBlank() &&
        bootstrapToken.isNotBlank() &&
        deviceName.isNotBlank() &&
        devicePublicKey.isNotBlank()
}

data class PairingSuccessState(
    val pairingSessionId: String,
    val namespace: String,
    val trustedDeviceId: String,
    val trustedDeviceName: String,
    val trustedDevicePlatform: String,
    val accessTokenPreview: String,
    val tokenType: String,
    val tokenExpiresAt: String,
    val resumeAnchorPreview: String,
    val resumeAnchorExpiresAt: String,
)

data class PairingFailureState(
    val pairingSessionId: String?,
    val namespace: String?,
    val code: String,
    val message: String,
    val retriable: Boolean,
)

data class PairingUiState(
    val form: PairingFormState = PairingFormState(),
    val isSubmitting: Boolean = false,
    val successState: PairingSuccessState? = null,
    val failureState: PairingFailureState? = null,
    val statusMessage: String = "扫码后确认配对参数，再发起 mobile pairing exchange。",
) {
    val canSubmit: Boolean = !isSubmitting && form.canSubmit
}

internal fun PairingFormState.toRequest(): HubPairingExchangeRequest {
    return HubPairingExchangeRequest(
        pairingSessionId = pairingSessionId.trim(),
        namespace = namespace.trim(),
        bootstrapToken = bootstrapToken.trim(),
        deviceName = deviceName.trim(),
        devicePublicKey = devicePublicKey.trim(),
    )
}

internal fun HubPairingExchangeSuccessResponse.toPairingSuccessState(): PairingSuccessState {
    return PairingSuccessState(
        pairingSessionId = pairingSessionId,
        namespace = namespace,
        trustedDeviceId = trustedDevice.trustedDeviceId,
        trustedDeviceName = trustedDevice.deviceName,
        trustedDevicePlatform = trustedDevice.devicePlatform,
        accessTokenPreview = mobileToken.accessToken.redactedPreview(),
        tokenType = mobileToken.tokenType,
        tokenExpiresAt = mobileToken.expiresAt,
        resumeAnchorPreview = resumeAnchor.anchor.redactedPreview(),
        resumeAnchorExpiresAt = resumeAnchor.expiresAt,
    )
}

internal fun HubPairingExchangeFailureResponse.toPairingFailureState(): PairingFailureState {
    return PairingFailureState(
        pairingSessionId = pairingSessionId,
        namespace = namespace,
        code = error.code,
        message = error.message,
        retriable = error.retriable,
    )
}

private fun String.redactedPreview(): String {
    val trimmed = trim()
    if (trimmed.length <= 10) return trimmed
    return buildString {
        append(trimmed.take(6))
        append("…")
        append(trimmed.takeLast(4))
    }
}
