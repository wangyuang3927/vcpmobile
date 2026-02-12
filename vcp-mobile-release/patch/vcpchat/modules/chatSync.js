// modules/chatSync.js
// VCPChat 桌面端聊天记录同步模块
// 与 VCPToolBox 的 ChatSync 插件通信，实现跨设备消息级增量同步

const fs = require('fs-extra');
const path = require('path');

const LOG_PREFIX = '[ChatSync]';

class ChatSyncManager {
    constructor(config = {}) {
        this.userDataDir = config.userDataDir || '';
        this.baseUrl = config.baseUrl || '';
        this.adminUsername = config.adminUsername || '';
        this.adminPassword = config.adminPassword || '';
        this.enabled = false;
        this.syncTimestamps = new Map(); // topicKey -> lastSyncTimestamp
        this.debugMode = config.debugMode || false;
    }

    log(...args) {
        console.log(LOG_PREFIX, ...args);
    }

    debug(...args) {
        if (this.debugMode) console.log(LOG_PREFIX, '[DEBUG]', ...args);
    }

    /**
     * 更新配置
     */
    configure(config) {
        if (config.baseUrl !== undefined) this.baseUrl = config.baseUrl;
        if (config.adminUsername !== undefined) this.adminUsername = config.adminUsername;
        if (config.adminPassword !== undefined) this.adminPassword = config.adminPassword;
        if (config.enabled !== undefined) this.enabled = config.enabled;
        if (config.userDataDir !== undefined) this.userDataDir = config.userDataDir;
        if (config.debugMode !== undefined) this.debugMode = config.debugMode;
    }

    /**
     * 构建请求头
     */
    _buildHeaders() {
        const headers = { 'Content-Type': 'application/json' };
        if (this.adminUsername && this.adminPassword) {
            const credentials = Buffer.from(`${this.adminUsername}:${this.adminPassword}`).toString('base64');
            headers['Authorization'] = `Basic ${credentials}`;
        }
        return headers;
    }

    /**
     * 构建同步 API URL
     */
    _buildSyncUrl(endpoint) {
        let base = (this.baseUrl || '').trim();
        if (!base) return '';
        if (base.endsWith('/')) base = base.slice(0, -1);
        return `${base}/admin_api/chat-sync${endpoint}`;
    }

    /**
     * 发起 HTTP 请求
     */
    async _fetch(url, options = {}) {
        const { default: fetch } = await import('node-fetch');
        return fetch(url, {
            ...options,
            headers: { ...this._buildHeaders(), ...(options.headers || {}) },
        });
    }

    /**
     * 检查同步服务是否可用
     */
    async checkStatus() {
        const url = this._buildSyncUrl('/status');
        if (!url) return { available: false, error: '未配置同步地址' };

        try {
            const response = await this._fetch(url);
            if (!response.ok) return { available: false, error: `HTTP ${response.status}` };
            const data = await response.json();
            return { available: data.success, data };
        } catch (error) {
            return { available: false, error: error.message };
        }
    }

    /**
     * 合并消息（基于 ID 去重，按时间戳排序）
     */
    _mergeMessages(existing, incoming) {
        const messageMap = new Map();
        for (const msg of existing) {
            if (msg.id) messageMap.set(msg.id, msg);
        }
        let newCount = 0;
        for (const msg of incoming) {
            if (!msg.id) continue;
            if (!messageMap.has(msg.id)) {
                messageMap.set(msg.id, msg);
                newCount++;
            }
        }
        const merged = Array.from(messageMap.values());
        merged.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
        return { merged, newCount };
    }

    /**
     * 同步单个话题
     * @param {string} agentId - Agent ID
     * @param {string} topicId - Topic ID
     * @returns {Object} { success, newFromServer, newFromClient }
     */
    async syncTopic(agentId, topicId) {
        if (!this.enabled || !this.baseUrl || !this.adminUsername) {
            return { success: false, error: '同步未启用或未配置' };
        }

        const url = this._buildSyncUrl('/sync');
        if (!url) return { success: false, error: '无效的同步地址' };

        // 读取本地历史
        const historyPath = path.join(this.userDataDir, agentId, 'topics', topicId, 'history.json');
        let localMessages = [];
        try {
            if (await fs.pathExists(historyPath)) {
                localMessages = await fs.readJson(historyPath);
            }
        } catch (e) {
            this.debug(`读取本地历史失败: ${e.message}`);
        }

        // 获取上次同步时间戳
        const topicKey = `${agentId}/${topicId}`;
        const lastSyncTimestamp = this.syncTimestamps.get(topicKey) || 0;

        // 筛选本地新消息
        const clientNewMessages = localMessages.filter(
            (msg) => (msg.timestamp || 0) > lastSyncTimestamp
        );

        try {
            const response = await this._fetch(url, {
                method: 'POST',
                body: JSON.stringify({
                    agentId,
                    topicId,
                    clientMessages: clientNewMessages,
                    lastSyncTimestamp,
                }),
            });

            if (!response.ok) {
                const text = await response.text();
                return { success: false, error: `HTTP ${response.status}: ${text}` };
            }

            const data = await response.json();
            if (!data.success) return { success: false, error: data.error };

            // 更新同步时间戳
            this.syncTimestamps.set(topicKey, data.lastSyncTimestamp);

            // 合并服务端新消息到本地
            const serverNew = data.serverNewMessages || [];
            let newFromServer = 0;

            if (serverNew.length > 0) {
                const { merged, newCount } = this._mergeMessages(localMessages, serverNew);
                newFromServer = newCount;

                if (newCount > 0) {
                    await fs.ensureDir(path.dirname(historyPath));
                    await fs.writeJson(historyPath, merged, { spaces: 2 });
                    this.debug(`${topicKey}: 合并了 ${newCount} 条来自服务端的新消息`);
                }
            }

            return {
                success: true,
                newFromServer,
                newFromClient: data.newFromClient || 0,
                mergedCount: data.mergedCount,
            };
        } catch (error) {
            this.debug(`同步 ${topicKey} 失败: ${error.message}`);
            return { success: false, error: error.message };
        }
    }

    /**
     * 同步某个 agent 的所有话题
     * @param {string} agentId - Agent ID
     * @param {Array} topics - 话题列表 [{ id, title, ... }]
     * @param {Function} onProgress - 进度回调 (current, total, topicTitle)
     */
    async syncAgent(agentId, topics, onProgress) {
        if (!this.enabled) return { success: false, error: '同步未启用' };

        this.log(`开始同步 Agent ${agentId}，共 ${topics.length} 个话题`);

        // 先上传话题元数据
        try {
            const topicsUrl = this._buildSyncUrl(`/agents/${agentId}/topics`);
            await this._fetch(topicsUrl, {
                method: 'PUT',
                body: JSON.stringify(topics),
            });
        } catch (e) {
            this.debug(`上传话题元数据失败: ${e.message}`);
        }

        let syncedCount = 0;
        let errorCount = 0;

        for (let i = 0; i < topics.length; i++) {
            const topic = topics[i];
            const topicId = topic.id || topic.topicId;

            if (onProgress) onProgress(i + 1, topics.length, topic.title || topic.name);

            const result = await this.syncTopic(agentId, topicId);
            if (result.success) {
                syncedCount++;
            } else {
                errorCount++;
            }
        }

        this.log(`Agent ${agentId} 同步完成: ${syncedCount} 成功, ${errorCount} 失败`);
        return { success: true, syncedCount, errorCount, total: topics.length };
    }

    /**
     * 后台静默同步当前话题（在保存聊天记录后调用）
     */
    async backgroundSync(agentId, topicId) {
        if (!this.enabled || !this.baseUrl || !this.adminUsername) return;

        try {
            const result = await this.syncTopic(agentId, topicId);
            if (result.success && result.newFromServer > 0) {
                this.log(`后台同步: ${agentId}/${topicId} 收到 ${result.newFromServer} 条新消息`);
            }
        } catch (e) {
            // 静默失败，不影响用户体验
            this.debug(`后台同步失败: ${e.message}`);
        }
    }
}

module.exports = ChatSyncManager;
