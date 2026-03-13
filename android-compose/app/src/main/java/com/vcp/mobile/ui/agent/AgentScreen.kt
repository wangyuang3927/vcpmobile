package com.vcp.mobile.ui.agent

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
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
        AgentTopBar(
            form = form,
            onRefreshClicked = { viewModel.refreshAgents() },
            onCreateClicked = viewModel::startCreateFlow,
            onBackClicked = viewModel::closeEditor,
        )

        state.errorMessage?.let { message ->
            FeedbackBanner(
                text = message,
                tone = BannerTone.ERROR,
                onDismiss = viewModel::clearMessage,
            )
        }
        state.statusMessage?.let { message ->
            FeedbackBanner(
                text = message,
                tone = BannerTone.SUCCESS,
                onDismiss = viewModel::clearMessage,
            )
        }

        when {
            state.isEditorLoading -> {
                LoadingState(message = "正在加载 agent 配置…")
            }

            form != null -> {
                AgentEditorPanel(
                    form = form,
                    isSaving = state.isSaving,
                    onNameChanged = viewModel::onNameChanged,
                    onAvatarUriChanged = viewModel::onAvatarUriChanged,
                    onSystemPromptChanged = viewModel::onSystemPromptChanged,
                    onSaveClicked = viewModel::saveAgent,
                    onCancelClicked = viewModel::closeEditor,
                )
            }

            else -> {
                AgentListPanel(
                    modifier = Modifier.weight(1f),
                    agents = state.agents,
                    isLoading = state.isLoadingAgents,
                    onCreateClicked = viewModel::startCreateFlow,
                    onEditClicked = viewModel::startEditFlow,
                    onRefreshClicked = { viewModel.refreshAgents() },
                )
            }
        }
    }
}

private enum class BannerTone {
    ERROR,
    SUCCESS,
}

@Composable
private fun AgentTopBar(
    form: AgentFormState?,
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
                    contentDescription = "返回 agent 列表",
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (form == null) "Agent 配置" else if (form.isCreate) "新建 Agent" else "编辑 Agent",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (form == null) {
                    "在手机上完成 agent 的创建、查看和编辑。"
                } else {
                    "当前表单覆盖头像、名称和 system prompt，保存后直接写回 Hub bridge。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (form == null) {
            IconButton(onClick = onRefreshClicked) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "刷新 agent 列表",
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
private fun FeedbackBanner(
    text: String,
    tone: BannerTone,
    onDismiss: () -> Unit,
) {
    val containerColor = when (tone) {
        BannerTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        BannerTone.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when (tone) {
        BannerTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        BannerTone.SUCCESS -> MaterialTheme.colorScheme.onTertiaryContainer
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
private fun AgentListPanel(
    modifier: Modifier = Modifier,
    agents: List<com.vcp.mobile.data.network.HubAgentConfig>,
    isLoading: Boolean,
    onCreateClicked: () -> Unit,
    onEditClicked: (String) -> Unit,
    onRefreshClicked: () -> Unit,
) {
    if (isLoading && agents.isEmpty()) {
        LoadingState(message = "正在同步 agent 列表…")
        return
    }

    if (agents.isEmpty()) {
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
                    text = "还没有 agent",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "先在手机上创建一个 agent，填写头像、名称和 system prompt 后即可直接保存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCreateClicked) {
                        Text(text = "创建第一个 agent")
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

        items(agents, key = { it.id }) { agent ->
            AgentSummaryCard(
                agent = agent,
                onEditClicked = { onEditClicked(agent.id) },
            )
        }
    }
}

@Composable
private fun AgentSummaryCard(
    agent: com.vcp.mobile.data.network.HubAgentConfig,
    onEditClicked: () -> Unit,
) {
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
                AvatarPreview(
                    avatarUri = agent.identity.avatarUri,
                    name = agent.identity.name,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.identity.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = agent.identity.avatarUri ?: "未设置头像 URI，当前使用名称首字母预览。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                text = agent.prompt.systemPrompt.ifBlank { "未配置 system prompt" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ID ${agent.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = agent.updatedAt ?: agent.createdAt ?: "刚刚",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AgentEditorPanel(
    form: AgentFormState,
    isSaving: Boolean,
    onNameChanged: (String) -> Unit,
    onAvatarUriChanged: (String) -> Unit,
    onSystemPromptChanged: (String) -> Unit,
    onSaveClicked: () -> Unit,
    onCancelClicked: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "表单只修改手机端最常用的三项：头像、名称、system prompt。其余配置在编辑时保持原样透传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AvatarPreview(
                        avatarUri = form.avatarUri.ifBlank { null },
                        name = form.name,
                    )
                    Column {
                        Text(
                            text = if (form.isCreate) "创建新 agent" else "编辑现有 agent",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = form.agentId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = form.name,
                    onValueChange = onNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "名称") },
                    placeholder = { Text(text = "例如：VCP Planner") },
                    singleLine = true,
                    isError = form.nameError != null,
                    supportingText = {
                        Text(text = form.nameError ?: "名称会显示在聊天和 agent 列表里。")
                    },
                )

                OutlinedTextField(
                    value = form.avatarUri,
                    onValueChange = onAvatarUriChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "头像 URI / emoji") },
                    placeholder = { Text(text = "例如：🤖 或 https://example.com/avatar.png") },
                    singleLine = true,
                    supportingText = {
                        Text(text = "当前 bridge 合同是 `avatar_uri` 字段；可先填 emoji 或可解析 URI。")
                    },
                )

                OutlinedTextField(
                    value = form.systemPrompt,
                    onValueChange = onSystemPromptChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "System prompt") },
                    placeholder = { Text(text = "描述 agent 的职责、语气和约束") },
                    minLines = 8,
                    isError = form.promptError != null,
                    supportingText = {
                        Text(
                            text = form.promptError
                                ?: "支持直接写 `{{variable}}` 占位文本，后续可以继续扩展变量配置。"
                        )
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCancelClicked,
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "取消")
            }
            Button(
                onClick = onSaveClicked,
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = if (form.isCreate) "保存并创建" else "保存更新")
            }
        }
    }
}

@Composable
private fun AvatarPreview(
    avatarUri: String?,
    name: String,
) {
    Surface(
        modifier = Modifier.size(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = avatarLabel(avatarUri, name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun avatarLabel(avatarUri: String?, name: String): String {
    val trimmedAvatar = avatarUri?.trim().orEmpty()
    if (trimmedAvatar.isNotEmpty()) {
        return if (trimmedAvatar.startsWith("http://") || trimmedAvatar.startsWith("https://")) {
            "图"
        } else {
            trimmedAvatar.take(2)
        }
    }
    val trimmedName = name.trim()
    if (trimmedName.isNotEmpty()) {
        return trimmedName.take(1).uppercase()
    }
    return "A"
}

@Composable
private fun LoadingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
