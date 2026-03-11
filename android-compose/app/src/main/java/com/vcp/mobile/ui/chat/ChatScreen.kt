package com.vcp.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import com.vcp.mobile.ui.chat.catalog.ConversationCatalogWorkbench
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val detailState by viewModel.detailState.collectAsState()
    val draftState by viewModel.draftState.collectAsState()
    val listState = rememberLazyListState()
    val defaultCatalogProjection = ConversationCatalogWorkbench.project(
        conversations = detailState.recoverableConversations,
        activeConversationId = detailState.conversationId,
        query = "",
        filter = ConversationCatalogFilter.ALL,
    )

    LaunchedEffect(detailState.contentVersion, detailState.isTyping, detailState.messages.size) {
        if (detailState.messages.isNotEmpty()) {
            val lastIndex = detailState.messages.lastIndex
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            if (detailState.stickToBottom && shouldStickToBottom(lastVisibleIndex, lastIndex)) {
                listState.animateScrollToItem(lastIndex)
            }
        }
    }

    LaunchedEffect(detailState.conversationId, detailState.recoveryFocusMessageId, detailState.stickToBottom, detailState.messages) {
        val focusMessageId = detailState.recoveryFocusMessageId
        if (focusMessageId != null && !detailState.stickToBottom) {
            val index = detailState.messages.indexOfFirst { it.id == focusMessageId }
            if (index >= 0) {
                listState.scrollToItem(index)
                viewModel.consumeRecoveryScene()
            }
        } else if (detailState.recoveryFocusMessageId != null) {
            viewModel.consumeRecoveryScene()
        }
    }

    LaunchedEffect(detailState.conversationId, detailState.messages) {
        snapshotFlow {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val lastVisibleMessageId = lastVisibleIndex
                ?.takeIf { it in detailState.messages.indices }
                ?.let { detailState.messages[it].id }
            val shouldStick = shouldStickToBottom(lastVisibleIndex, detailState.messages.lastIndex)
            lastVisibleMessageId to shouldStick
        }
            .distinctUntilChanged()
            .collect { (lastVisibleMessageId, shouldStick) ->
                viewModel.persistSceneAnchor(lastVisibleMessageId, shouldStick)
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = detailState.agentName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    viewModel.setConversationCatalogExpanded(
                        !detailState.isConversationCatalogExpanded
                    )
                }
            ) {
                Icon(
                    imageVector = if (detailState.isConversationCatalogExpanded) {
                        Icons.Filled.Close
                    } else {
                        Icons.Filled.History
                    },
                    contentDescription = if (detailState.isConversationCatalogExpanded) {
                        "关闭工作台"
                    } else {
                        "会话列表"
                    },
                )
            }
        }
        CurrentSceneAnchorCard(
            activeConversation = defaultCatalogProjection.activeConversation,
            isRecovering = detailState.isRecoveringConversation,
            isWorkbenchExpanded = detailState.isConversationCatalogExpanded,
            onToggleWorkbench = {
                viewModel.setConversationCatalogExpanded(!detailState.isConversationCatalogExpanded)
            },
            onStartNewConversation = viewModel::startNewConversation,
        )
        if (!detailState.isConversationCatalogExpanded && defaultCatalogProjection.quickSwitchConversations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            RecoveryStrip(
                conversations = defaultCatalogProjection.quickSwitchConversations,
                activeConversationId = detailState.conversationId,
                isRecovering = detailState.isRecoveringConversation,
                onRecoverClicked = viewModel::recoverConversation,
            )
        }
        detailState.recoveryNotice?.let { notice ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = notice,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        AnimatedVisibility(
            visible = detailState.isConversationCatalogExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                ConversationCatalogWorkbenchPanel(
                    conversations = detailState.recoverableConversations,
                    activeConversationId = detailState.conversationId,
                    isRecovering = detailState.isRecoveringConversation,
                    query = detailState.conversationCatalogQuery,
                    filter = detailState.conversationCatalogFilter,
                    onRecoverClicked = viewModel::recoverConversation,
                    onRefreshClicked = viewModel::refreshRecoveryCatalog,
                    onStartNewConversation = viewModel::startNewConversation,
                    onQueryChanged = viewModel::onConversationCatalogQueryChanged,
                    onFilterChanged = viewModel::onConversationCatalogFilterChanged,
                    onDismiss = { viewModel.setConversationCatalogExpanded(false) },
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(detailState.messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        isStreamingActive = detailState.generation.activeMessageKey == message.id &&
                            detailState.isTyping,
                    )
                }

                if (detailState.isTyping && detailState.generation.activeMessageKey == null) {
                    item {
                        TypingIndicator()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        MessageInput(
            input = draftState.currentInput,
            onInputChanged = viewModel::onInputChanged,
            onSendClicked = viewModel::sendMessage,
        )
    }
}

@Composable
private fun CurrentSceneAnchorCard(
    activeConversation: RecoverableConversation?,
    isRecovering: Boolean,
    isWorkbenchExpanded: Boolean,
    onToggleWorkbench: () -> Unit,
    onStartNewConversation: () -> Unit,
) {
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (activeConversation != null) "当前现场" else "新对话现场",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = activeConversation?.title ?: "还没有绑定会话，可直接开始新的聊天。",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (activeConversation?.pinned == true) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "置顶",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            Text(
                text = activeConversation?.summary
                    ?: if (isRecovering) "正在回到上次阅读位置…" else "工作台会记住你的会话现场与滚动位置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = buildString {
                        append(
                            if (activeConversation?.nodeCount ?: 0 > 0) {
                                "节点 ${activeConversation?.nodeCount ?: 0}"
                            } else {
                                "准备开始"
                            }
                        )
                        activeConversation?.updatedAt?.takeIf { it.isNotBlank() }?.let {
                            append(" · ")
                            append(it.toCompactTimestamp())
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onToggleWorkbench) {
                    Text(
                        text = when {
                            isWorkbenchExpanded -> "收起工作台"
                            isRecovering -> "回到工作台…"
                            else -> "切换会话"
                        }
                    )
                }
                OutlinedButton(onClick = onStartNewConversation) {
                    Text(text = "新对话")
                }
            }
        }
    }
}

internal fun shouldStickToBottom(
    lastVisibleIndex: Int?,
    lastMessageIndex: Int,
    trailingBuffer: Int = 1,
): Boolean {
    if (lastMessageIndex <= 1) return true
    val visibleIndex = lastVisibleIndex ?: return true
    return visibleIndex >= lastMessageIndex - trailingBuffer
}

@Composable
private fun ConversationCatalogWorkbenchPanel(
    conversations: List<RecoverableConversation>,
    activeConversationId: String?,
    isRecovering: Boolean,
    query: String,
    filter: ConversationCatalogFilter,
    onRecoverClicked: (String) -> Unit,
    onRefreshClicked: () -> Unit,
    onStartNewConversation: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (ConversationCatalogFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val projection = ConversationCatalogWorkbench.project(
        conversations = conversations,
        activeConversationId = activeConversationId,
        query = query,
        filter = filter,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            ) {
                Text(
                    text = "工作台保持打开时，你仍能看到聊天现场并随时切换上下文。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "会话工作台",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (isRecovering) "正在回到所选现场…" else "浏览最近会话、切换上下文，或开始一段新对话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefreshClicked) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新会话列表",
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭会话列表",
                    )
                }
            }

            OutlinedButton(
                onClick = onStartNewConversation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "开始新对话")
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                placeholder = {
                    Text(text = "搜索会话标题")
                }
            )

            ConversationFilterRow(
                selected = filter,
                onFilterChanged = onFilterChanged,
            )

            ConversationCatalogSummary(
                projection = projection,
            )

            if (projection.emptyMessage != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = projection.emptyMessage,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    projection.groupedConversations.forEach { group ->
                        item(key = "group-${group.section.name}") {
                            Text(
                                text = group.section.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(group.conversations, key = { it.conversationId }) { conversation ->
                            ConversationCatalogItem(
                                conversation = conversation,
                                selected = conversation.conversationId == activeConversationId,
                                onClick = { onRecoverClicked(conversation.conversationId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationFilterRow(
    selected: ConversationCatalogFilter,
    onFilterChanged: (ConversationCatalogFilter) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ConversationCatalogFilter.entries.toList(), key = { it.name }) { filter ->
            val isSelected = filter == selected
            AssistChip(
                onClick = { onFilterChanged(filter) },
                label = {
                    Text(
                        text = when (filter) {
                            ConversationCatalogFilter.ALL -> "全部"
                            ConversationCatalogFilter.IDLE -> "空闲"
                            ConversationCatalogFilter.FAILED -> "失败"
                        }
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            )
        }
    }
}

@Composable
private fun ConversationCatalogSummary(
    projection: com.vcp.mobile.ui.chat.catalog.ConversationCatalogProjection,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "共 ${projection.totalCount} 个会话，当前显示 ${projection.visibleCount} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            projection.activeConversation?.let {
                Text(
                    text = "当前现场：${it.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ConversationCatalogItem(
    conversation: RecoverableConversation,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (conversation.pinned) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = "置顶",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    if (selected) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = "当前",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
            conversation.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "状态：${conversation.generationState.uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = conversation.generationState.toStatusColor(selected),
            )
            Text(
                text = if (conversation.nodeCount > 0) {
                    "节点：${conversation.nodeCount} · 更新：${conversation.updatedAt.toCompactTimestamp()}"
                } else {
                    "更新：${conversation.updatedAt.toCompactTimestamp()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecoveryStrip(
    conversations: List<RecoverableConversation>,
    activeConversationId: String?,
    isRecovering: Boolean,
    onRecoverClicked: (String) -> Unit,
) {
    if (conversations.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (isRecovering) "正在恢复会话…" else "快速切换",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = { it.conversationId }) { conversation ->
                val selected = conversation.conversationId == activeConversationId
                AssistChip(
                    onClick = { onRecoverClicked(conversation.conversationId) },
                    label = {
                        Text(
                            text = if (selected) "✓ ${conversation.title}" else conversation.title,
                            maxLines = 1,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                )
            }
        }
    }
}

private fun String.toCompactTimestamp(): String {
    return runCatching {
        val instant = java.time.Instant.parse(this)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
        formatter.format(instant)
    }.getOrElse {
        this.replace('T', ' ').removeSuffix("Z").take(16).ifBlank { this }
    }
}

@Composable
private fun String.toStatusColor(selected: Boolean) = when (lowercase()) {
    "idle" -> if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
    "failed" -> MaterialTheme.colorScheme.error
    "streaming" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    isStreamingActive: Boolean,
) {
    val projection = message.renderProjection()
    val isUser = message.sender == MessageSender.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .width(280.dp)
                .background(
                    color = if (isStreamingActive && !isUser) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
                    } else {
                        bubbleColor
                    },
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isStreamingActive && !isUser) {
                    Text(
                        text = "正在生成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                if (!isUser && projection.labels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        projection.labels.forEach { label ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.20f),
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textColor.copy(alpha = 0.68f),
                                )
                            }
                        }
                    }
                }

                TypedPartsRenderer(
                    message = message,
                    textColor = textColor,
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .width(18.dp)
                .height(18.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Agent 思考中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageInput(
    input: String,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = input,
            onValueChange = onInputChanged,
            placeholder = {
                Text(
                    text = "输入消息…",
                    textAlign = TextAlign.Start,
                )
            },
            maxLines = 4,
            singleLine = false,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onSendClicked,
            enabled = input.isNotBlank(),
        ) {
            Text(text = "发送")
        }
    }
}
