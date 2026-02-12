// Plugin/ChatSync/ChatSync.js
// 聊天记录跨设备同步服务
// 提供 REST API 实现 VCPChat（桌面端）和 vcp-mobile（移动端）之间的消息级增量同步

const fs = require('fs').promises;
const fsSync = require('fs');
const path = require('path');

const LOG_PREFIX = '[ChatSync]';
let debugMode = false;

// 数据存储根目录（在 VCPToolBox 项目目录下）
let SYNC_DATA_DIR = '';

// ========== 工具函数 ==========

function log(...args) {
    console.log(LOG_PREFIX, ...args);
}

function debug(...args) {
    if (debugMode) console.log(LOG_PREFIX, '[DEBUG]', ...args);
}

/**
 * 确保目录存在
 */
async function ensureDir(dirPath) {
    await fs.mkdir(dirPath, { recursive: true });
}

/**
 * 安全读取 JSON 文件，不存在则返回默认值
 */
async function readJsonSafe(filePath, defaultValue = null) {
    try {
        const content = await fs.readFile(filePath, 'utf-8');
        return JSON.parse(content);
    } catch (e) {
        if (e.code === 'ENOENT') return defaultValue;
        throw e;
    }
}

/**
 * 安全写入 JSON 文件（原子写入：先写临时文件再重命名）
 */
async function writeJsonSafe(filePath, data) {
    const tmpPath = filePath + '.tmp';
    await fs.writeFile(tmpPath, JSON.stringify(data, null, 2), 'utf-8');
    await fs.rename(tmpPath, filePath);
}

/**
 * 获取 agent 的话题目录路径
 */
function getTopicDir(agentId, topicId) {
    return path.join(SYNC_DATA_DIR, agentId, 'topics', topicId);
}

/**
 * 获取 agent 的话题历史文件路径
 */
function getHistoryPath(agentId, topicId) {
    return path.join(getTopicDir(agentId, topicId), 'history.json');
}

/**
 * 获取 agent 的话题配置文件路径
 */
function getTopicsConfigPath(agentId) {
    return path.join(SYNC_DATA_DIR, agentId, 'topics.json');
}

// ========== 核心同步逻辑 ==========

/**
 * 合并消息列表（基于消息 ID 去重，按时间戳排序）
 * @param {Array} existing - 服务端已有消息
 * @param {Array} incoming - 客户端上传的消息
 * @returns {Array} 合并后的消息列表
 */
function mergeMessages(existing, incoming) {
    const messageMap = new Map();

    // 先放入已有消息
    for (const msg of existing) {
        if (msg.id) {
            messageMap.set(msg.id, msg);
        }
    }

    // 合并新消息（同 ID 则用时间戳更新的覆盖）
    let newCount = 0;
    for (const msg of incoming) {
        if (!msg.id) continue;
        const existingMsg = messageMap.get(msg.id);
        if (!existingMsg) {
            messageMap.set(msg.id, msg);
            newCount++;
        } else if (msg.timestamp && existingMsg.timestamp && msg.timestamp > existingMsg.timestamp) {
            messageMap.set(msg.id, msg);
        }
    }

    // 按时间戳排序
    const merged = Array.from(messageMap.values());
    merged.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));

    return { merged, newCount };
}

// ========== 路由注册 ==========

/**
 * 注册 API 路由到 Express app
 * 路由挂载在 adminApiRouter 下，受 Admin Auth 保护
 */
function registerRoutes(app, adminApiRouter, pluginConfig, projectBasePath) {
    debugMode = pluginConfig && pluginConfig.DebugMode === true;
    SYNC_DATA_DIR = path.join(projectBasePath, 'ChatSyncData');

    log(`初始化中... 数据目录: ${SYNC_DATA_DIR}`);
    log(`调试模式: ${debugMode ? 'ON' : 'OFF'}`);

    // 确保数据目录存在
    fsSync.mkdirSync(SYNC_DATA_DIR, { recursive: true });

    // ---- GET /chat-sync/status ----
    // 获取同步服务状态
    adminApiRouter.get('/chat-sync/status', (req, res) => {
        res.json({
            success: true,
            service: 'ChatSync',
            version: '1.0.0',
            timestamp: Date.now()
        });
    });

    // ---- GET /chat-sync/agents ----
    // 获取所有已同步的 agent 列表
    adminApiRouter.get('/chat-sync/agents', async (req, res) => {
        try {
            await ensureDir(SYNC_DATA_DIR);
            const entries = await fs.readdir(SYNC_DATA_DIR, { withFileTypes: true });
            const agents = entries
                .filter(e => e.isDirectory())
                .map(e => e.name);
            res.json({ success: true, agents });
        } catch (error) {
            log('获取 agent 列表失败:', error.message);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // ---- GET /chat-sync/agents/:agentId/topics ----
    // 获取某 agent 的所有话题元数据
    adminApiRouter.get('/chat-sync/agents/:agentId/topics', async (req, res) => {
        try {
            const { agentId } = req.params;
            const topicsConfig = await readJsonSafe(getTopicsConfigPath(agentId), []);
            res.json({ success: true, topics: topicsConfig });
        } catch (error) {
            log(`获取 agent ${req.params.agentId} 话题列表失败:`, error.message);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // ---- PUT /chat-sync/agents/:agentId/topics ----
    // 上传/更新某 agent 的话题元数据列表
    adminApiRouter.put('/chat-sync/agents/:agentId/topics', async (req, res) => {
        try {
            const { agentId } = req.params;
            const topics = req.body;

            if (!Array.isArray(topics)) {
                return res.status(400).json({ success: false, error: 'Request body must be an array of topics.' });
            }

            const configPath = getTopicsConfigPath(agentId);
            await ensureDir(path.dirname(configPath));

            // 合并话题列表（基于 topicId 去重）
            const existing = await readJsonSafe(configPath, []);
            const topicMap = new Map();
            for (const t of existing) {
                if (t.id || t.topicId) topicMap.set(t.id || t.topicId, t);
            }
            for (const t of topics) {
                const key = t.id || t.topicId;
                if (key) topicMap.set(key, t);
            }
            const merged = Array.from(topicMap.values());

            await writeJsonSafe(configPath, merged);
            debug(`Agent ${agentId} 话题元数据已更新，共 ${merged.length} 个话题`);
            res.json({ success: true, count: merged.length });
        } catch (error) {
            log(`更新 agent ${req.params.agentId} 话题元数据失败:`, error.message);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // ---- GET /chat-sync/history/:agentId/:topicId ----
    // 拉取某话题的完整聊天记录
    adminApiRouter.get('/chat-sync/history/:agentId/:topicId', async (req, res) => {
        try {
            const { agentId, topicId } = req.params;
            const since = parseInt(req.query.since) || 0; // 可选：只返回此时间戳之后的消息

            const historyPath = getHistoryPath(agentId, topicId);
            const history = await readJsonSafe(historyPath, []);

            if (since > 0) {
                const filtered = history.filter(msg => (msg.timestamp || 0) > since);
                debug(`Agent ${agentId} Topic ${topicId}: 返回 ${filtered.length}/${history.length} 条消息 (since=${since})`);
                res.json({ success: true, messages: filtered, total: history.length });
            } else {
                debug(`Agent ${agentId} Topic ${topicId}: 返回全部 ${history.length} 条消息`);
                res.json({ success: true, messages: history, total: history.length });
            }
        } catch (error) {
            log(`拉取 ${req.params.agentId}/${req.params.topicId} 历史失败:`, error.message);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // ---- PUT /chat-sync/history/:agentId/:topicId ----
    // 上传/覆盖某话题的完整聊天记录
    adminApiRouter.put('/chat-sync/history/:agentId/:topicId', async (req, res) => {
        try {
            const { agentId, topicId } = req.params;
            const messages = req.body;

            if (!Array.isArray(messages)) {
                return res.status(400).json({ success: false, error: 'Request body must be an array of messages.' });
            }

            const topicDir = getTopicDir(agentId, topicId);
            await ensureDir(topicDir);
            const historyPath = getHistoryPath(agentId, topicId);

            await writeJsonSafe(historyPath, messages);
            debug(`Agent ${agentId} Topic ${topicId}: 覆盖写入 ${messages.length} 条消息`);
            res.json({ success: true, count: messages.length });
        } catch (error) {
            log(`覆盖 ${req.params.agentId}/${req.params.topicId} 历史失败:`, error.message);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // ---- POST /chat-sync/sync ----
    // 增量同步：客户端上传新消息 + 拉取服务端新消息
    // 请求体: { agentId, topicId, clientMessages: [...], lastSyncTimestamp: number }
    // 响应: { success, serverNewMessages: [...], mergedCount, lastSyncTimestamp }
    adminApiRouter.post('/chat-sync/sync', async (req, res) => {
        try {
            const { agentId, topicId, clientMessages, lastSyncTimestamp } = req.body;

            if (!agentId || !topicId) {
                return res.status(400).json({ success: false, error: 'agentId and topicId are required.' });
            }

            const topicDir = getTopicDir(agentId, topicId);
            await ensureDir(topicDir);
            const historyPath = getHistoryPath(agentId, topicId);

            // 1. 读取服务端已有消息
            const serverMessages = await readJsonSafe(historyPath, []);

            // 2. 合并客户端上传的消息
            const incomingMessages = Array.isArray(clientMessages) ? clientMessages : [];
            const { merged, newCount } = mergeMessages(serverMessages, incomingMessages);

            // 3. 保存合并后的消息
            if (newCount > 0) {
                await writeJsonSafe(historyPath, merged);
                debug(`Sync ${agentId}/${topicId}: 合并了 ${newCount} 条新消息，总计 ${merged.length} 条`);
            }

            // 4. 返回服务端在 lastSyncTimestamp 之后的消息（供客户端拉取）
            const syncTs = lastSyncTimestamp || 0;
            const serverNewMessages = merged.filter(msg => (msg.timestamp || 0) > syncTs);

            // 5. 计算新的同步时间戳
            const newSyncTimestamp = merged.length > 0
                ? Math.max(...merged.map(m => m.timestamp || 0))
                : Date.now();

            res.json({
                success: true,
                serverNewMessages,
                mergedCount: merged.length,
                newFromClient: newCount,
                lastSyncTimestamp: newSyncTimestamp
            });
        } catch (error) {
            log(`同步失败:`, error.message);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // ---- POST /chat-sync/batch-sync ----
    // 批量同步多个话题（减少网络请求次数）
    // 请求体: { syncs: [{ agentId, topicId, clientMessages, lastSyncTimestamp }, ...] }
    adminApiRouter.post('/chat-sync/batch-sync', async (req, res) => {
        try {
            const { syncs } = req.body;

            if (!Array.isArray(syncs)) {
                return res.status(400).json({ success: false, error: 'syncs must be an array.' });
            }

            const results = [];

            for (const syncReq of syncs) {
                const { agentId, topicId, clientMessages, lastSyncTimestamp } = syncReq;

                if (!agentId || !topicId) {
                    results.push({ agentId, topicId, success: false, error: 'Missing agentId or topicId' });
                    continue;
                }

                try {
                    const topicDir = getTopicDir(agentId, topicId);
                    await ensureDir(topicDir);
                    const historyPath = getHistoryPath(agentId, topicId);

                    const serverMessages = await readJsonSafe(historyPath, []);
                    const incomingMessages = Array.isArray(clientMessages) ? clientMessages : [];
                    const { merged, newCount } = mergeMessages(serverMessages, incomingMessages);

                    if (newCount > 0) {
                        await writeJsonSafe(historyPath, merged);
                    }

                    const syncTs = lastSyncTimestamp || 0;
                    const serverNewMessages = merged.filter(msg => (msg.timestamp || 0) > syncTs);
                    const newSyncTimestamp = merged.length > 0
                        ? Math.max(...merged.map(m => m.timestamp || 0))
                        : Date.now();

                    results.push({
                        agentId,
                        topicId,
                        success: true,
                        serverNewMessages,
                        mergedCount: merged.length,
                        newFromClient: newCount,
                        lastSyncTimestamp: newSyncTimestamp
                    });
                } catch (innerError) {
                    results.push({ agentId, topicId, success: false, error: innerError.message });
                }
            }

            res.json({ success: true, results });
        } catch (error) {
            log(`批量同步失败:`, error.message);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // ---- DELETE /chat-sync/history/:agentId/:topicId ----
    // 删除某话题的同步数据
    adminApiRouter.delete('/chat-sync/history/:agentId/:topicId', async (req, res) => {
        try {
            const { agentId, topicId } = req.params;
            const topicDir = getTopicDir(agentId, topicId);

            await fs.rm(topicDir, { recursive: true, force: true });
            debug(`已删除 ${agentId}/${topicId} 的同步数据`);
            res.json({ success: true });
        } catch (error) {
            log(`删除 ${req.params.agentId}/${req.params.topicId} 失败:`, error.message);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    log('✅ 聊天记录同步服务已注册，API 路径: /admin_api/chat-sync/*');
}

module.exports = { registerRoutes };
