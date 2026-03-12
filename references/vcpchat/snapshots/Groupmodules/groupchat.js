// Groupmodules/groupchat.js - 群聊核心逻辑模块

const fs = require('fs-extra');
const path = require('path');
const { ipcMain } = require('electron');
const contextSanitizer = require('../modules/contextSanitizer');
const fileManager = require('../modules/fileManager');
const canvasHandlers = require('../modules/ipc/canvasHandlers');
// const { v4: uuidv4 } = require('uuid'); // 如果需要唯一ID生成

const activeRequestControllers = new Map();
const CANVAS_PLACEHOLDER = '{{VCPChatCanvas}}';
const GROUP_SESSION_WATCHER_PLACEHOLDER = '{{VCPChatGroupSessionWatcher}}';

// 新增：话题总结相关常量
const MIN_MESSAGES_FOR_SUMMARY = 4;
const DEFAULT_TOPIC_NAMES = ["主要群聊"]; // 也可以包含 "新话题" 的模式匹配


let mainAppPaths = {}; // 将由 main.js 初始化时传入

/**
 * 初始化模块所需的路径配置
 * @param {object} paths - 包含 APP_DATA_ROOT_IN_PROJECT, AGENTS_DIR, USER_DATA_DIR, SETTINGS_FILE 等路径的对象
 */
function initializePaths(paths) {
    mainAppPaths = {
        ...paths,
        AGENT_GROUPS_DIR: path.join(paths.APP_DATA_ROOT_IN_PROJECT, 'AgentGroups'),
    };
    fs.ensureDirSync(mainAppPaths.AGENT_GROUPS_DIR);
    console.log('[GroupChat] Paths initialized. AgentGroups directory ensured:', mainAppPaths.AGENT_GROUPS_DIR);
}

/**
 * 获取群聊会话监控信息
 * @param {string} groupId - 群组ID
 * @param {string} topicId - 话题ID
 * @returns {Promise<object>} - 群聊会话监控信息
 */
async function getGroupSessionWatcher(groupId, topicId) {
    try {
        if (!mainAppPaths.USER_DATA_DIR) {
            return {
                status: "error",
                error: "用户数据目录未初始化",
                timestamp: new Date().toISOString(),
                displayTime: new Date().toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai' })
            };
        }

        const groupHistoryPath = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', topicId, 'history.json');
        
        if (await fs.pathExists(groupHistoryPath)) {
            const stats = await fs.stat(groupHistoryPath);
            const historyContent = await fs.readJson(groupHistoryPath);
            
            return {
                status: "active",
                currentSession: {
                    groupId: groupId,
                    topicId: topicId,
                    filePath: groupHistoryPath,
                    lastModified: stats.mtime.toISOString(),
                    lastModifiedDisplay: stats.mtime.toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai' }),
                    modifiedTimestamp: stats.mtime.getTime(),
                    size: stats.size,
                    messageCount: historyContent.length
                },
                timestamp: new Date().toISOString(),
                displayTime: new Date().toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai' })
            };
        } else {
            return {
                status: "no_session",
                message: `未找到群聊会话文件: ${groupHistoryPath}`,
                groupId: groupId,
                topicId: topicId,
                timestamp: new Date().toISOString(),
                displayTime: new Date().toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai' })
            };
        }
    } catch (error) {
        console.error(`[GroupChat] Error in getGroupSessionWatcher for group ${groupId}, topic ${topicId}:`, error.message);
        return {
            status: "error",
            error: error.message,
            groupId: groupId,
            topicId: topicId,
            timestamp: new Date().toISOString(),
            displayTime: new Date().toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai' })
        };
    }
}


/**
 * 获取全局VCP设置（如URL, API Key, 用户名）
 * @returns {Promise<object>}
 */
async function getVcpGlobalSettings() {
    if (mainAppPaths.SETTINGS_FILE && await fs.pathExists(mainAppPaths.SETTINGS_FILE)) {
        try {
            const settings = await fs.readJson(mainAppPaths.SETTINGS_FILE);
            return {
                vcpUrl: settings.vcpServerUrl,
                vcpApiKey: settings.vcpApiKey,
                userName: settings.userName || '用户',
                topicSummaryModel: settings.topicSummaryModel,
                enableAgentBubbleTheme: settings.enableAgentBubbleTheme === true,
                // 添加净化器相关配置
                enableContextSanitizer: settings.enableContextSanitizer === true,
                contextSanitizerDepth: settings.contextSanitizerDepth
            };
        } catch (e) {
            console.error("[GroupChat] Error reading VCP settings from settings.json", e);
        }
    }
    return { 
        vcpUrl: null, 
        vcpApiKey: null, 
        userName: '用户', 
        topicSummaryModel: null, 
        enableAgentBubbleTheme: false,
        // 添加净化器默认值
        enableContextSanitizer: false,
        contextSanitizerDepth: 2
    };
}

/**
 * Resolve the model that should be used for this agent in group chat.
 * When unified model is enabled, it has priority over per-agent model.
 * @param {object} groupConfig
 * @param {object} agentConfig
 * @returns {{usingUnifiedModel: boolean, unifiedModel: string, agentModel: string, effectiveModel: string}}
 */
function resolveEffectiveModel(groupConfig, agentConfig) {
    const usingUnifiedModel = groupConfig && groupConfig.useUnifiedModel === true;
    const unifiedModel = (groupConfig && typeof groupConfig.unifiedModel === 'string')
        ? groupConfig.unifiedModel.trim()
        : '';
    const agentModel = (agentConfig && typeof agentConfig.model === 'string')
        ? agentConfig.model.trim()
        : '';

    return {
        usingUnifiedModel,
        unifiedModel,
        agentModel,
        effectiveModel: usingUnifiedModel ? unifiedModel : agentModel
    };
}



/**
 * 创建一个新的 AgentGroup
 * @param {string} groupName - 群组名称
 * @param {object} initialConfig - 可选的初始配置
 * @returns {Promise<object>} - 包含成功状态和群组信息的对象
 */
async function createAgentGroup(groupName, initialConfig = {}) {
    if (!mainAppPaths.AGENT_GROUPS_DIR) {
        return { success: false, error: 'GroupChat module paths not initialized.' };
    }
    try {
        const baseName = groupName.replace(/[^a-zA-Z0-9_-]/g, '_');
        const groupId = `${baseName}_${Date.now()}`; // 更简单的唯一ID
        const groupDir = path.join(mainAppPaths.AGENT_GROUPS_DIR, groupId);

        if (await fs.pathExists(groupDir)) {
            return { success: false, error: 'AgentGroup 文件夹已存在（ID冲突）。' };
        }
        await fs.ensureDir(groupDir);

        const defaultConfig = {
            id: groupId,
            name: groupName,
            avatar: null, 
            avatarCalculatedColor: null, // 新增：用于存储头像计算出的颜色
            members: [],
            mode: 'sequential', // 可选: 'sequential', 'naturerandom', 'invite_only'
            tagMatchMode: 'strict', // 可选: 'strict'(原始行为), 'natural'(智能触发，区分tag来源)
            memberTags: {},
            groupPrompt: '',
            invitePrompt: '现在轮到你{{VCPChatAgentName}}发言了。系统已经为大家添加[xxx的发言：]这样的标记头，以用于区分不同发言来自谁。大家不用自己再输出自己的发言标记头，也不需要讨论发言标记系统，正常聊天即可。',
           // 新增：统一模型设置
           useUnifiedModel: false,
           unifiedModel: '',
            createdAt: Date.now(),
            topics: [{ id: `group_topic_${Date.now()}`, name: "主要群聊", createdAt: Date.now() }]
        };

        const configToSave = { ...defaultConfig, ...initialConfig, id: groupId, name: groupName };
        await fs.writeJson(path.join(groupDir, 'config.json'), configToSave, { spaces: 2 });

        const defaultTopicHistoryDir = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', configToSave.topics[0].id);
        await fs.ensureDir(defaultTopicHistoryDir);
        await fs.writeJson(path.join(defaultTopicHistoryDir, 'history.json'), [], { spaces: 2 });

        console.log(`[GroupChat] AgentGroup created: ${groupName} (ID: ${groupId})`);
        return { success: true, agentGroup: configToSave };
    } catch (error) {
        console.error('[GroupChat] 创建 AgentGroup 失败:', error);
        return { success: false, error: error.message };
    }
}

/**
 * 获取所有 AgentGroup 列表
 * @returns {Promise<Array<object>>} - AgentGroup 配置对象数组
 */
async function getAgentGroups() {
    if (!mainAppPaths.AGENT_GROUPS_DIR) {
        console.error('[GroupChat] Cannot get agent groups, paths not initialized.');
        return [];
    }
    try {
        const groupFolders = await fs.readdir(mainAppPaths.AGENT_GROUPS_DIR);
        const agentGroups = [];
        for (const folderName of groupFolders) {
            const groupPath = path.join(mainAppPaths.AGENT_GROUPS_DIR, folderName);
            const stat = await fs.stat(groupPath);
            if (stat.isDirectory()) {
                const configPath = path.join(groupPath, 'config.json');
                if (await fs.pathExists(configPath)) {
                    const config = await fs.readJson(configPath);
                    if (config.avatar) { 
                        config.avatarUrl = `file://${path.join(groupPath, config.avatar)}?t=${Date.now()}`;
                    } else {
                        config.avatarUrl = null; 
                    }
                    // avatarCalculatedColor 应该已随config加载
                    agentGroups.push(config);
                }
            }
        }
        // TODO: 根据 settings.json 中的 itemOrder 排序 (如果需要，但这通常在renderer端处理)
        agentGroups.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        return agentGroups;
    } catch (error) {
        console.error('[GroupChat] 获取 AgentGroup 列表失败:', error);
        return [];
    }
}

/**
 * 获取指定 AgentGroup 的配置
 * @param {string} groupId - 群组 ID
 * @returns {Promise<object|null>} - 群组配置对象，或 null (如果未找到)
 */
async function getAgentGroupConfig(groupId) {
    if (!mainAppPaths.AGENT_GROUPS_DIR) return null;
    try {
        const groupDir = path.join(mainAppPaths.AGENT_GROUPS_DIR, groupId);
        const configPath = path.join(groupDir, 'config.json');
        if (await fs.pathExists(configPath)) {
            const config = await fs.readJson(configPath);
            if (config.avatar) {
                config.avatarUrl = `file://${path.join(groupDir, config.avatar)}?t=${Date.now()}`;
            } else {
                config.avatarUrl = null;
            }
            return config;
        }
        return null;
    } catch (error) {
        console.error(`[GroupChat] 获取 AgentGroup ${groupId} 配置失败:`, error);
        return null;
    }
}

/**
 * 保存 AgentGroup 的配置
 * @param {string} groupId - 群组 ID
 * @param {object} configData - 要保存的配置数据
 * @returns {Promise<object>} - 包含成功状态的对象
 */
async function saveAgentGroupConfig(groupId, configData) {
    if (!mainAppPaths.AGENT_GROUPS_DIR) {
        return { success: false, error: 'GroupChat module paths not initialized.' };
    }
    try {
        const groupDir = path.join(mainAppPaths.AGENT_GROUPS_DIR, groupId);
        await fs.ensureDir(groupDir);
        const configPath = path.join(groupDir, 'config.json');
        
        let existingConfig = {};
        if (await fs.pathExists(configPath)) {
            existingConfig = await fs.readJson(configPath);
        }
        
        // avatarUrl 是动态生成的，不保存到文件
        // avatar 字段（文件名）应该在 configData 中，如果被修改的话
        // avatarCalculatedColor 也是动态获取的，但如果 main.js 决定持久化它，它应该在 configData 中
        const { avatarUrl, ...dataToSave } = configData; 

        const newConfigData = { ...existingConfig, ...dataToSave, id: groupId };

        // Backend guard: unified model mode must have a non-empty model id.
        if (newConfigData.useUnifiedModel === true) {
            const normalizedUnifiedModel = typeof newConfigData.unifiedModel === 'string'
                ? newConfigData.unifiedModel.trim()
                : '';
            if (!normalizedUnifiedModel) {
                return { success: false, error: '启用群组统一模型时，群组统一模型不能为空。' };
            }
            newConfigData.unifiedModel = normalizedUnifiedModel;
        } else if (typeof newConfigData.unifiedModel === 'string') {
            newConfigData.unifiedModel = newConfigData.unifiedModel.trim();
        }
        
        await fs.writeJson(configPath, newConfigData, { spaces: 2 });
        console.log(`[GroupChat] AgentGroup ${groupId} 配置已保存。`);
        
        if (newConfigData.avatar) {
            newConfigData.avatarUrl = `file://${path.join(groupDir, newConfigData.avatar)}?t=${Date.now()}`;
        } else {
            newConfigData.avatarUrl = null;
        }
        return { success: true, agentGroup: newConfigData };
    } catch (error) {
        console.error(`[GroupChat] 保存 AgentGroup ${groupId} 配置失败:`, error);
        return { success: false, error: error.message };
    }
}

/**
 * 删除 AgentGroup
 * @param {string} groupId - 群组 ID
 * @returns {Promise<object>} - 包含成功状态的对象
 */
async function deleteAgentGroup(groupId) {
    if (!mainAppPaths.AGENT_GROUPS_DIR || !mainAppPaths.USER_DATA_DIR) {
        return { success: false, error: 'GroupChat module paths not initialized.' };
    }
    try {
        const groupDir = path.join(mainAppPaths.AGENT_GROUPS_DIR, groupId);
        const userDataGroupDir = path.join(mainAppPaths.USER_DATA_DIR, groupId); 

        if (await fs.pathExists(groupDir)) {
            await fs.remove(groupDir);
        }
        if (await fs.pathExists(userDataGroupDir)) {
            await fs.remove(userDataGroupDir);
        }
        console.log(`[GroupChat] AgentGroup ${groupId} 已删除。`);
        return { success: true };
    } catch (error) {
        console.error(`[GroupChat] 删除 AgentGroup ${groupId} 失败:`, error);
        return { success: false, error: error.message };
    }
}


/**
 * 处理群聊消息，并触发AI响应
 * @param {string} groupId - 群组ID
 * @param {string} topicId - 话题ID
 * @param {object} userMessage - 用户发送的消息对象 { role: 'user', content: { text: '...', image?: 'base64...' }, id: 'messageId', name?: 'UserName' }
 * @param {function} sendStreamChunkToRenderer - 用于发送流式数据的回调函数 (channel, data) => {}
 * @param {function} getAgentConfigById - 函数，用于根据Agent ID获取其完整配置 (agentId) => Promise<AgentConfig|null>
 * @returns {Promise<void>}
 */
async function handleGroupChatMessage(groupId, topicId, userMessage, sendStreamChunkToRenderer, getAgentConfigById) {
    console.log('[GroupChat] handleGroupChatMessage invoked.');
    console.log('[GroupChat] mainAppPaths:', mainAppPaths ? JSON.stringify(Object.keys(mainAppPaths)) : 'undefined/null');
    console.log('[GroupChat] typeof getAgentConfigById:', typeof getAgentConfigById);

    if (!mainAppPaths || !mainAppPaths.AGENT_GROUPS_DIR || !mainAppPaths.AGENT_DIR || !mainAppPaths.USER_DATA_DIR || typeof getAgentConfigById !== 'function') {
        console.error('[GroupChat] handleGroupChatMessage: Critical paths or getAgentConfigById not initialized properly.');
        console.error(`[GroupChat] Details - mainAppPaths keys: ${mainAppPaths ? Object.keys(mainAppPaths).join(', ') : 'N/A'}, AGENT_GROUPS_DIR exists: ${!!mainAppPaths?.AGENT_GROUPS_DIR}, AGENT_DIR exists: ${!!mainAppPaths?.AGENT_DIR}, USER_DATA_DIR exists: ${!!mainAppPaths?.USER_DATA_DIR}, getAgentConfigById is function: ${typeof getAgentConfigById === 'function'}`);
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: '群聊模块关键路径或依赖未正确初始化。', messageId: userMessage.id || Date.now(), context: { groupId, topicId, isGroupMessage: true } });
        }
        return;
    }

    const groupConfig = await getAgentGroupConfig(groupId);
    if (!groupConfig) {
        console.error(`[GroupChat] 未找到群组配置: ${groupId}`);
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: `未找到群组配置: ${groupId}`, messageId: userMessage.id || Date.now(), context: { groupId, topicId, isGroupMessage: true } });
        }
        return;
    }
 
     const groupHistoryPath = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', topicId, 'history.json');
     await fs.ensureDir(path.dirname(groupHistoryPath));
     let groupHistory = [];
     if (await fs.pathExists(groupHistoryPath)) {
         groupHistory = await fs.readJson(groupHistoryPath);
     }
    
    const globalVcpSettings = await getVcpGlobalSettings();
    const userNameForMessage = userMessage.name || globalVcpSettings.userName || '用户';

    // 确保 userMessage.content 是对象，并且 text 存在
    // userMessage.content.text is combinedTextContent (user input + non-image file texts)
    // userMessage.originalUserText is the raw user input
    const userOriginalTextForHistory = userMessage.originalUserText ||
                                     ((userMessage.content && typeof userMessage.content.text === 'string') ? userMessage.content.text : ''); // Fallback if originalUserText is somehow missing

    // userMessage.attachments from grouprenderer.js now contains full _fileManagerData
    const userMessageEntry = {
        role: 'user',
        name: userNameForMessage,
        content: userOriginalTextForHistory, // Store original user text in history
        attachments: userMessage.attachments || [], // Preserve full attachment info in history
        timestamp: Date.now(),
        id: userMessage.id || `msg_user_${Date.now()}`
        // We might want to store userMessage.content.text (combined) separately in history if needed for other features,
        // but for UI rendering and consistent history, originalUserText is better for the main 'content' field.
    };
    groupHistory.push(userMessageEntry);
    await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });

    // 获取所有成员的详细配置
    const memberAgentConfigs = {};
    for (const memberId of groupConfig.members) {
        const agentConfig = await getAgentConfigById(memberId); // 使用传入的函数获取Agent配置
        if (agentConfig && !agentConfig.error) {
            memberAgentConfigs[memberId] = agentConfig;
        } else {
            console.warn(`[GroupChat] 未找到或无法加载群成员 ${memberId} 的配置: ${agentConfig?.error}`);
        }
    }
    
    const activeMembers = groupConfig.members
        .map(id => memberAgentConfigs[id])
        .filter(Boolean); // 过滤掉未成功加载配置的成员

    if (activeMembers.length === 0) {
        console.log('[GroupChat] 群聊中没有可用的活跃成员。');
         if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'no_ai_response', message: '当前群聊没有可响应的AI成员。', messageId: userMessage.id, context: { groupId, topicId, isGroupMessage: true } });
        }
        return;
    }

    let agentsToRespond = [];
    if (groupConfig.mode === 'sequential') {
        // TODO: 实现顺序发言逻辑，可能需要追踪上一位发言者
        // 简单实现：按成员列表顺序，每次一个？或者全部按顺序？
        // 假设按列表顺序轮流发言，需要一个状态来记录当前轮到谁
        // 暂时简化为：顺序模式下，所有成员都尝试按列表顺序发言
        agentsToRespond = activeMembers; 
        console.log(`[GroupChat - Sequential] Agents to respond: ${agentsToRespond.map(a => a.name).join(', ')}`);
    } else if (groupConfig.mode === 'naturerandom') {
        agentsToRespond = determineNatureRandomSpeakers(
            activeMembers, // 传递已加载好的详细配置
            groupHistory,
            groupConfig,
            userMessageEntry // 用户消息本身，用于内容匹配
        );
    } else if (groupConfig.mode === 'invite_only') {
        agentsToRespond = []; // 在邀请模式下，用户发言后AI不主动响应
        console.log(`[GroupChat - Invite Only] No agents will respond automatically to user message.`);
    } else {
        console.warn(`[GroupChat] 未知的群聊模式: ${groupConfig.mode}`);
        // 默认或回退到某种行为，例如不响应或只让第一个发言
        agentsToRespond = []; // 对于未知模式，也先不响应
    }

    // 只有在 agentsToRespond 明确有内容时才继续自动发言流程
    // 在 invite_only 模式下，这个循环不会执行
    if (agentsToRespond.length > 0) {
        console.log(`[GroupChat] Agents to respond automatically: ${agentsToRespond.map(a => a.name).join(', ')}`);
        // 按顺序让选中的 Agent 发言 (严格串行处理)
        for (const agentConfig of agentsToRespond) {
            const agentId = agentConfig.id;
        const agentName = agentConfig.name || agentId; // 修复：如果名称丢失，回退到 agentId
        // 为每个Agent的响应生成唯一ID
        const messageIdForAgentResponse = `msg_group_${userMessage.id}_${agentId}_${Date.now()}`;

        // 重新从文件读取最新的历史记录，确保获取到上一个Agent的发言
        // 注意：如果Agent非常多且发言很快，频繁读写文件可能会有性能影响，
        // 但为了严格的上下文连续性，这是必要的。
        // 或者，在 handleGroupChatMessage 开始时读取一次，然后在此循环中仅更新内存中的 groupHistory 数组，
        // 并在每个 agent 发言完毕后，将该 agent 的发言追加到文件。
        // 当前的 groupHistory 是在函数开始时加载的，并在用户发言后追加了用户消息。
        // 我们将在每个 AI 发言后，将 AI 的回复也追加到这个内存中的 groupHistory，并写回文件。

        // 1. 构建 SystemPrompt (基于当前 agentConfig 和 groupConfig)
        let combinedSystemPrompt = agentConfig.systemPrompt || `你是${agentName}。`;
        if (groupConfig.groupPrompt) {
            let groupPrompt = groupConfig.groupPrompt;
            // 处理 VCPChatGroupSessionWatcher 占位符
            if (groupPrompt.includes(GROUP_SESSION_WATCHER_PLACEHOLDER)) {
                const sessionWatcherInfo = await getGroupSessionWatcher(groupId, topicId);
                groupPrompt = groupPrompt.replace(new RegExp(GROUP_SESSION_WATCHER_PLACEHOLDER, 'g'), JSON.stringify(sessionWatcherInfo));
            }
            combinedSystemPrompt += `\n\n[群聊设定]:\n${groupPrompt}`;
        }

        // 2. 构建上下文结构 (每次循环都基于最新的 groupHistory)
        const contextForAgentPromises = groupHistory.map(async msg => {
            const speakerName = msg.name || (msg.role === 'user' ? userNameForMessage : (memberAgentConfigs[msg.agentId]?.name || 'AI'));
            
            let textForAIContext;
            if (msg.id === userMessage.id && msg.role === 'user') {
                // This is the current user message being processed for the AI turn.
                // Use the combined text passed from grouprenderer (userMessage.content.text).
                textForAIContext = (userMessage.content && typeof userMessage.content.text === 'string')
                                   ? userMessage.content.text
                                   : '';
                
                if (textForAIContext.includes(CANVAS_PLACEHOLDER)) {
                    try {
                        const canvasData = await canvasHandlers.handleGetLatestCanvasContent();
                        if (canvasData && !canvasData.error) {
                            const formattedCanvasContent = `
[Canvas Content]
${canvasData.content || ''}
[Canvas Path]
${canvasData.path || 'No file path'}
[Canvas Errors]
${canvasData.errors || 'No errors'}
`;
                            textForAIContext = textForAIContext.replace(new RegExp(CANVAS_PLACEHOLDER, 'g'), formattedCanvasContent);
                        } else {
                            console.error("[GroupChat] Failed to get latest canvas content:", canvasData?.error);
                            textForAIContext = textForAIContext.replace(new RegExp(CANVAS_PLACEHOLDER, 'g'), '\n[Canvas content could not be loaded]\n');
                        }
                    } catch (error) {
                        // 这个catch块现在理论上不会因为handleGetLatestCanvasContent本身被触发，但保留以防万一
                        console.error("[GroupChat] Error processing canvas content:", error);
                        textForAIContext = textForAIContext.replace(new RegExp(CANVAS_PLACEHOLDER, 'g'), '\n[Error processing canvas content]\n');
                    }
                }
            } else {
                // This is a historical message. msg.content is now the original user text.
                // We need to reconstruct the text with appended file contents for the AI.
                textForAIContext = (typeof msg.content === 'string') ? msg.content : '';
                if (msg.attachments && msg.attachments.length > 0) {
                    for (const att of msg.attachments) {
                        if (att._fileManagerData && typeof att._fileManagerData.extractedText === 'string' && att._fileManagerData.extractedText.trim() !== '') {
                            textForAIContext += `

[附加文件: ${att.name || '未知文件'}]
${att._fileManagerData.extractedText}
[/附加文件结束: ${att.name || '未知文件'}]`;
                        } else if (att._fileManagerData && att.type && !att.type.startsWith('image/')) {
                            textForAIContext += `\n\n[附加文件: ${att.name || '未知文件'} (无法预览文本内容)]`;
                        } else if (!att._fileManagerData) {
                            console.warn(`[GroupChat Context] Historical message attachment for "${att.name}" is missing _fileManagerData. Text content cannot be appended.`);
                        }
                    }
                }
            }
            
            const contentWithSpeakerTag = `[${speakerName}的发言]: ${textForAIContext}`;
            const vcpMessageContent = [{ type: 'text', text: contentWithSpeakerTag }];

            // Image handling: Iterate through attachments of the current message (msg)
            // msg.attachments contains _fileManagerData which has internalPath
            if (msg.attachments && msg.attachments.length > 0) {
                for (const att of msg.attachments) {
                    const isSupportedMediaType = att._fileManagerData.type.startsWith('image/') || att._fileManagerData.type.startsWith('audio/') || att._fileManagerData.type.startsWith('video/');
                    if (att._fileManagerData && att._fileManagerData.type && isSupportedMediaType && att._fileManagerData.internalPath) {
                        try {
                            const result = await fileManager.getFileAsBase64(att._fileManagerData.internalPath);
                            if (result && result.success && result.base64Frames && result.base64Frames.length > 0) {
                                // 对于多帧的媒体（如GIF），我们这里只取第一帧给AI，以避免上下文过长。
                                // 未来可以根据模型能力进行优化。
                                vcpMessageContent.push({
                                    type: 'image_url',
                                    image_url: { url: `data:${att._fileManagerData.type};base64,${result.base64Frames[0]}` }
                                });
                            } else {
                                console.warn(`[GroupChat] Failed to get base64 for media ${att.name || att._fileManagerData.name}: ${result?.error}`);
                            }
                        } catch (e) {
                            console.error(`[GroupChat] Error getting base64 for media ${att.name || att._fileManagerData.name} in context:`, e);
                        }
                    }
                }
            }
            
            return {
                role: msg.role,
                content: vcpMessageContent, // This is now an array
            };
        });
        
        const contextForAgent = await Promise.all(contextForAgentPromises);

        // 3. 构建 InvitePrompt
        let invitePromptContent = (groupConfig.invitePrompt || `现在轮到你 {{VCPChatAgentName}} 发言了。`).replace(/{{VCPChatAgentName}}/g, agentName);

        let messagesForAI = [];
        if (combinedSystemPrompt.trim()) {
            messagesForAI.push({ role: 'system', content: combinedSystemPrompt });
        }
        messagesForAI.push(...contextForAgent);
        // 添加触发AI发言的模拟用户输入 (as text part of a content array)
        messagesForAI.push({ role: 'user', content: [{ type: 'text', text: invitePromptContent }], name: userNameForMessage });
        // 添加净化器处理  
        if (globalVcpSettings.enableContextSanitizer === true) {    
            const sanitizerDepth = globalVcpSettings.contextSanitizerDepth !== undefined ? globalVcpSettings.contextSanitizerDepth : 2;    
            console.log(`[GroupChat Context Sanitizer] Enabled with depth: ${sanitizerDepth}`);    
              
            const systemMessages = messagesForAI.filter(m => m.role === 'system');    
            const nonSystemMessages = messagesForAI.filter(m => m.role !== 'system');    
              
            // 使用已加载的净化器
            const sanitizedNonSystemMessages = contextSanitizer.sanitizeMessages(nonSystemMessages, sanitizerDepth);
              
            messagesForAI = [...systemMessages, ...sanitizedNonSystemMessages];
              
            console.log(`[GroupChat Context Sanitizer] Messages processed successfully`);    
        }
        // --- Agent Bubble Theme Injection ---
        if (globalVcpSettings.enableAgentBubbleTheme) {
            let systemMsgIndex = messagesForAI.findIndex(m => m.role === 'system');
            if (systemMsgIndex === -1) {
                messagesForAI.unshift({ role: 'system', content: '' });
                systemMsgIndex = 0;
            }
            
            const injection = '为你在群聊中构建独特的个性气泡，输出规范要求：{{VarDivRender}}';
            if (!messagesForAI[systemMsgIndex].content.includes(injection)) {
                messagesForAI[systemMsgIndex].content += `\n\n${injection}`;
                messagesForAI[systemMsgIndex].content = messagesForAI[systemMsgIndex].content.trim();
            }
        }
        // --- End of Injection ---

        const modelResolution = resolveEffectiveModel(groupConfig, agentConfig);
        if (!globalVcpSettings.vcpUrl) {
            const errorMsg = `Agent ${agentName} (${agentId}) 无法响应：VCP URL 未配置。`;
            console.error(`[GroupChat] ${errorMsg}`);
            const errorResponse = { role: 'assistant', name: agentName, agentId: agentId, content: `[系统消息] ${errorMsg}`, timestamp: Date.now(), id: messageIdForAgentResponse };
            groupHistory.push(errorResponse);
            if (typeof sendStreamChunkToRenderer === 'function') {
                sendStreamChunkToRenderer({ type: 'error', error: errorMsg, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true } });
            }
            continue; // 继续处理下一个需要发言的Agent
        }

        if (!modelResolution.effectiveModel) {
            const modelHint = modelResolution.usingUnifiedModel
                ? '已启用群组统一模型，但群组统一模型为空。'
                : '当前成员未配置模型。';
            const errorMsg = `Agent ${agentName} (${agentId}) 无法响应：${modelHint}`;
            console.error(`[GroupChat] ${errorMsg}`);
            const errorResponse = { role: 'assistant', name: agentName, agentId: agentId, content: `[系统消息] ${errorMsg}`, timestamp: Date.now(), id: messageIdForAgentResponse };
            groupHistory.push(errorResponse);
            if (typeof sendStreamChunkToRenderer === 'function') {
                sendStreamChunkToRenderer({ type: 'error', error: errorMsg, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true } });
            }
            continue; // 继续处理下一个需要发言的Agent
        }

        try {
            // Send 'agent_thinking' event before VCP call for ALL agents
            console.log(`[GroupChat] Preparing to send 'agent_thinking' event for ${agentName} (msgId: ${messageIdForAgentResponse})`);
            if (typeof sendStreamChunkToRenderer === 'function') {
                sendStreamChunkToRenderer({
                    type: 'agent_thinking',
                    messageId: messageIdForAgentResponse,
                    context: {
                        groupId,
                        topicId,
                        agentId,
                        agentName,
                        avatarUrl: agentConfig.avatarUrl,
                        avatarColor: agentConfig.avatarCalculatedColor,
                        isGroupMessage: true
                    }
                });
                console.log(`[GroupChat] 'agent_thinking' event sent for ${agentName}.`);
                // Short delay to allow renderer to process 'thinking' bubble
                await new Promise(resolve => setTimeout(resolve, 200));
            } else {
                console.error(`[GroupChat] sendStreamChunkToRenderer is not a function when sending 'agent_thinking' for ${agentName}!`);
            }

            const modelConfigForAgent = {
               model: modelResolution.effectiveModel,
                temperature: parseFloat(agentConfig.temperature),
                max_tokens: agentConfig.maxOutputTokens ? parseInt(agentConfig.maxOutputTokens) : undefined,
                stream: agentConfig.streamOutput === true || String(agentConfig.streamOutput) === 'true'
            };

            // 添加超时控制
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 60000); // 60秒超时
            activeRequestControllers.set(messageIdForAgentResponse, controller);
            
            let response;
            try {
                response = await fetch(globalVcpSettings.vcpUrl, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${globalVcpSettings.vcpApiKey}`
                    },
                    body: JSON.stringify({
                        messages: messagesForAI,
                        model: modelConfigForAgent.model,
                        temperature: modelConfigForAgent.temperature,
                        stream: modelConfigForAgent.stream,
                        messageId: messageIdForAgentResponse // 包含 messageId 以支持后端中断
                    }),
                    signal: controller.signal
                });
            } catch (fetchError) {
                clearTimeout(timeoutId);
                if (fetchError.name === 'AbortError') {
                    // This case handles when the request is aborted BEFORE the stream starts.
                    // The stream's own catch block will handle abortions DURING streaming.
                    console.log(`[GroupChat] VCP fetch for ${agentName} was aborted before stream began.`);
                    // We don't need to save history here as no response was generated.
                    // The 'thinking' bubble will just disappear without a message, which is acceptable.
                    // We send an 'end' event to make sure the UI cleans up the thinking bubble.
                     if (typeof sendStreamChunkToRenderer === 'function') {
                        sendStreamChunkToRenderer({ type: 'end', error: '用户中止', fullResponse: '', messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true }, interrupted: true });
                    }
                    continue;
                }
                throw fetchError;
            } finally {
                clearTimeout(timeoutId);
            }

            if (!response.ok) {
                const errorText = await response.text();
                console.error(`[GroupChat] VCP request failed for ${agentName}. Status: ${response.status}, Response Text:`, errorText);
                let errorData = { message: `Server returned status ${response.status}`, details: errorText };
                try { const parsedError = JSON.parse(errorText); if (typeof parsedError === 'object' && parsedError !== null) errorData = parsedError; } catch (e) { /* Not JSON */ }
                
                const errorMessageToPropagate = `VCP request failed: ${response.status} - ${errorData.message || errorData.error || (typeof errorData === 'string' ? errorData : 'Unknown server error')}`;
                const errorResponseEntry = { role: 'assistant', name: agentName, agentId: agentId, content: `[System Message] ${errorMessageToPropagate}`, timestamp: Date.now(), id: messageIdForAgentResponse };
                groupHistory.push(errorResponseEntry);
                await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });

                if (typeof sendStreamChunkToRenderer === 'function') {
                    // Finalize the 'thinking' bubble with an error message
                    sendStreamChunkToRenderer({ type: 'end', error: errorMessageToPropagate, fullResponse: `[错误] ${errorMessageToPropagate}`, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true } });
                }
                continue; // Move to the next agent
            }

            if (modelConfigForAgent.stream) {
                // For streaming, now send the 'start' event to replace the 'thinking' bubble
                console.log(`[GroupChat] VCP Response: Starting stream for ${agentName} (msgId: ${messageIdForAgentResponse})`);
                if (typeof sendStreamChunkToRenderer === 'function') {
                    sendStreamChunkToRenderer({
                        type: 'start',
                        messageId: messageIdForAgentResponse,
                        context: {
                            groupId,
                            topicId,
                            agentId,
                            agentName,
                            avatarUrl: agentConfig.avatarUrl,
                            avatarColor: agentConfig.avatarCalculatedColor,
                            isGroupMessage: true
                        }
                    });
                }
                
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                
                // This function will now be awaited
                async function processStreamForGroupAndUpdateHistory() {
                    let accumulatedResponse = "";
                    try {
                        while (true) {
                            const { done, value } = await reader.read();
                            if (done) {
                                console.log(`[GroupChat] VCP stream ended for ${agentName} (msgId: ${messageIdForAgentResponse})`);
                                const finalAiResponseEntry = { role: 'assistant', name: agentName, agentId: agentId, content: accumulatedResponse, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId, avatarUrl: agentConfig.avatarUrl, avatarColor: agentConfig.avatarCalculatedColor };
                                groupHistory.push(finalAiResponseEntry);
                                await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
                                if (typeof sendStreamChunkToRenderer === 'function') {
                                    sendStreamChunkToRenderer({ type: 'end', messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true }, fullResponse: accumulatedResponse });
                                }
                                break;
                            }
                            const chunkString = decoder.decode(value, { stream: true });
                            const lines = chunkString.split('\n').filter(line => line.trim() !== '');
                            for (const line of lines) {
                                if (line.startsWith('data: ')) {
                                    const jsonData = line.substring(5).trim();
                                    if (jsonData === '[DONE]') {
                                        console.log(`[GroupChat] VCP stream explicit [DONE] for ${agentName} (msgId: ${messageIdForAgentResponse})`);
                                        const doneAiResponseEntry = { role: 'assistant', name: agentName, agentId: agentId, content: accumulatedResponse, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId, avatarUrl: agentConfig.avatarUrl, avatarColor: agentConfig.avatarCalculatedColor };
                                        groupHistory.push(doneAiResponseEntry);
                                        await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
                                        if (typeof sendStreamChunkToRenderer === 'function') {
                                            sendStreamChunkToRenderer({ type: 'end', messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true }, fullResponse: accumulatedResponse });
                                        }
                                        return;
                                    }
                                    try {
                                        const parsedChunk = JSON.parse(jsonData);
                                        
                                        // 更全面的安全检查，处理各种可能的响应格式
                                        let hasContent = false;
                                        
                                        // 标准OpenAI格式 (choices[0].delta.content)
                                        if (parsedChunk.choices && Array.isArray(parsedChunk.choices) && parsedChunk.choices.length > 0) {
                                            const choice = parsedChunk.choices[0];
                                            if (choice && choice.delta) {
                                                if (typeof choice.delta.content === 'string' && choice.delta.content !== '') {
                                                    accumulatedResponse += choice.delta.content;
                                                    hasContent = true;
                                                }
                                            }
                                        }
                                        
                                        // 备选格式1 (delta.content)
                                        if (!hasContent && parsedChunk.delta) {
                                            if (typeof parsedChunk.delta.content === 'string' && parsedChunk.delta.content !== '') {
                                                accumulatedResponse += parsedChunk.delta.content;
                                                hasContent = true;
                                            }
                                        }
                                        
                                        // 备选格式2 (content)
                                        if (!hasContent && typeof parsedChunk.content === 'string' && parsedChunk.content !== '') {
                                            accumulatedResponse += parsedChunk.content;
                                            hasContent = true;
                                        }
                                        
                                        // 备选格式3 (message.content) - 某些API的格式
                                        if (!hasContent && parsedChunk.message && typeof parsedChunk.message.content === 'string' && parsedChunk.message.content !== '') {
                                            accumulatedResponse += parsedChunk.message.content;
                                            hasContent = true;
                                        }
                                        
                                        // 总是发送chunk事件，即使没有新内容（保持流的连续性）
                                        if (typeof sendStreamChunkToRenderer === 'function') {
                                            sendStreamChunkToRenderer({ 
                                                type: 'data', 
                                                chunk: parsedChunk, 
                                                messageId: messageIdForAgentResponse, 
                                                context: { groupId, topicId, agentId, agentName, isGroupMessage: true },
                                                hasContent: hasContent // 添加标志位，让前端知道是否有实际内容
                                            });
                                        }
                                    } catch (e) {
                                        console.error(`[GroupChat] Failed to parse VCP stream chunk JSON for ${agentName}:`, e, 'Raw data:', jsonData);
                                        if (typeof sendStreamChunkToRenderer === 'function') {
                                            sendStreamChunkToRenderer({ type: 'data', chunk: { raw: jsonData, error: 'json_parse_error' }, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true } });
                                        }
                                    }
                                }
                            }
                        }
                    } catch (streamError) {
                        if (streamError.name === 'AbortError') {
                            console.log(`[GroupChat] VCP stream for ${agentName} (msgId: ${messageIdForAgentResponse}) was aborted by user.`);
                            // Even though it was aborted, we save the content received so far.
                            const finalAiResponseEntry = { role: 'assistant', name: agentName, agentId: agentId, content: accumulatedResponse, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId, avatarUrl: agentConfig.avatarUrl, avatarColor: agentConfig.avatarCalculatedColor, interrupted: true };
                            groupHistory.push(finalAiResponseEntry);
                            await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
                            if (typeof sendStreamChunkToRenderer === 'function') {
                                // Send 'end' event to finalize the UI with the partial content.
                                sendStreamChunkToRenderer({ type: 'end', messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true }, fullResponse: accumulatedResponse, interrupted: true });
                            }
                        } else {
                            console.error(`[GroupChat] VCP stream reading error for ${agentName}:`, streamError);
                            const errorText = `[System Message] ${agentName} stream processing error: ${streamError.message}`;
                            const streamErrorResponseEntry = { role: 'assistant', name: agentName, agentId: agentId, content: errorText, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId };
                            groupHistory.push(streamErrorResponseEntry);
                            await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
                            if (typeof sendStreamChunkToRenderer === 'function') {
                                sendStreamChunkToRenderer({ type: 'error', error: `VCP stream reading error: ${streamError.message}`, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true } });
                            }
                        }
                    } finally {
                        reader.releaseLock();
                        activeRequestControllers.delete(messageIdForAgentResponse);
                        console.log(`[GroupChat] Active request controller removed for ${messageIdForAgentResponse}`);
                    }
                }
                await processStreamForGroupAndUpdateHistory();
            } else { // Non-streaming response
                console.log(`[GroupChat] VCP Response: Non-streaming for ${agentName}`);
                const vcpResponseJson = await response.json();
                const aiResponseContent = vcpResponseJson.choices && vcpResponseJson.choices.length > 0 ? vcpResponseJson.choices[0].message.content : "[AI failed to generate a valid response]";
                
                const aiResponseEntry = { role: 'assistant', name: agentName, agentId: agentId, content: aiResponseContent, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId, avatarUrl: agentConfig.avatarUrl, avatarColor: agentConfig.avatarCalculatedColor };
                groupHistory.push(aiResponseEntry);
                await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });

                // Directly send the 'end' event. The 'thinking' placeholder already exists.
                // Directly send the 'full_response' event. The 'thinking' placeholder already exists.
                if (typeof sendStreamChunkToRenderer === 'function') {
                    sendStreamChunkToRenderer({
                        type: 'full_response',
                        messageId: messageIdForAgentResponse,
                        fullResponse: aiResponseContent,
                        context: {
                            groupId,
                            topicId,
                            agentId,
                            agentName,
                            isGroupMessage: true
                        }
                    });
               }
               activeRequestControllers.delete(messageIdForAgentResponse);
           }
        } catch (error) {
            console.error(`[GroupChat] Error during response for Agent ${agentName}:`, error);
            const errorText = `[System Message] ${agentName} failed to respond: ${error.message}`;
            const errorResponse = { role: 'assistant', name: agentName, agentId: agentId, content: errorText, timestamp: Date.now(), id: messageIdForAgentResponse };
            groupHistory.push(errorResponse);
            await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
            if (typeof sendStreamChunkToRenderer === 'function') {
                // Finalize the 'thinking' bubble with an error
                sendStreamChunkToRenderer({ type: 'end', error: error.message, fullResponse: errorText, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId, agentName, isGroupMessage: true } });
            }
        }
        } // End of loop for agentsToRespond
    } else if (groupConfig.mode !== 'invite_only') { // 如果不是邀请模式，但也没有AI响应，也发送 no_ai_response
        console.log('[GroupChat] 根据群聊模式，没有 Agent 需要响应。');
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'no_ai_response', message: '当前没有AI需要发言。', messageId: userMessage.id, context: { groupId, topicId, isGroupMessage: true } });
        }
        // 即使没有AI响应，也可能需要总结话题（例如，用户连续发了几条消息）
    }


    // 总结话题的逻辑现在移到函数末尾，无论是否有AI自动回复，都可能触发
    // （例如，用户发了多条消息，即使在邀请模式下没有AI回复，也可能达到总结条件）
    const finalGroupConfigForSummary = await getAgentGroupConfig(groupId);
    const finalGroupHistoryPathForSummary = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', topicId, 'history.json');
    let finalGroupHistoryForSummary = [];
    if (await fs.pathExists(finalGroupHistoryPathForSummary)) {
        finalGroupHistoryForSummary = await fs.readJson(finalGroupHistoryPathForSummary);
    }
    
    if (finalGroupConfigForSummary && finalGroupHistoryForSummary.length > 0) {
        const latestGlobalVcpSettingsForSummary = await getVcpGlobalSettings();
        await triggerTopicSummarizationIfNeeded(groupId, topicId, finalGroupHistoryForSummary, latestGlobalVcpSettingsForSummary, finalGroupConfigForSummary, sendStreamChunkToRenderer);
    }
}


/**
 * 新增：处理特定Agent被邀请发言的逻辑
 * @param {string} groupId - 群组ID
 * @param {string} topicId - 话题ID
 * @param {string} invitedAgentId - 被邀请发言的Agent ID
 * @param {function} sendStreamChunkToRenderer - 用于发送流式数据的回调函数
 * @param {function} getAgentConfigById - 用于根据Agent ID获取其完整配置的函数
 * @returns {Promise<void>}
 */
async function handleInviteAgentToSpeak(groupId, topicId, invitedAgentId, sendStreamChunkToRenderer, getAgentConfigById) {
    console.log(`[GroupChat] handleInviteAgentToSpeak invoked for agent ${invitedAgentId} in group ${groupId}, topic ${topicId}.`);

    if (!mainAppPaths || !mainAppPaths.AGENT_GROUPS_DIR || !mainAppPaths.AGENT_DIR || !mainAppPaths.USER_DATA_DIR || typeof getAgentConfigById !== 'function') {
        console.error('[GroupChat] handleInviteAgentToSpeak: Critical paths or getAgentConfigById not initialized properly.');
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: '群聊模块关键路径或依赖未正确初始化 (邀请发言)。', context: { groupId, topicId, agentId: invitedAgentId, isGroupMessage: true } });
        }
        return;
    }

    const groupConfig = await getAgentGroupConfig(groupId);
    if (!groupConfig) {
        console.error(`[GroupChat] 未找到群组配置: ${groupId} (邀请发言)`);
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: `未找到群组配置: ${groupId}`, context: { groupId, topicId, agentId: invitedAgentId, isGroupMessage: true } });
        }
        return;
    }

    const agentConfig = await getAgentConfigById(invitedAgentId);
    if (!agentConfig || agentConfig.error) {
        console.error(`[GroupChat] 未找到或无法加载被邀请的群成员 ${invitedAgentId} 的配置: ${agentConfig?.error}`);
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: `未找到受邀Agent ${invitedAgentId} 的配置。`, context: { groupId, topicId, agentId: invitedAgentId, isGroupMessage: true } });
        }
        return;
    }
    
    const groupHistoryPath = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', topicId, 'history.json');
    await fs.ensureDir(path.dirname(groupHistoryPath));
    let groupHistory = [];
    if (await fs.pathExists(groupHistoryPath)) {
        groupHistory = await fs.readJson(groupHistoryPath);
    }

    const globalVcpSettings = await getVcpGlobalSettings();
    const agentName = agentConfig.name || invitedAgentId; // 修复：如果名称丢失，回退到 invitedAgentId
    const messageIdForAgentResponse = `msg_group_invited_${groupId}_${topicId}_${invitedAgentId}_${Date.now()}`;

    // 1. 构建 SystemPrompt
    let combinedSystemPrompt = agentConfig.systemPrompt || `你是${agentName}。`;
    if (groupConfig.groupPrompt) {
        let groupPrompt = groupConfig.groupPrompt;
        // 处理 VCPChatGroupSessionWatcher 占位符
        if (groupPrompt.includes(GROUP_SESSION_WATCHER_PLACEHOLDER)) {
            const sessionWatcherInfo = await getGroupSessionWatcher(groupId, topicId);
            groupPrompt = groupPrompt.replace(new RegExp(GROUP_SESSION_WATCHER_PLACEHOLDER, 'g'), JSON.stringify(sessionWatcherInfo));
        }
        combinedSystemPrompt += `\n\n[群聊设定]:\n${groupPrompt}`;
    }

    // 2. 构建上下文结构 (基于最新的 groupHistory)
    // 注意：这里需要确定用户消息是否应该从 groupHistory 的最后一条获取，或者由调用者传递
    // 假设 groupHistory 已经包含了最新的用户消息（如果有的话）
    const contextForAgentPromises = groupHistory.map(async (msg, index, arr) => {
        const speakerName = msg.name || (msg.role === 'user' ? (globalVcpSettings.userName || '用户') : (msg.agentName || 'AI')); // Use msg.agentName if available for AI
        
        let textForAIContext = (typeof msg.content === 'string') ? msg.content : (msg.content?.text || '');
        
        // 检查当前消息是否为上下文中的最后一条用户消息
        const isLastUserMessageInContext = msg.role === 'user' && !arr.slice(index + 1).some(futureMsg => futureMsg.role === 'user');

        // 仅当是最后一条用户消息时，才解析Canvas占位符
        if (isLastUserMessageInContext && textForAIContext.includes(CANVAS_PLACEHOLDER)) {
            try {
                const canvasData = await canvasHandlers.handleGetLatestCanvasContent();
                if (canvasData && !canvasData.error) {
                    const formattedCanvasContent = `
[Canvas Content]
${canvasData.content || ''}
[Canvas Path]
${canvasData.path || 'No file path'}
[Canvas Errors]
${canvasData.errors || 'No errors'}
`;
                    textForAIContext = textForAIContext.replace(new RegExp(CANVAS_PLACEHOLDER, 'g'), formattedCanvasContent);
                } else {
                    console.error("[GroupChat Invite] Failed to get latest canvas content:", canvasData?.error);
                    textForAIContext = textForAIContext.replace(new RegExp(CANVAS_PLACEHOLDER, 'g'), '\n[Canvas content could not be loaded]\n');
                }
            } catch (error) {
                console.error("[GroupChat Invite] Error processing canvas content:", error);
                textForAIContext = textForAIContext.replace(new RegExp(CANVAS_PLACEHOLDER, 'g'), '\n[Error processing canvas content]\n');
            }
        }

        if (msg.attachments && msg.attachments.length > 0) {
            for (const att of msg.attachments) {
                if (att._fileManagerData && typeof att._fileManagerData.extractedText === 'string' && att._fileManagerData.extractedText.trim() !== '') {
                    textForAIContext += `

[附加文件: ${att.name || '未知文件'}]
${att._fileManagerData.extractedText}
[/附加文件结束: ${att.name || '未知文件'}]`;
                } else if (att._fileManagerData && att.type && !att.type.startsWith('image/')) {
                    textForAIContext += `\n\n[附加文件: ${att.name || '未知文件'} (无法预览文本内容)]`;
                } else if (!att._fileManagerData) {
                    console.warn(`[GroupChat Invite Context] Historical message attachment for "${att.name}" is missing _fileManagerData. Text content cannot be appended.`);
                }
            }
        }
        
        const contentWithSpeakerTag = `[${speakerName}的发言]: ${textForAIContext}`;
        const vcpMessageContent = [{ type: 'text', text: contentWithSpeakerTag }];

        if (msg.attachments && msg.attachments.length > 0) {
            for (const att of msg.attachments) {
                const isSupportedMediaType = att._fileManagerData.type.startsWith('image/') || att._fileManagerData.type.startsWith('audio/') || att._fileManagerData.type.startsWith('video/');
                if (att._fileManagerData && att._fileManagerData.type && isSupportedMediaType && att._fileManagerData.internalPath) {
                    try {
                        const result = await fileManager.getFileAsBase64(att._fileManagerData.internalPath);
                        if (result && result.success && result.base64Frames && result.base64Frames.length > 0) {
                            vcpMessageContent.push({
                                type: 'image_url',
                                image_url: { url: `data:${att._fileManagerData.type};base64,${result.base64Frames[0]}` }
                            });
                        } else {
                             console.warn(`[GroupChat Invite] Failed to get base64 for media ${att.name || att._fileManagerData.name}: ${result?.error}`);
                        }
                    } catch (e) {
                        console.error(`[GroupChat Invite] Error getting base64 for media ${att.name || att._fileManagerData.name} in context:`, e);
                    }
                }
            }
        }
        
        return {
            role: msg.role,
            content: vcpMessageContent,
        };
    });
    
    const contextForAgent = await Promise.all(contextForAgentPromises);

    // 3. 构建 InvitePrompt
    let invitePromptContent = (groupConfig.invitePrompt || `现在轮到你 {{VCPChatAgentName}} 发言了。`).replace(/{{VCPChatAgentName}}/g, agentName);

    let messagesForAI = [];
    if (combinedSystemPrompt.trim()) {
        messagesForAI.push({ role: 'system', content: combinedSystemPrompt });
    }
    messagesForAI.push(...contextForAgent);
    messagesForAI.push({ role: 'user', content: [{ type: 'text', text: invitePromptContent }], name: (globalVcpSettings.userName || '用户') }); // 模拟用户触发
    // 添加净化器处理  
    if (globalVcpSettings.enableContextSanitizer === true) {    
        const sanitizerDepth = globalVcpSettings.contextSanitizerDepth !== undefined ? globalVcpSettings.contextSanitizerDepth : 2;    
        console.log(`[GroupChat Context Sanitizer] Enabled with depth: ${sanitizerDepth}`);    
          
        const systemMessages = messagesForAI.filter(m => m.role === 'system');    
        const nonSystemMessages = messagesForAI.filter(m => m.role !== 'system');    
          
        // 使用已加载的净化器
        const sanitizedNonSystemMessages = contextSanitizer.sanitizeMessages(nonSystemMessages, sanitizerDepth);
          
        messagesForAI = [...systemMessages, ...sanitizedNonSystemMessages];
          
        console.log(`[GroupChat Context Sanitizer] Messages processed successfully`);    
    }
    // --- Agent Bubble Theme Injection ---
    if (globalVcpSettings.enableAgentBubbleTheme) {
        let systemMsgIndex = messagesForAI.findIndex(m => m.role === 'system');
        if (systemMsgIndex === -1) {
            messagesForAI.unshift({ role: 'system', content: '' });
            systemMsgIndex = 0;
        }
        
        const injection = '为你在群聊中构建独特的个性气泡，输出规范要求：{{VarDivRender}}';
        if (!messagesForAI[systemMsgIndex].content.includes(injection)) {
            messagesForAI[systemMsgIndex].content += `\n\n${injection}`;
            messagesForAI[systemMsgIndex].content = messagesForAI[systemMsgIndex].content.trim();
        }
    }
    // --- End of Injection ---

    const modelResolution = resolveEffectiveModel(groupConfig, agentConfig);
    if (!globalVcpSettings.vcpUrl) {
        const errorMsg = `Agent ${agentName} (${invitedAgentId}) 无法响应（邀请）：VCP URL 未配置。`;
        console.error(`[GroupChat Invite] ${errorMsg}`);
        const errorResponse = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: `[系统消息] ${errorMsg}`, timestamp: Date.now(), id: messageIdForAgentResponse };
        groupHistory.push(errorResponse);
        await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: errorMsg, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true } });
        }
        return;
    }

    if (!modelResolution.effectiveModel) {
        const modelHint = modelResolution.usingUnifiedModel
            ? '已启用群组统一模型，但群组统一模型为空。'
            : '当前成员未配置模型。';
        const errorMsg = `Agent ${agentName} (${invitedAgentId}) 无法响应（邀请）：${modelHint}`;
        console.error(`[GroupChat Invite] ${errorMsg}`);
        const errorResponse = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: `[系统消息] ${errorMsg}`, timestamp: Date.now(), id: messageIdForAgentResponse };
        groupHistory.push(errorResponse);
        await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: errorMsg, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true } });
        }
        return;
    }

    try {
        // Always send 'agent_thinking' before the fetch call
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({
                type: 'agent_thinking',
                messageId: messageIdForAgentResponse,
                context: {
                    groupId,
                    topicId,
                    agentId: invitedAgentId,
                    agentName,
                    avatarUrl: agentConfig.avatarUrl,
                    avatarColor: agentConfig.avatarCalculatedColor,
                    isGroupMessage: true
                }
            });
            await new Promise(resolve => setTimeout(resolve, 200)); // Give renderer time to create the bubble
        }

        const modelConfigForAgent = {
           model: modelResolution.effectiveModel,
            temperature: parseFloat(agentConfig.temperature),
            max_tokens: agentConfig.maxOutputTokens ? parseInt(agentConfig.maxOutputTokens) : undefined,
            stream: agentConfig.streamOutput === true || String(agentConfig.streamOutput) === 'true'
        };

        // 添加超时控制
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 60000); // 60秒超时
        activeRequestControllers.set(messageIdForAgentResponse, controller);
        
        let response;
        try {
            response = await fetch(globalVcpSettings.vcpUrl, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${globalVcpSettings.vcpApiKey}`
                },
                body: JSON.stringify({
                    messages: messagesForAI,
                    model: modelConfigForAgent.model,
                    temperature: modelConfigForAgent.temperature,
                    stream: modelConfigForAgent.stream,
                    max_tokens: modelConfigForAgent.max_tokens,
                    messageId: messageIdForAgentResponse // 包含 messageId 以支持后端中断
                }),
                signal: controller.signal
            });
        } catch (fetchError) {
            clearTimeout(timeoutId);
            if (fetchError.name === 'AbortError') {
                console.log(`[GroupChat Invite] VCP fetch for ${agentName} was aborted before stream began.`);
                if (typeof sendStreamChunkToRenderer === 'function') {
                    sendStreamChunkToRenderer({ type: 'end', error: '用户中止', fullResponse: '', messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true }, interrupted: true });
                }
                return;
            }
            throw fetchError;
        } finally {
            clearTimeout(timeoutId);
        }

        if (!response.ok) {
            const errorText = await response.text();
            console.error(`[GroupChat Invite] VCP request failed for ${agentName}. Status: ${response.status}, Response Text:`, errorText);
            let errorData = { message: `Server returned status ${response.status}`, details: errorText };
            try { const parsedError = JSON.parse(errorText); if (typeof parsedError === 'object' && parsedError !== null) errorData = parsedError; } catch (e) { /* Not JSON */ }
            
            const errorMessageToPropagate = `VCP request failed (invite): ${response.status} - ${errorData.message || errorData.error || (typeof errorData === 'string' ? errorData : 'Unknown server error')}`;
            const errorResponseEntry = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: `[System Message] ${errorMessageToPropagate}`, timestamp: Date.now(), id: messageIdForAgentResponse };
            groupHistory.push(errorResponseEntry);
            await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
 
            if (typeof sendStreamChunkToRenderer === 'function') {
                sendStreamChunkToRenderer({ type: 'end', error: errorMessageToPropagate, fullResponse: `[错误] ${errorMessageToPropagate}`, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true } });
            }
            return;
        }

        if (modelConfigForAgent.stream) {
            // Send 'start' to replace 'thinking'
            if (typeof sendStreamChunkToRenderer === 'function') {
                sendStreamChunkToRenderer({
                    type: 'start',
                    messageId: messageIdForAgentResponse,
                    context: {
                        groupId,
                        topicId,
                        agentId: invitedAgentId,
                        agentName,
                        avatarUrl: agentConfig.avatarUrl,
                        avatarColor: agentConfig.avatarCalculatedColor,
                        isGroupMessage: true
                    }
                });
            }

            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            
            async function processStreamForInvitedAgent() {
                let accumulatedResponse = "";
                try {
                    while (true) {
                        const { done, value } = await reader.read();
                        if (done) {
                            const finalAiResponseEntry = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: accumulatedResponse, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId, avatarUrl: agentConfig.avatarUrl, avatarColor: agentConfig.avatarCalculatedColor };
                            groupHistory.push(finalAiResponseEntry);
                            await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
                            if (typeof sendStreamChunkToRenderer === 'function') {
                                sendStreamChunkToRenderer({ type: 'end', messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true }, fullResponse: accumulatedResponse });
                            }
                            break;
                        }
                        const chunkString = decoder.decode(value, { stream: true });
                        const lines = chunkString.split('\n').filter(line => line.trim() !== '');
                        for (const line of lines) {
                            if (line.startsWith('data: ')) {
                                const jsonData = line.substring(5).trim();
                                if (jsonData === '[DONE]') {
                                    const doneAiResponseEntry = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: accumulatedResponse, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId, avatarUrl: agentConfig.avatarUrl, avatarColor: agentConfig.avatarCalculatedColor };
                                    groupHistory.push(doneAiResponseEntry);
                                    await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
                                    if (typeof sendStreamChunkToRenderer === 'function') {
                                        sendStreamChunkToRenderer({ type: 'end', messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true }, fullResponse: accumulatedResponse });
                                    }
                                    return;
                                }
                                try {
                                    const parsedChunk = JSON.parse(jsonData);
                                    
                                    // 更全面的安全检查，处理各种可能的响应格式
                                    let hasContent = false;
                                    
                                    // 标准OpenAI格式 (choices[0].delta.content)
                                    if (parsedChunk.choices && Array.isArray(parsedChunk.choices) && parsedChunk.choices.length > 0) {
                                        const choice = parsedChunk.choices[0];
                                        if (choice && choice.delta) {
                                            if (typeof choice.delta.content === 'string' && choice.delta.content !== '') {
                                                accumulatedResponse += choice.delta.content;
                                                hasContent = true;
                                            }
                                        }
                                    }
                                    
                                    // 备选格式1 (delta.content)
                                    if (!hasContent && parsedChunk.delta) {
                                        if (typeof parsedChunk.delta.content === 'string' && parsedChunk.delta.content !== '') {
                                            accumulatedResponse += parsedChunk.delta.content;
                                            hasContent = true;
                                        }
                                    }
                                    
                                    // 备选格式2 (content)
                                    if (!hasContent && typeof parsedChunk.content === 'string' && parsedChunk.content !== '') {
                                        accumulatedResponse += parsedChunk.content;
                                        hasContent = true;
                                    }
                                    
                                    // 备选格式3 (message.content) - 某些API的格式
                                    if (!hasContent && parsedChunk.message && typeof parsedChunk.message.content === 'string' && parsedChunk.message.content !== '') {
                                        accumulatedResponse += parsedChunk.message.content;
                                        hasContent = true;
                                    }
                                    
                                    // 总是发送chunk事件，即使没有新内容（保持流的连续性）
                                    if (typeof sendStreamChunkToRenderer === 'function') {
                                        sendStreamChunkToRenderer({ 
                                            type: 'data', 
                                            chunk: parsedChunk, 
                                            messageId: messageIdForAgentResponse, 
                                            context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true },
                                            hasContent: hasContent // 添加标志位，让前端知道是否有实际内容
                                        });
                                    }
                                } catch (e) {
                                    console.error(`[GroupChat Invite] Failed to parse VCP stream chunk JSON for ${agentName}:`, e, 'Raw data:', jsonData);
                                    // 添加错误chunk发送，保持一致性
                                    if (typeof sendStreamChunkToRenderer === 'function') {
                                        sendStreamChunkToRenderer({ type: 'data', chunk: { raw: jsonData, error: 'json_parse_error' }, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true } });
                                    }
                                }
                            }
                        }
                    }
                } catch (streamError) {
                    if (streamError.name === 'AbortError') {
                        console.log(`[GroupChat Invite] VCP stream for ${agentName} (msgId: ${messageIdForAgentResponse}) was aborted by user.`);
                        // Save the content received so far upon abortion.
                        const finalAiResponseEntry = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: accumulatedResponse, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId, avatarUrl: agentConfig.avatarUrl, avatarColor: agentConfig.avatarCalculatedColor, interrupted: true };
                        groupHistory.push(finalAiResponseEntry);
                        await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
                        if (typeof sendStreamChunkToRenderer === 'function') {
                            sendStreamChunkToRenderer({ type: 'end', messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true }, fullResponse: accumulatedResponse, interrupted: true });
                        }
                    } else {
                        console.error(`[GroupChat Invite] VCP stream reading error for ${agentName}:`, streamError);
                        const errorText = `[System Message] ${agentName} stream processing error (invite): ${streamError.message}`;
                        const streamErrorResponseEntry = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: errorText, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId };
                        groupHistory.push(streamErrorResponseEntry);
                        await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
                        if (typeof sendStreamChunkToRenderer === 'function') {
                            sendStreamChunkToRenderer({ type: 'error', error: `VCP stream reading error (invite): ${streamError.message}`, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true } });
                        }
                    }
                } finally {
                    reader.releaseLock();
                    activeRequestControllers.delete(messageIdForAgentResponse);
                    console.log(`[GroupChat Invite] Active request controller removed for ${messageIdForAgentResponse}`);
                }
            }
            await processStreamForInvitedAgent();
        } else { // Non-streaming response
            const vcpResponseJson = await response.json();
            const aiResponseContent = vcpResponseJson.choices && vcpResponseJson.choices.length > 0 ? vcpResponseJson.choices[0].message.content : "[AI failed to generate a valid response (invite)]";
            
            const aiResponseEntry = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: aiResponseContent, timestamp: Date.now(), id: messageIdForAgentResponse, isGroupMessage: true, groupId, topicId, avatarUrl: agentConfig.avatarUrl, avatarColor: agentConfig.avatarCalculatedColor };
            groupHistory.push(aiResponseEntry);
            await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
 
            if (typeof sendStreamChunkToRenderer === 'function') {
                sendStreamChunkToRenderer({
                    type: 'full_response',
                    messageId: messageIdForAgentResponse,
                    fullResponse: aiResponseContent,
                    context: {
                        groupId,
                        topicId,
                        agentId: invitedAgentId,
                        agentName,
                        isGroupMessage: true
                    }
                });
            }
            activeRequestControllers.delete(messageIdForAgentResponse);
        }

    } catch (error) {
        console.error(`[GroupChat Invite] Error responding for agent ${agentName}:`, error);
        const errorText = `[System Message] ${agentName} failed to respond (invite): ${error.message}`;
        const errorResponse = { role: 'assistant', name: agentName, agentId: invitedAgentId, content: errorText, timestamp: Date.now(), id: messageIdForAgentResponse };
        groupHistory.push(errorResponse);
        await fs.writeJson(groupHistoryPath, groupHistory, { spaces: 2 });
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'end', error: error.message, fullResponse: errorText, messageId: messageIdForAgentResponse, context: { groupId, topicId, agentId: invitedAgentId, agentName, isGroupMessage: true } });
        }
    }

    // 邀请发言后也尝试总结话题
    const finalGroupConfigForSummary = await getAgentGroupConfig(groupId);
    const finalGroupHistoryPathForSummary = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', topicId, 'history.json');
    let finalGroupHistoryForSummary = [];
    if (await fs.pathExists(finalGroupHistoryPathForSummary)) {
        finalGroupHistoryForSummary = await fs.readJson(finalGroupHistoryPathForSummary);
    }
    
    if (finalGroupConfigForSummary && finalGroupHistoryForSummary.length > 0) {
        const latestGlobalVcpSettingsForSummary = await getVcpGlobalSettings();
        await triggerTopicSummarizationIfNeeded(groupId, topicId, finalGroupHistoryForSummary, latestGlobalVcpSettingsForSummary, finalGroupConfigForSummary, sendStreamChunkToRenderer);
    }
}


/**
 * 清理和格式化从AI获取的话题标题
 * @param {string} rawTitle - AI返回的原始标题
 * @returns {string} 清理后的标题
 */
function cleanSummarizedTitle(rawTitle) {
    if (!rawTitle || typeof rawTitle !== 'string') return "AI总结话题";

    let cleanedTitle = rawTitle.split('\n')[0].trim(); // 取第一行
    // 移除常见标点、数字编号、特定前后缀
    cleanedTitle = cleanedTitle.replace(/[^\u4e00-\u9fa5a-zA-Z0-9\s（）()-]/g, ''); // 保留一些常用括号
    cleanedTitle = cleanedTitle.replace(/^\s*[\d①②③④⑤⑥⑦⑧⑨⑩❶❷❸❹❺❻❼❽❾❿一二三四五六七八九十]+\s*[\.\uff0e\s、]\s*/, ''); // 移除 "1. ", "①. " 等
    cleanedTitle = cleanedTitle.replace(/^(话题|标题|总结|Topic|Title|Summary)[:：\s]*/i, '');
    cleanedTitle = cleanedTitle.replace(/[。？！，、；：“”‘’（）《》〈〉【】「」『』]/g, ''); // 移除特定标点
    cleanedTitle = cleanedTitle.replace(/\s+/g, ' ').trim(); // 合并多个空格为一个，并去除首尾空格

    if (cleanedTitle.length > 15) { // 限制长度
        cleanedTitle = cleanedTitle.substring(0, 15);
    }
    return cleanedTitle || "AI总结话题"; // 如果清理后为空，返回默认
}


/**
 * 触发话题总结（如果需要）
 * @param {string} groupId
 * @param {string} topicId
 * @param {Array<object>} groupHistory - 当前话题的完整历史记录
 * @param {object} globalVcpSettings - 全局VCP设置
 * @param {object} groupConfig - 当前群组的配置
 * @param {function} sendStreamChunkToRenderer - 用于发送通知回渲染器的函数
 */
async function triggerTopicSummarizationIfNeeded(groupId, topicId, groupHistory, globalVcpSettings, groupConfig, sendStreamChunkToRenderer) {
    if (!groupConfig || !groupConfig.topics) return;

    const currentTopic = groupConfig.topics.find(t => t.id === topicId);
    if (!currentTopic) {
        console.warn(`[TopicSummary] 尝试总结时未找到话题 ${topicId} in group ${groupId}`);
        return;
    }

    const isDefaultTitle = DEFAULT_TOPIC_NAMES.includes(currentTopic.name) || currentTopic.name.startsWith("新话题");

    if (groupHistory.length >= MIN_MESSAGES_FOR_SUMMARY && isDefaultTitle) {
        console.log(`[TopicSummary] 话题 ${topicId} (${currentTopic.name}) 满足总结条件。消息数: ${groupHistory.length}`);

        const recentMessagesContent = groupHistory.slice(-MIN_MESSAGES_FOR_SUMMARY).map(msg => {
            const speakerName = msg.name || (msg.role === 'user' ? (globalVcpSettings.userName || '用户') : 'AI成员');
            // 确保从消息内容中提取文本，即使它是对象 { text: '...' }
            let contentText = typeof msg.content === 'string' ? msg.content : (msg.content?.text || '');
            // 如果消息有附件且包含提取的文本，也将其包含在内
            if (msg.attachments && msg.attachments.length > 0) {
                for (const att of msg.attachments) {
                    if (att._fileManagerData && typeof att._fileManagerData.extractedText === 'string' && att._fileManagerData.extractedText.trim() !== '') {
                        contentText += `

[附加文件: ${att.name || '未知文件'}]
${att._fileManagerData.extractedText}
[/附加文件结束: ${att.name || '未知文件'}]`;
                    } else if (att._fileManagerData && att.type && !att.type.startsWith('image/')) {
                        contentText += `\n\n[附加文件: ${att.name || '未知文件'} (无法预览文本内容)]`;
                    }
                }
            }
            return `${speakerName}: ${contentText}`;
        }).join('\n\n');

        const summaryPrompt = `请根据以下群聊对话内容，仅返回一个简洁的话题标题。要求：1. 标题长度控制在10个汉字或20个英文字符以内。2. 标题本身不能包含任何标点符号、数字编号或任何非标题文字。3. 直接给出标题文字，不要添加任何解释或前缀。\n\n对话内容：\n${recentMessagesContent}`;
        
        const messagesForAISummary = [{ role: 'user', content: [{ type: 'text', text: summaryPrompt }] }];

        try {
            if (!globalVcpSettings.vcpUrl) {
                console.error("[TopicSummary] VCP URL not configured. Cannot summarize topic.");
                return;
            }

            // 添加超时控制
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 20000); // 20秒超时（总结用时较短）
            
            let response;
            try {
                response = await fetch(globalVcpSettings.vcpUrl, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${globalVcpSettings.vcpApiKey}`
                    },
                    body: JSON.stringify({
                        messages: messagesForAISummary,
                        model: globalVcpSettings.topicSummaryModel || 'gemini-2.5-flash-preview-05-20',
                        temperature: 0.3,
                        max_tokens: 4000,
                        stream: false // 总结通常不需要流式
                    }),
                    signal: controller.signal
                });
            } catch (fetchError) {
                clearTimeout(timeoutId);
                if (fetchError.name === 'AbortError') {
                    console.error(`[TopicSummary] VCP request timeout after 20 seconds`);
                    return;
                }
                throw fetchError;
            } finally {
                clearTimeout(timeoutId);
            }

            if (!response.ok) {
                const errorText = await response.text();
                console.error(`[TopicSummary] VCP请求失败. Status: ${response.status}, Response: ${errorText}`);
                return;
            }

            const summaryResponseJson = await response.json();
            if (summaryResponseJson.choices && summaryResponseJson.choices.length > 0) {
                const rawTitle = summaryResponseJson.choices[0].message.content;
                const newTitle = cleanSummarizedTitle(rawTitle);
                
                // 检查新标题是否有效且与原标题不同
                if (newTitle && newTitle !== "AI总结话题" && newTitle !== currentTopic.name) {
                    console.log(`[TopicSummary] 话题 ${topicId} 新标题: "${newTitle}" (原: "${currentTopic.name}")`);
                    const saveResult = await saveGroupTopicTitle(groupId, topicId, newTitle);
                    if (saveResult.success && sendStreamChunkToRenderer) {
                        // 通知渲染器话题标题已更新
                        sendStreamChunkToRenderer({
                            type: 'topic_updated', // Use a type to distinguish from chat chunks
                            context: {
                                groupId,
                                topicId,
                                newTitle,
                                topics: saveResult.topics,
                                isGroupMessage: true // Maintain context structure
                            }
                        });
                        console.log(`[TopicSummary] 已通知渲染器话题 ${topicId} 标题更新。`);
                    } else if (!saveResult.success) {
                        console.error(`[TopicSummary] 保存新话题标题失败: ${saveResult.error}`);
                    }
                } else if (newTitle === "AI总结话题") {
                    console.log(`[TopicSummary] AI未能有效总结话题 ${topicId} (返回默认值)，不更新标题。原始AI返回: "${rawTitle}"`);
                } else if (newTitle === currentTopic.name) {
                    console.log(`[TopicSummary] AI总结的标题与原标题 "${currentTopic.name}" 相同，不更新。`);
                } else {
                    console.log(`[TopicSummary] 生成的标题为空或无效，不更新: "${newTitle}"`);
                }
            } else {
                console.warn('[TopicSummary] AI未能生成有效的总结标题或响应格式不符。Response:', summaryResponseJson);
            }
        } catch (error) {
            console.error('[TopicSummary] 调用VCP进行话题总结时出错:', error);
        }
    }
}


/**
 * NatureRandom 模式：决定哪些 Agent 发言
 * @param {Array<object>} activeMembersConfigs - 当前群聊中活跃的成员 Agent 的完整配置对象数组
 * @param {Array<object>} history - 当前聊天历史
 * @param {object} groupConfig - 当前群组的配置
 * @param {object} userMessageEntry - 用户最新发送的消息条目 { content: "用户说的文本内容" }
 * @returns {Array<object>} - 需要发言的 Agent 配置对象数组，按发言顺序排列
 */
function determineNatureRandomSpeakers(activeMembersConfigs, history, groupConfig, userMessageEntry) {
    const speakers = [];
    const spokenThisTurn = new Set(); // 存储已确定发言的 Agent ID
    const userMessageText = (userMessageEntry.content && typeof userMessageEntry.content === 'string')
                            ? userMessageEntry.content.toLowerCase()
                            : "";

    // 修复2：排除最后一项（当前用户消息），contextText 只代表纯历史上下文。
    // 当前用户消息通过 userMessageText 独立检测，避免与历史上下文混淆，
    // 防止"旧话题残留在窗口"与"当前消息提及"无法区分的问题。
    const CONTEXT_WINDOW = 8;
    const recentHistory = history.slice(-(CONTEXT_WINDOW + 1), -1);
    const contextText = recentHistory
        .map(msg => {
            const rawContent = typeof msg.content === 'string' ? msg.content : (msg.content?.text || '');
            return rawContent.replace(/^\[.*?的发言\]:\s*/, '');
        })
        .join(' \n ')
        .toLowerCase();

    // 读取发言触发模式，默认 strict（向后兼容）
    const tagMatchMode = groupConfig.tagMatchMode || 'strict';

    // 优先级1: @角色名 (直接在最新消息中匹配) — 两种模式相同
    activeMembersConfigs.forEach(memberConfig => {
        if (userMessageText.includes(`@${memberConfig.name.toLowerCase()}`)) {
            if (!spokenThisTurn.has(memberConfig.id)) {
                speakers.push(memberConfig);
                spokenThisTurn.add(memberConfig.id);
                console.log(`[NatureRandom] @${memberConfig.name} triggered by direct mention.`);
            }
        }
    });

    // 优先级2: Tag 匹配 — 行为因模式而异
    activeMembersConfigs.forEach(memberConfig => {
        if (spokenThisTurn.has(memberConfig.id)) return;

        const tagsString = groupConfig.memberTags ? groupConfig.memberTags[memberConfig.id] : '';
        if (!tagsString) return;
        const tags = tagsString.split(/,|，/).map(t => t.trim().toLowerCase()).filter(t => t);

        if (tagMatchMode === 'natural') {
            // Natural 模式：按 tag 来源分档处理

            // 检查 tag 是否出现在历史上其他人（其他 Agent 或历史用户消息）的消息中
            const tagInHistoricalOtherMessages = recentHistory
                .filter(msg => msg.agentId !== memberConfig.id)
                .some(msg => {
                    const content = (typeof msg.content === 'string'
                        ? msg.content : (msg.content?.text || ''))
                        .replace(/^\[.*?的发言\]:\s*/, '')
                        .toLowerCase();
                    return tags.some(tag => content.includes(tag));
                });

            // 修复2：当前用户消息独立检测，是最强的外部信号
            const tagInCurrentUserMessage = tags.some(tag => userMessageText.includes(tag));

            const tagInOtherMessages = tagInHistoricalOtherMessages || tagInCurrentUserMessage;

            // 检查 tag 是否仅出现在自身历史消息中（自我污染场景）
            const tagOnlyInOwnMessages = !tagInOtherMessages &&
                recentHistory
                    .filter(msg => msg.agentId === memberConfig.id)
                    .some(msg => {
                        const content = (typeof msg.content === 'string'
                            ? msg.content : (msg.content?.text || '')).toLowerCase();
                        return tags.some(tag => content.includes(tag));
                    });

            if (tags.some(tag => userMessageText.includes(`@${tag}`))) {
                // @tag 直接提及 → 强信号，100% 触发
                speakers.push(memberConfig);
                spokenThisTurn.add(memberConfig.id);
                console.log(`[NatureRandom/Natural] @tag trigger for ${memberConfig.name}.`);

            } else if (tagInOtherMessages) {
                // tag 来自用户或其他 Agent → 话题真实相关，100% 触发
                speakers.push(memberConfig);
                spokenThisTurn.add(memberConfig.id);
                console.log(`[NatureRandom/Natural] Tag in others' messages, triggering ${memberConfig.name}.`);

            } else if (tagOnlyInOwnMessages) {
                // tag 仅来自自身历史消息 → 动态概率（区分"话题延续"与"自我污染"）
                const lastAiMsg = [...history].slice(0, -1).reverse()
                    .find(msg => msg.role === 'assistant');
                const isLastSpeaker = lastAiMsg?.agentId === memberConfig.id;
                const ownMsgCount = recentHistory
                    .filter(msg => msg.agentId === memberConfig.id).length;

                // isLastSpeaker=true: 刚发言，用户可能在延续对话，概率随发言次数递增，上限 0.75
                // isLastSpeaker=false: 更可能是自我污染，保持低概率
                const speakChance = isLastSpeaker
                    ? Math.min(0.5 + ownMsgCount * 0.1, 0.75)
                    : 0.2;

                if (Math.random() < speakChance) {
                    speakers.push(memberConfig);
                    spokenThisTurn.add(memberConfig.id);
                    console.log(`[NatureRandom/Natural] Self-context trigger for ${memberConfig.name} (isLastSpeaker=${isLastSpeaker}, chance=${speakChance.toFixed(2)}).`);
                }
            }

        } else {
            // Strict 模式：tag 在历史上下文 OR 在当前用户消息 OR @tag → 100% 触发
            // 修复2：userMessageText.includes(tag) 显式替代原先通过 contextText 捕获当前消息 tag 的方式
            if (tags.some(tag => contextText.includes(tag) || userMessageText.includes(tag))) {
                speakers.push(memberConfig);
                spokenThisTurn.add(memberConfig.id);
                console.log(`[NatureRandom/Strict] Tag match for ${memberConfig.name}.`);
            }
        }
    });

    // 优先级3: @所有人 (在最新消息中匹配) — 两种模式相同
    if (userMessageText.includes('@所有人')) {
        activeMembersConfigs.forEach(memberConfig => {
            if (!spokenThisTurn.has(memberConfig.id)) {
                speakers.push(memberConfig);
                spokenThisTurn.add(memberConfig.id);
                console.log(`[NatureRandom] @所有人 triggered for ${memberConfig.name}.`);
            }
        });
    }

    // 优先级4: 概率发言（对于未被上述规则触发的）
    // 修复1：natural 模式禁用 contextText 提升（P2 已处理所有 tag 相关逻辑）
    // 若保留，P2 精心校准的概率会被 P4 的 85% 覆盖，导致自我污染场景实际概率高达 88%+
    const nonTriggeredMembers = activeMembersConfigs.filter(member => !spokenThisTurn.has(member.id));
    const baseRandomSpeakProbability = 0.15;

    nonTriggeredMembers.forEach(memberConfig => {
        let speakChance = baseRandomSpeakProbability;

        if (tagMatchMode === 'strict') {
            // strict 模式保留 contextText 提升（向后兼容）
            const tagsString = groupConfig.memberTags ? groupConfig.memberTags[memberConfig.id] : '';
            if (tagsString) {
                const tags = tagsString.split(/,|，/).map(t => t.trim().toLowerCase()).filter(t => t);
                if (tags.some(tag => contextText.includes(tag))) {
                    speakChance = 0.85;
                    console.log(`[NatureRandom/Strict] Increased speak probability for ${memberConfig.name}.`);
                }
            }
        }
        // natural 模式：只用基础 15%，不做 tag 提升

        if (Math.random() < speakChance) {
            speakers.push(memberConfig);
            spokenThisTurn.add(memberConfig.id);
            console.log(`[NatureRandom] Probabilistic speak for ${memberConfig.name} (chance=${speakChance.toFixed(2)}).`);
        }
    });

    // 优先级5: 保底发言（如果以上都没有触发任何 Agent）
    // 修复3：speakers.length === 0 时 spokenThisTurn 必然为空，直接用 activeMembersConfigs，无需冗余过滤
    if (speakers.length === 0 && activeMembersConfigs.length > 0) {
        const relevantMembers = activeMembersConfigs.filter(m => {
            const tagsString = groupConfig.memberTags ? groupConfig.memberTags[m.id] : '';
            if (!tagsString) return false;
            const tags = tagsString.split(/,|，/).map(t => t.trim().toLowerCase()).filter(t => t);
            return tags.some(tag => contextText.includes(tag));
        });

        let fallbackSpeaker;
        if (relevantMembers.length > 0) {
            fallbackSpeaker = relevantMembers[Math.floor(Math.random() * relevantMembers.length)];
            console.log(`[NatureRandom] Fallback speaker (relevant): ${fallbackSpeaker.name}.`);
        } else {
            // 修复3：spokenThisTurn 为空，直接随机选取，无需额外过滤
            fallbackSpeaker = activeMembersConfigs[Math.floor(Math.random() * activeMembersConfigs.length)];
            console.log(`[NatureRandom] Fallback speaker (random): ${fallbackSpeaker.name}.`);
        }
        if (fallbackSpeaker) {
            speakers.push(fallbackSpeaker);
        }
    }

    // 排序优化：Tag 在用户最新发言中命中的角色排在最前
    speakers.sort((a, b) => {
        const getRelevance = (memberConfig) => {
            const tagsString = groupConfig.memberTags ? groupConfig.memberTags[memberConfig.id] : '';
            if (!tagsString) return 0;
            const tags = tagsString.split(/,|，/).map(t => t.trim().toLowerCase()).filter(t => t);
            if (tags.some(tag => userMessageText.includes(tag))) return 2;
            if (tags.some(tag => contextText.includes(tag))) return 1;
            return 0;
        };
        return getRelevance(b) - getRelevance(a);
    });

    console.log(`[NatureRandom] Mode: ${tagMatchMode}. Speakers: ${speakers.map(s => s.name).join(', ')}`);
    return speakers;
}


/**
 * 保存 AgentGroup 的头像
 * @param {string} groupId
 * @param {object} avatarData - { name: 'avatar.png', type: 'image/png', buffer: ArrayBuffer }
 * @returns {Promise<object>}
 */
async function saveAgentGroupAvatar(groupId, avatarData) {
    if (!mainAppPaths.AGENT_GROUPS_DIR) {
        return { success: false, error: 'GroupChat module paths not initialized.' };
    }
    try {
        if (!avatarData || !avatarData.name || !avatarData.buffer) {
            return { success: false, error: '无效的头像数据。' };
        }
        const groupDir = path.join(mainAppPaths.AGENT_GROUPS_DIR, groupId);
        await fs.ensureDir(groupDir);

        const allowedExtensions = ['.png', '.jpg', '.jpeg', '.gif', '.webp'];
        let newExt = path.extname(avatarData.name).toLowerCase();
        if (!allowedExtensions.includes(newExt)) {
            if (avatarData.type === 'image/png') newExt = '.png';
            else if (avatarData.type === 'image/jpeg') newExt = '.jpg';
            else if (avatarData.type === 'image/gif') newExt = '.gif';
            else if (avatarData.type === 'image/webp') newExt = '.webp';
            else newExt = '.png'; 
        }
        
        for (const ext of allowedExtensions) {
            const oldAvatarPath = path.join(groupDir, `avatar${ext}`);
            if (await fs.pathExists(oldAvatarPath)) {
                await fs.remove(oldAvatarPath);
            }
        }
        
        const newAvatarFileName = `avatar${newExt}`;
        const newAvatarPath = path.join(groupDir, newAvatarFileName);
        const nodeBuffer = Buffer.from(avatarData.buffer);
        await fs.writeFile(newAvatarPath, nodeBuffer);

        const configPath = path.join(groupDir, 'config.json');
        let config = {};
        if (await fs.pathExists(configPath)) {
            config = await fs.readJson(configPath);
        }
        config.avatar = newAvatarFileName; // 保存文件名
        // avatarCalculatedColor 通常由前端计算后通过 saveAgentGroupConfig 保存，这里不直接修改
        await fs.writeJson(configPath, config, { spaces: 2 });

        console.log(`[GroupChat] AgentGroup ${groupId} 头像已保存: ${newAvatarPath}`);
        return { success: true, avatarFileName: newAvatarFileName, avatarUrl: `file://${newAvatarPath}?t=${Date.now()}` };
    } catch (error) {
        console.error(`[GroupChat] 保存 AgentGroup ${groupId} 头像失败:`, error);
        return { success: false, error: error.message };
    }
}

// --- Group Topic Management (与Agent topics类似) ---
async function getGroupTopics(groupId, searchTerm = '') {
    const groupConfig = await getAgentGroupConfig(groupId);
    if (!groupConfig) {
        return { error: `Group ${groupId} not found.` };
    }

    let topics = groupConfig.topics && Array.isArray(groupConfig.topics)
                 ? groupConfig.topics
                 : [];

    if (topics.length === 0 && !searchTerm) { // Only add default if no search term and no topics
        const defaultTopic = { id: `group_topic_${Date.now()}`, name: "主要群聊", createdAt: Date.now() };
        topics.push(defaultTopic);
        // Optionally save this default topic back to config if it was truly missing
        const updatedConfig = { ...groupConfig, topics: topics };
        await saveAgentGroupConfig(groupId, updatedConfig);
    }

    if (searchTerm) {
        topics = topics.filter(topic =>
            topic.name.toLowerCase().includes(searchTerm.toLowerCase())
        );
    }
    
    return topics;
}

async function createNewTopicForGroup(groupId, topicName) {
    let groupConfig = await getAgentGroupConfig(groupId);
    if (!groupConfig) return { success: false, error: `Group ${groupId} not found.` };

    if (!Array.isArray(groupConfig.topics)) groupConfig.topics = [];
    
    const newTopicId = `group_topic_${Date.now()}`;
    const newTopic = { id: newTopicId, name: topicName || `新话题 ${groupConfig.topics.length + 1}`, createdAt: Date.now() };
    groupConfig.topics.unshift(newTopic);

    // 直接传递需要更新的部分给 saveAgentGroupConfig
    const result = await saveAgentGroupConfig(groupId, { topics: groupConfig.topics });
    if (!result.success) return { success: false, error: result.error };
 
    const topicHistoryDir = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', newTopicId);
    await fs.ensureDir(topicHistoryDir);
    await fs.writeJson(path.join(topicHistoryDir, 'history.json'), [], { spaces: 2 });

    return { success: true, topicId: newTopicId, topicName: newTopic.name, topics: result.agentGroup.topics };
}

async function deleteGroupTopic(groupId, topicIdToDelete) {
    let groupConfig = await getAgentGroupConfig(groupId);
    if (!groupConfig || !Array.isArray(groupConfig.topics)) {
        return { success: false, error: `Group ${groupId} or its topics not found.` };
    }

    const initialTopicCount = groupConfig.topics.length;
    groupConfig.topics = groupConfig.topics.filter(topic => topic.id !== topicIdToDelete);

    if (groupConfig.topics.length === initialTopicCount && initialTopicCount > 0) { // 确保真的有话题被删，且不是因为原本就空
        return { success: false, error: `Topic ID ${topicIdToDelete} not found in group ${groupId}.` };
    }

    if (groupConfig.topics.length === 0) { 
        const defaultTopic = { id: `group_topic_${Date.now()}`, name: "主要群聊", createdAt: Date.now() };
        groupConfig.topics.push(defaultTopic);
        const defaultTopicHistoryDir = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', defaultTopic.id);
        await fs.ensureDir(defaultTopicHistoryDir);
        await fs.writeJson(path.join(defaultTopicHistoryDir, 'history.json'), [], { spaces: 2 });
    }
    
    const result = await saveAgentGroupConfig(groupId, { topics: groupConfig.topics });
    if (!result.success) return { success: false, error: result.error };
 
    const topicDataDir = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', topicIdToDelete);
    if (await fs.pathExists(topicDataDir)) {
        await fs.remove(topicDataDir);
    }

    return { success: true, remainingTopics: result.agentGroup.topics };
}

async function saveGroupTopicTitle(groupId, topicId, newTitle) {
    let groupConfig = await getAgentGroupConfig(groupId);
    if (!groupConfig || !Array.isArray(groupConfig.topics)) {
        return { success: false, error: `Group ${groupId} or its topics not found.` };
    }
    const topicIndex = groupConfig.topics.findIndex(t => t.id === topicId);
    if (topicIndex === -1) {
        return { success: false, error: `Topic ID ${topicId} not found in group ${groupId}.` };
    }
    groupConfig.topics[topicIndex].name = newTitle;
    const result = await saveAgentGroupConfig(groupId, { topics: groupConfig.topics });
    return result.success ? { success: true, topics: result.agentGroup.topics } : { success: false, error: result.error };
}

async function getGroupChatHistory(groupId, topicId) {
    if (!mainAppPaths.USER_DATA_DIR) return { error: "Paths not initialized" };
    const historyFile = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', topicId, 'history.json');
    await fs.ensureDir(path.dirname(historyFile));
    if (await fs.pathExists(historyFile)) {
        try {
            return await fs.readJson(historyFile);
        } catch (e) {
            // console.error(`[GroupChat] Error reading or parsing history for ${groupId}/${topicId}:`, e); // 根据用户要求移除此报错
            // If reading or parsing fails, treat it as an empty history to avoid blocking the chat.
            return [];
        }
    }
    return []; // Return empty array if no history
}


/**
 * 新增：处理“重新回复”群聊消息的逻辑
 * @param {string} groupId - 群组ID
 * @param {string} topicId - 话题ID
 * @param {string} messageIdToDelete - 要删除并重新生成的消息ID
 * @param {string} agentIdToReInvite - 要重新邀请发言的Agent ID
 * @param {function} sendStreamChunkToRenderer - 用于发送流式数据的回调函数
 * @param {function} getAgentConfigById - 用于根据Agent ID获取其完整配置的函数
 * @returns {Promise<void>}
 */
async function redoGroupChatMessage(groupId, topicId, messageIdToDelete, agentIdToReInvite, sendStreamChunkToRenderer, getAgentConfigById) {
    console.log(`[GroupChat] redoGroupChatMessage invoked for message ${messageIdToDelete} by agent ${agentIdToReInvite}`);

    const groupHistoryPath = path.join(mainAppPaths.USER_DATA_DIR, groupId, 'topics', topicId, 'history.json');
    
    if (!await fs.pathExists(groupHistoryPath)) {
        console.error(`[GroupChat Redo] History file not found at ${groupHistoryPath}`);
        // Optionally send an error to renderer
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: '无法重新回复：找不到历史记录文件。', context: { groupId, topicId, agentId: agentIdToReInvite, isGroupMessage: true } });
        }
        return;
    }

    try {
        // 1. 读取、过滤并保存历史记录
        let groupHistory = await fs.readJson(groupHistoryPath);
        const initialLength = groupHistory.length;
        const updatedHistory = groupHistory.filter(msg => msg.id !== messageIdToDelete);

        if (updatedHistory.length === initialLength) {
            console.warn(`[GroupChat Redo] Message with ID ${messageIdToDelete} not found in history. Cannot redo.`);
            // No need to proceed if the message wasn't found
            return;
        }

        await fs.writeJson(groupHistoryPath, updatedHistory, { spaces: 2 });
        console.log(`[GroupChat Redo] Message ${messageIdToDelete} removed from history.`);

        // 2. 通知渲染器删除该消息
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({
                type: 'remove_message',
                messageId: messageIdToDelete,
                context: {
                    groupId,
                    topicId,
                    isGroupMessage: true
                }
            });
        }

        // 3. 调用现有的邀请函数来重新生成回复
        // handleInviteAgentToSpeak 将处理后续的所有逻辑，包括发送 'agent_thinking' 等事件
        await handleInviteAgentToSpeak(groupId, topicId, agentIdToReInvite, sendStreamChunkToRenderer, getAgentConfigById);

    } catch (error) {
        console.error(`[GroupChat Redo] Error during redo process for message ${messageIdToDelete}:`, error);
        if (typeof sendStreamChunkToRenderer === 'function') {
            sendStreamChunkToRenderer({ type: 'error', error: `重新回复时发生错误: ${error.message}`, context: { groupId, topicId, agentId: agentIdToReInvite, isGroupMessage: true } });
        }
    }
}


/**
 * 中断一个正在进行的群聊 VCP 请求
 * @param {string} messageId - 要中断的消息的 ID
 * @returns {{success: boolean, error?: string}}
 */
async function interruptGroupRequest(messageId) {
    const controller = activeRequestControllers.get(messageId);
    if (controller) {
        console.log(`[GroupChat] Interrupting local request for messageId: ${messageId}`);
        controller.abort();

        // 发送远程中断协议
        try {
            const globalSettings = await getVcpGlobalSettings();
            if (globalSettings.vcpUrl && globalSettings.vcpApiKey) {
                // 计算中断接口 URL (假设从 /v1/chat/completions 转换为 /v1/interrupt)
                const urlObj = new URL(globalSettings.vcpUrl);
                urlObj.pathname = '/v1/interrupt';
                const interruptUrl = urlObj.toString();

                console.log(`[GroupChat] Sending remote interrupt request to: ${interruptUrl}`);
                const response = await fetch(interruptUrl, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${globalSettings.vcpApiKey}`
                    },
                    body: JSON.stringify({
                        messageId: messageId
                    })
                });

                if (response.ok) {
                    const result = await response.json();
                    console.log('[GroupChat] Remote interrupt success:', result.message);
                } else {
                    const errorText = await response.text();
                    console.error('[GroupChat] Remote interrupt failed:', response.status, errorText);
                }
            }
        } catch (remoteError) {
            console.error('[GroupChat] Error sending remote interrupt:', remoteError);
        }

        return { success: true, message: 'Interrupt signal sent locally and remote request attempted.' };
    } else {
        console.warn(`[GroupChat] Could not find active request controller for messageId to interrupt: ${messageId}`);
        return { success: false, error: 'Request not found or already completed.' };
    }
}


module.exports = {
    initializePaths,
    createAgentGroup,
    getAgentGroups,
    getAgentGroupConfig,
    saveAgentGroupConfig,
    deleteAgentGroup,
    handleGroupChatMessage,
    handleInviteAgentToSpeak, // 新增导出
    redoGroupChatMessage, // 新增导出
    interruptGroupRequest,
    saveAgentGroupAvatar,
    getGroupTopics,
    createNewTopicForGroup,
    deleteGroupTopic,
    saveGroupTopicTitle,
    getGroupChatHistory,
};
