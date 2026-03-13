package com.vcp.mobile.ui.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcp.mobile.data.network.HubProviderAdapterKind
import com.vcp.mobile.data.network.HubProviderAuthType
import com.vcp.mobile.data.network.HubProviderConfig

@Composable
fun ProviderScreen(
    viewModel: ProviderViewModel,
    activeProviderLocalId: String?,
    onSetActiveProvider: (providerLocalId: String, modelId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val form = state.form

    BackHandler(enabled = form != null) {
        viewModel.closeEditor()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProviderTopBar(
            form = form,
            activeProviderLocalId = activeProviderLocalId,
            onRefreshClicked = { viewModel.refreshProviders() },
            onCreateClicked = viewModel::startCreateFlow,
            onBackClicked = viewModel::closeEditor,
        )

        state.errorMessage?.let { message ->
            ProviderFeedbackBanner(
                text = message,
                isError = true,
                onDismiss = viewModel::clearMessage,
            )
        }
        state.statusMessage?.let { message ->
            ProviderFeedbackBanner(
                text = message,
                isError = false,
                onDismiss = viewModel::clearMessage,
            )
        }

        when {
            state.isEditorLoading -> {
                ProviderLoadingState(message = "正在加载 provider 配置…")
            }

            form != null -> {
                ProviderEditorPanel(
                    form = form,
                    isSaving = state.isSaving,
                    isDeleting = state.isDeleting,
                    onDisplayNameChanged = viewModel::onDisplayNameChanged,
                    onAvatarUriChanged = viewModel::onAvatarUriChanged,
                    onBaseUrlChanged = viewModel::onBaseUrlChanged,
                    onAdapterKindChanged = viewModel::onAdapterKindChanged,
                    onAuthTypeChanged = viewModel::onAuthTypeChanged,
                    onAuthTokenChanged = viewModel::onAuthTokenChanged,
                    onAuthHeaderNameChanged = viewModel::onAuthHeaderNameChanged,
                    onAuthValueChanged = viewModel::onAuthValueChanged,
                    onAuthUsernameChanged = viewModel::onAuthUsernameChanged,
                    onAuthPasswordChanged = viewModel::onAuthPasswordChanged,
                    onModelLinesChanged = viewModel::onModelLinesChanged,
                    onDefaultModelIdChanged = viewModel::onDefaultModelIdChanged,
                    onSaveClicked = viewModel::saveProvider,
                    onDeleteClicked = viewModel::deleteProvider,
                    onCancelClicked = viewModel::closeEditor,
                    onActivateClicked = {
                        form.resolvePreferredModelId()?.let { modelId ->
                            onSetActiveProvider(form.providerLocalId, modelId)
                        }
                    },
                    canActivate = !form.isCreate && form.resolvePreferredModelId() != null,
                    isActive = !form.isCreate && form.providerLocalId == activeProviderLocalId,
                )
            }

            else -> {
                ProviderListPanel(
                    modifier = Modifier.weight(1f),
                    providers = state.providers,
                    activeProviderLocalId = activeProviderLocalId,
                    isLoading = state.isLoadingProviders,
                    onCreateClicked = viewModel::startCreateFlow,
                    onEditClicked = viewModel::startEditFlow,
                    onRefreshClicked = { viewModel.refreshProviders() },
                    onActivateClicked = onSetActiveProvider,
                )
            }
        }
    }
}

@Composable
private fun ProviderTopBar(
    form: ProviderFormState?,
    activeProviderLocalId: String?,
    onRefreshClicked: () -> Unit,
    onCreateClicked: () -> Unit,
    onBackClicked: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (form != null) {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回 provider 列表",
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (form == null) "Provider 配置" else if (form.isCreate) "新建 Provider" else "编辑 Provider",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (form == null) {
                    if (activeProviderLocalId.isNullOrBlank()) {
                        "在手机上完成 provider 的创建、编辑和切换。"
                    } else {
                        "当前激活 provider: $activeProviderLocalId"
                    }
                } else {
                    "当前表单覆盖基础接入字段；高级字段继续由 Rust bridge 持有。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (form == null) {
            IconButton(onClick = onRefreshClicked) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "刷新 provider 列表",
                )
            }
            FilledTonalButton(onClick = onCreateClicked) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "新建")
            }
        }
    }
}

@Composable
private fun ProviderFeedbackBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
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
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭提示",
                    tint = contentColor,
                )
            }
        }
    }
}

@Composable
private fun ProviderListPanel(
    modifier: Modifier = Modifier,
    providers: List<HubProviderConfig>,
    activeProviderLocalId: String?,
    isLoading: Boolean,
    onCreateClicked: () -> Unit,
    onEditClicked: (String) -> Unit,
    onRefreshClicked: () -> Unit,
    onActivateClicked: (providerLocalId: String, modelId: String) -> Unit,
) {
    if (isLoading && providers.isEmpty()) {
        ProviderLoadingState(message = "正在同步 provider 列表…")
        return
    }

    if (providers.isEmpty()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "还没有 provider",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "先创建一个 provider，填写基础地址、鉴权和模型后即可在聊天里切换使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCreateClicked) {
                        Text(text = "创建第一个 provider")
                    }
                    OutlinedButton(onClick = onRefreshClicked) {
                        Text(text = "刷新")
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isLoading) {
            item(key = "loading-banner") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "正在刷新列表…",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        items(providers, key = { it.localId }) { provider ->
            ProviderSummaryCard(
                provider = provider,
                isActive = provider.localId == activeProviderLocalId,
                onEditClicked = { onEditClicked(provider.localId) },
                onActivateClicked = {
                    provider.resolvePreferredModelId()?.let { modelId ->
                        onActivateClicked(provider.localId, modelId)
                    }
                },
            )
        }
    }
}

@Composable
private fun ProviderSummaryCard(
    provider: HubProviderConfig,
    isActive: Boolean,
    onEditClicked: () -> Unit,
    onActivateClicked: () -> Unit,
) {
    val preferredModelId = provider.resolvePreferredModelId()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = provider.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isActive) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "当前",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(onClick = onEditClicked) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "编辑")
                }
            }

            Text(
                text = "${provider.adapterKind.label} · ${provider.modelCatalog.entries.size} 个模型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when (provider.auth.type) {
                    HubProviderAuthType.NONE -> "未配置认证"
                    HubProviderAuthType.BEARER_TOKEN -> "Bearer Token 已配置"
                    HubProviderAuthType.API_KEY -> "API Key Header: ${provider.auth.headerName}"
                    HubProviderAuthType.BASIC -> "Basic 用户名: ${provider.auth.username.ifBlank { "未设置" }}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onActivateClicked,
                    enabled = preferredModelId != null,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (isActive) "已激活" else "设为当前")
                }
                preferredModelId?.let { modelId ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = modelId,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditorPanel(
    form: ProviderFormState,
    isSaving: Boolean,
    isDeleting: Boolean,
    onDisplayNameChanged: (String) -> Unit,
    onAvatarUriChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onAdapterKindChanged: (HubProviderAdapterKind) -> Unit,
    onAuthTypeChanged: (HubProviderAuthType) -> Unit,
    onAuthTokenChanged: (String) -> Unit,
    onAuthHeaderNameChanged: (String) -> Unit,
    onAuthValueChanged: (String) -> Unit,
    onAuthUsernameChanged: (String) -> Unit,
    onAuthPasswordChanged: (String) -> Unit,
    onModelLinesChanged: (String) -> Unit,
    onDefaultModelIdChanged: (String) -> Unit,
    onSaveClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onActivateClicked: () -> Unit,
    canActivate: Boolean,
    isActive: Boolean,
) {
    val scrollState = rememberScrollState()

    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (form.isCreate) "基础字段直接写回 Rust provider truth。" else "编辑时需要重新输入密钥类字段，bridge 不会回传明文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = form.displayName,
                onValueChange = onDisplayNameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显示名称") },
                isError = form.displayNameError != null,
                supportingText = form.displayNameError?.let { { Text(it) } },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.avatarUri,
                onValueChange = onAvatarUriChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("头像 URI（可选）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = onBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                isError = form.baseUrlError != null,
                supportingText = form.baseUrlError?.let { { Text(it) } },
                singleLine = true,
            )

            Text(
                text = "Adapter",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SelectableChipRow(
                options = HubProviderAdapterKind.entries,
                selected = form.adapterKind,
                labelOf = { it.label },
                onSelected = onAdapterKindChanged,
            )

            Text(
                text = "认证方式",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SelectableChipRow(
                options = HubProviderAuthType.entries,
                selected = form.authType,
                labelOf = { it.label },
                onSelected = onAuthTypeChanged,
            )

            when (form.authType) {
                HubProviderAuthType.NONE -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "当前 provider 不附带认证字段。",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HubProviderAuthType.BEARER_TOKEN -> {
                    if (form.hasStoredSecret && form.authToken.isEmpty()) {
                        SecretReminder(text = "Bridge 仅返回“已配置”状态；保存编辑时需要重新输入 bearer token。")
                    }
                    OutlinedTextField(
                        value = form.authToken,
                        onValueChange = onAuthTokenChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Bearer Token") },
                        isError = form.authTokenError != null,
                        supportingText = form.authTokenError?.let { { Text(it) } },
                        singleLine = true,
                    )
                }

                HubProviderAuthType.API_KEY -> {
                    if (form.hasStoredSecret && form.authValue.isEmpty()) {
                        SecretReminder(text = "当前 API Key 已存在；保存编辑时需要重新输入 value。")
                    }
                    OutlinedTextField(
                        value = form.authHeaderName,
                        onValueChange = onAuthHeaderNameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Header 名称") },
                        isError = form.authHeaderNameError != null,
                        supportingText = form.authHeaderNameError?.let { { Text(it) } },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = form.authValue,
                        onValueChange = onAuthValueChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        isError = form.authValueError != null,
                        supportingText = form.authValueError?.let { { Text(it) } },
                        singleLine = true,
                    )
                }

                HubProviderAuthType.BASIC -> {
                    if (form.hasStoredPassword && form.authPassword.isEmpty()) {
                        SecretReminder(text = "当前密码已存在；保存编辑时需要重新输入 password。")
                    }
                    OutlinedTextField(
                        value = form.authUsername,
                        onValueChange = onAuthUsernameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("用户名") },
                        isError = form.authUsernameError != null,
                        supportingText = form.authUsernameError?.let { { Text(it) } },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = form.authPassword,
                        onValueChange = onAuthPasswordChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        isError = form.authPasswordError != null,
                        supportingText = form.authPasswordError?.let { { Text(it) } },
                        singleLine = true,
                    )
                }
            }

            OutlinedTextField(
                value = form.modelLines,
                onValueChange = onModelLinesChanged,
                modifier = Modifier.fillMaxWidth().height(168.dp),
                label = { Text("模型列表") },
                isError = form.modelLinesError != null,
                supportingText = {
                    Text(form.modelLinesError ?: "每行一个模型，格式：model_id 或 model_id | 展示名")
                },
            )
            OutlinedTextField(
                value = form.defaultModelId,
                onValueChange = onDefaultModelIdChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("默认模型 ID（可选）") },
                isError = form.defaultModelError != null,
                supportingText = form.defaultModelError?.let { { Text(it) } },
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveClicked, enabled = !isSaving && !isDeleting) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = if (form.isCreate) "创建 Provider" else "保存修改")
                }
                OutlinedButton(onClick = onCancelClicked, enabled = !isSaving && !isDeleting) {
                    Text(text = "取消")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onActivateClicked,
                    enabled = canActivate && !isSaving && !isDeleting,
                ) {
                    Text(text = if (isActive) "当前已激活" else "设为当前 Provider")
                }
                if (!form.isCreate) {
                    OutlinedButton(
                        onClick = onDeleteClicked,
                        enabled = !isSaving && !isDeleting,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = if (isDeleting) "删除中…" else "删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretReminder(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun <T> SelectableChipRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            if (isSelected) {
                FilledTonalButton(
                    onClick = { onSelected(option) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = labelOf(option))
                }
            } else {
                OutlinedButton(
                    onClick = { onSelected(option) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = labelOf(option))
                }
            }
        }
    }
}

@Composable
private fun ProviderLoadingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
