package com.vcp.mobile.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    modifier: Modifier = Modifier,
    onOpenChat: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PairingTopBar()
        PairingStatusBanner(
            text = state.statusMessage,
            onDismiss = viewModel::clearResult,
            showDismiss = state.successState != null || state.failureState != null,
        )
        PairingIntroCard()
        PairingFormCard(
            form = state.form,
            canSubmit = state.canSubmit,
            isSubmitting = state.isSubmitting,
            onPairingSessionIdChanged = viewModel::onPairingSessionIdChanged,
            onNamespaceChanged = viewModel::onNamespaceChanged,
            onBootstrapTokenChanged = viewModel::onBootstrapTokenChanged,
            onDeviceNameChanged = viewModel::onDeviceNameChanged,
            onDevicePublicKeyChanged = viewModel::onDevicePublicKeyChanged,
            onSubmitClicked = viewModel::submitPairing,
        )
        state.successState?.let { success ->
            PairingSuccessCard(
                success = success,
                onOpenChat = onOpenChat,
                onReset = viewModel::clearResult,
            )
        }
        state.failureState?.let { failure ->
            PairingFailureCard(
                failure = failure,
                isSubmitting = state.isSubmitting,
                onRetry = viewModel::retryPairing,
                onReset = viewModel::clearResult,
            )
        }
    }
}

@Composable
private fun PairingTopBar() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "设备配对",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "承接扫码后的 mobile pairing exchange，明确展示成功、失败和重试路径。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairingStatusBanner(
    text: String,
    onDismiss: () -> Unit,
    showDismiss: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (showDismiss) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭配对结果",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingIntroCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "扫码结果确认",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "当前版本先用表单承接扫码结果。确认 session、namespace 和 bootstrap token 后，直接在手机上完成 exchange。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "成功时会展示 trusted device 与 resume anchor；失败时会保留错误码并给出重试入口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PairingFormCard(
    form: PairingFormState,
    canSubmit: Boolean,
    isSubmitting: Boolean,
    onPairingSessionIdChanged: (String) -> Unit,
    onNamespaceChanged: (String) -> Unit,
    onBootstrapTokenChanged: (String) -> Unit,
    onDeviceNameChanged: (String) -> Unit,
    onDevicePublicKeyChanged: (String) -> Unit,
    onSubmitClicked: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Pairing payload",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = form.pairingSessionId,
                onValueChange = onPairingSessionIdChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Pairing session ID") },
                singleLine = true,
                enabled = !isSubmitting,
                isError = form.pairingSessionIdError != null,
                supportingText = {
                    Text(text = form.pairingSessionIdError ?: "桌面桥接生成的会话身份。")
                },
            )
            OutlinedTextField(
                value = form.namespace,
                onValueChange = onNamespaceChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Namespace") },
                singleLine = true,
                enabled = !isSubmitting,
                isError = form.namespaceError != null,
                supportingText = {
                    Text(text = form.namespaceError ?: "用于隔离当前工作区或后端上下文。")
                },
            )
            OutlinedTextField(
                value = form.bootstrapToken,
                onValueChange = onBootstrapTokenChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Bootstrap token") },
                enabled = !isSubmitting,
                isError = form.bootstrapTokenError != null,
                supportingText = {
                    Text(text = form.bootstrapTokenError ?: "扫码后拿到的一次性或短期凭证。")
                },
            )
            OutlinedTextField(
                value = form.deviceName,
                onValueChange = onDeviceNameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Device name") },
                singleLine = true,
                enabled = !isSubmitting,
                isError = form.deviceNameError != null,
                supportingText = {
                    Text(text = form.deviceNameError ?: "配对成功后，这个名称会显示在 trusted device 中。")
                },
            )
            OutlinedTextField(
                value = form.devicePublicKey,
                onValueChange = onDevicePublicKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Device public key") },
                enabled = !isSubmitting,
                isError = form.devicePublicKeyError != null,
                supportingText = {
                    Text(text = form.devicePublicKeyError ?: "当前 phase 1 用设备公钥占位承接 trusted-device 注册。")
                },
                maxLines = 3,
            )
            Button(
                onClick = onSubmitClicked,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = if (isSubmitting) "正在配对…" else "发起配对交换")
            }
        }
    }
}

@Composable
private fun PairingSuccessCard(
    success: PairingSuccessState,
    onOpenChat: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "配对成功",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "trusted device 已注册，可直接切回聊天继续使用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            PairingKeyValue("Session", success.pairingSessionId, MaterialTheme.colorScheme.onTertiaryContainer)
            PairingKeyValue("Namespace", success.namespace, MaterialTheme.colorScheme.onTertiaryContainer)
            PairingKeyValue(
                "Trusted device",
                "${success.trustedDeviceName} · ${success.trustedDevicePlatform}",
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
            PairingKeyValue("Trusted device ID", success.trustedDeviceId, MaterialTheme.colorScheme.onTertiaryContainer)
            PairingKeyValue(
                "Token",
                "${success.tokenType} ${success.accessTokenPreview}",
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
            PairingKeyValue("Token expires", success.tokenExpiresAt, MaterialTheme.colorScheme.onTertiaryContainer)
            PairingKeyValue("Resume anchor", success.resumeAnchorPreview, MaterialTheme.colorScheme.onTertiaryContainer)
            PairingKeyValue("Resume expires", success.resumeAnchorExpiresAt, MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenChat) {
                    Text(text = "进入聊天")
                }
                OutlinedButton(onClick = onReset) {
                    Text(text = "清除结果")
                }
            }
        }
    }
}

@Composable
private fun PairingFailureCard(
    failure: PairingFailureState,
    isSubmitting: Boolean,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "配对失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = failure.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            PairingKeyValue("Error code", failure.code, MaterialTheme.colorScheme.onErrorContainer)
            failure.pairingSessionId?.let {
                PairingKeyValue("Session", it, MaterialTheme.colorScheme.onErrorContainer)
            }
            failure.namespace?.let {
                PairingKeyValue("Namespace", it, MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(
                text = if (failure.retriable) {
                    "当前失败允许直接重试。"
                } else {
                    "当前失败不建议盲重试，优先重新扫码确认 token 是否过期。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onRetry,
                    enabled = !isSubmitting,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "重试配对")
                }
                OutlinedButton(onClick = onReset, enabled = !isSubmitting) {
                    Text(text = "关闭结果")
                }
            }
        }
    }
}

@Composable
private fun PairingKeyValue(
    label: String,
    value: String,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.72f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )
    }
}
