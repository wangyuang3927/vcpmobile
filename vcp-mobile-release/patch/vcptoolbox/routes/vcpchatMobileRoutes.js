/**
 * VCPChat Mobile 同步路由
 * 提供手机端读取 VCPChat 桌面端 Agent 列表和聊天记录的 API
 */
const path = require('path');
const fs = require('fs').promises;

module.exports = function (adminApiRouter, AGENT_MAP_FILE, parseAgentAssistantConfig) {

    // 将 HTML 内容转为纯文本，避免图片请求和渲染卡顿
    function simplifyContent(content, role) {
        if (!content || role !== 'assistant') return content;
        if (!/<[a-z][\s\S]*>/i.test(content)) return content;
        let s = content;
        s = s.replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '');
        s = s.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
        s = s.replace(/<br\s*\/?>/gi, '\n');
        s = s.replace(/<\/(p|div|li|h[1-6]|tr)>/gi, '\n');
        s = s.replace(/<li[^>]*>/gi, '• ');
        s = s.replace(/<[^>]+>/g, '');
        s = s.replace(/&nbsp;/gi, ' ');
        s = s.replace(/&amp;/gi, '&');
        s = s.replace(/&lt;/gi, '<');
        s = s.replace(/&gt;/gi, '>');
        s = s.replace(/&quot;/gi, '"');
        s = s.replace(/&#039;/gi, "'");
        s = s.replace(/\n{3,}/g, '\n\n');
        return s.trim();
    }

    // 获取 VCPChat 路径
    function getVCPChatPath() {
        let vcpchatPath = process.env.VarVchatPath || '';
        if (!vcpchatPath || vcpchatPath.includes('YOUR_VCHAT_PATH')) {
            vcpchatPath = path.join(__dirname, '..', '..', 'VCPChat');
        }
        return vcpchatPath;
    }

    // 从 VCPChat AppData/Agents 目录读取 Agent 列表
    async function readVCPChatAgents(agentManager) {
        const agentsDir = path.join(getVCPChatPath(), 'AppData', 'Agents');

        try {
            await fs.access(agentsDir);
        } catch {
            return [];
        }

        const entries = await fs.readdir(agentsDir, { withFileTypes: true });
        const agents = [];

        for (const entry of entries) {
            if (!entry.isDirectory()) continue;
            const configPath = path.join(agentsDir, entry.name, 'config.json');
            try {
                const content = await fs.readFile(configPath, 'utf-8');
                const config = JSON.parse(content);

                let systemPrompt = config.systemPrompt || '';
                const templateMatch = systemPrompt.match(/^\{\{(.+?)\}\}$/);
                if (templateMatch) {
                    try {
                        systemPrompt = await agentManager.getAgentPrompt(templateMatch[1]);
                    } catch (e) { /* 模板解析失败，保留原值 */ }
                }

                agents.push({
                    name: config.name || entry.name,
                    agentDirId: entry.name,
                    systemPrompt: systemPrompt,
                    modelId: config.model || '',
                    temperature: config.temperature ?? 0.7,
                    maxOutputTokens: config.maxOutputTokens ?? 60000,
                    contextTokenLimit: config.contextTokenLimit ?? 1000000,
                    description: '',
                    topics: (config.topics || []).map(t => ({
                        id: t.id,
                        name: t.name,
                        createdAt: t.createdAt || 0,
                    })),
                });
            } catch (e) {
                console.warn(`[VCPChatMobile] Failed to read agent ${entry.name}:`, e.message);
            }
        }

        if (agents.length > 0) {
            console.log(`[VCPChatMobile] Loaded ${agents.length} agents from VCPChat AppData`);
        }
        return agents;
    }

    // GET /agents/mobile-list
    adminApiRouter.get('/agents/mobile-list', async (req, res) => {
        try {
            const agentManager = require('../modules/agentManager');

            const vcpchatAgents = await readVCPChatAgents(agentManager);
            if (vcpchatAgents.length > 0) {
                return res.json({ success: true, source: 'vcpchat', agents: vcpchatAgents });
            }

            // Fallback: agent_map.json + AgentAssistant config
            let agentMap = {};
            try {
                const content = await fs.readFile(AGENT_MAP_FILE, 'utf-8');
                agentMap = JSON.parse(content);
            } catch (e) {
                if (e.code !== 'ENOENT') throw e;
            }

            let assistantConfig = { agents: [] };
            try {
                assistantConfig = await parseAgentAssistantConfig();
            } catch (e) { /* ignore */ }
            const assistantAgentMap = new Map();
            for (const a of assistantConfig.agents) {
                if (a.chineseName) assistantAgentMap.set(a.chineseName, a);
            }

            const agents = [];
            for (const [alias, file] of Object.entries(agentMap)) {
                const aa = assistantAgentMap.get(alias);
                let systemPrompt = '';
                try { systemPrompt = await agentManager.getAgentPrompt(alias); } catch (e) { /* ignore */ }
                agents.push({
                    name: alias, file, systemPrompt,
                    modelId: aa ? aa.modelId : '',
                    temperature: aa ? aa.temperature : 0.7,
                    maxOutputTokens: aa ? aa.maxOutputTokens : 40000,
                    description: aa ? aa.description : '',
                });
            }

            for (const a of assistantConfig.agents) {
                if (!agentMap[a.chineseName]) {
                    agents.push({
                        name: a.chineseName, file: '',
                        systemPrompt: a.systemPrompt || '',
                        modelId: a.modelId || '',
                        temperature: a.temperature || 0.7,
                        maxOutputTokens: a.maxOutputTokens || 40000,
                        description: a.description || '',
                    });
                }
            }

            res.json({ success: true, source: 'agent_map', agents });
        } catch (error) {
            console.error('[VCPChatMobile] Error getting mobile agent list:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // GET /agents/vcpchat-history
    // 支持 ifModifiedSince 参数：客户端传入上次缓存的 lastModified，未变化时返回 304 风格响应
    adminApiRouter.get('/agents/vcpchat-history', async (req, res) => {
        const { agentDirId, topicId, ifModifiedSince } = req.query;
        if (!agentDirId || !topicId) {
            return res.status(400).json({ success: false, error: '缺少 agentDirId 或 topicId 参数' });
        }

        try {
            const historyPath = path.join(getVCPChatPath(), 'AppData', 'UserData', agentDirId, 'topics', topicId, 'history.json');

            let stat;
            try {
                stat = await fs.stat(historyPath);
            } catch {
                return res.json({ success: true, messages: [], lastModified: 0 });
            }

            const lastModified = stat.mtimeMs;

            // 如果客户端传了 ifModifiedSince 且文件未变化，返回 notModified
            if (ifModifiedSince && Number(ifModifiedSince) >= lastModified) {
                return res.json({ success: true, notModified: true, lastModified });
            }

            const content = await fs.readFile(historyPath, 'utf-8');
            const messages = JSON.parse(content);

            const mobileMessages = messages.map(m => ({
                id: m.id || `msg_${m.timestamp || Date.now()}`,
                role: m.role,
                name: m.name || '',
                content: simplifyContent(m.content || '', m.role),
                timestamp: m.timestamp || 0,
                attachments: (m.attachments || []).map(a => ({
                    kind: a.kind || a.type || '',
                    name: a.name || '',
                })),
            }));

            res.json({ success: true, messages: mobileMessages, lastModified });
        } catch (error) {
            console.error('[VCPChatMobile] Error reading history:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // POST /agents/vcpchat-append-history
    // 手机端将新消息追加到 VCPChat 桌面端的 history.json（双向同步）
    // 同时更新 config.json 的 topics 列表（确保新话题出现在桌面端）
    adminApiRouter.post('/agents/vcpchat-append-history', async (req, res) => {
        const { agentDirId, topicId, topicName, messages: newMessages } = req.body;
        if (!agentDirId || !topicId || !Array.isArray(newMessages) || newMessages.length === 0) {
            return res.status(400).json({ success: false, error: '缺少 agentDirId、topicId 或 messages' });
        }

        try {
            const topicDir = path.join(getVCPChatPath(), 'AppData', 'UserData', agentDirId, 'topics', topicId);
            const historyPath = path.join(topicDir, 'history.json');

            // 确保目录存在（手机端新建的话题可能在桌面端不存在）
            await fs.mkdir(topicDir, { recursive: true });

            // 读取现有消息
            let existing = [];
            try {
                const content = await fs.readFile(historyPath, 'utf-8');
                existing = JSON.parse(content);
            } catch {
                // 文件不存在或解析失败，从空数组开始
            }

            // 去重：只追加 ID 不存在的消息
            const existingIds = new Set(existing.map(m => m.id));
            const toAppend = newMessages.filter(m => m.id && !existingIds.has(m.id));

            if (toAppend.length === 0) {
                return res.json({ success: true, appended: 0, total: existing.length });
            }

            // 格式化为 VCPChat 兼容格式
            const formatted = toAppend.map(m => ({
                id: m.id,
                role: m.role || 'user',
                name: m.name || '',
                content: m.content || '',
                timestamp: m.timestamp || Date.now(),
                attachments: m.attachments || [],
            }));

            const merged = [...existing, ...formatted];
            await fs.writeFile(historyPath, JSON.stringify(merged, null, 2), 'utf-8');

            // 同步话题到 config.json（确保新话题出现在 VCPChat 桌面端）
            const configPath = path.join(getVCPChatPath(), 'AppData', 'Agents', agentDirId, 'config.json');
            try {
                const configContent = await fs.readFile(configPath, 'utf-8');
                const agentConfig = JSON.parse(configContent);
                const topics = agentConfig.topics || [];
                const topicExists = topics.some(t => t.id === topicId);
                if (!topicExists) {
                    topics.unshift({
                        id: topicId,
                        name: topicName || `手机话题 ${new Date().toLocaleString('zh-CN')}`,
                        createdAt: Date.now(),
                    });
                    agentConfig.topics = topics;
                    await fs.writeFile(configPath, JSON.stringify(agentConfig, null, 2), 'utf-8');
                    console.log(`[VCPChatMobile] 新话题 ${topicId} 已添加到 ${agentDirId}/config.json`);
                }
            } catch (e) {
                console.warn(`[VCPChatMobile] 更新 config.json 失败:`, e.message);
            }

            console.log(`[VCPChatMobile] 追加 ${formatted.length} 条消息到 ${agentDirId}/${topicId}，总计 ${merged.length} 条`);
            res.json({ success: true, appended: formatted.length, total: merged.length });
        } catch (error) {
            console.error('[VCPChatMobile] Error appending history:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // POST /agents/vcpchat-delete-topic
    // 手机端删除话题，同步到 VCPChat 桌面端（从 config.json 移除 + 删除话题目录）
    adminApiRouter.post('/agents/vcpchat-delete-topic', async (req, res) => {
        const { agentDirId, topicId } = req.body;
        if (!agentDirId || !topicId) {
            return res.status(400).json({ success: false, error: '缺少 agentDirId 或 topicId' });
        }

        try {
            // 1. 从 config.json 的 topics 列表中移除
            const configPath = path.join(getVCPChatPath(), 'AppData', 'Agents', agentDirId, 'config.json');
            try {
                const configContent = await fs.readFile(configPath, 'utf-8');
                const agentConfig = JSON.parse(configContent);
                const before = (agentConfig.topics || []).length;
                agentConfig.topics = (agentConfig.topics || []).filter(t => t.id !== topicId);
                if (agentConfig.topics.length < before) {
                    await fs.writeFile(configPath, JSON.stringify(agentConfig, null, 2), 'utf-8');
                }
            } catch (e) {
                console.warn(`[VCPChatMobile] 更新 config.json 失败:`, e.message);
            }

            // 2. 删除话题目录（history.json 等）
            const topicDir = path.join(getVCPChatPath(), 'AppData', 'UserData', agentDirId, 'topics', topicId);
            try {
                await fs.rm(topicDir, { recursive: true, force: true });
            } catch (e) {
                // 目录不存在也无所谓
            }

            console.log(`[VCPChatMobile] 已删除话题 ${agentDirId}/${topicId}`);
            res.json({ success: true });
        } catch (error) {
            console.error('[VCPChatMobile] Error deleting topic:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // GET /agents/vcpchat-wallpapers
    // 返回 VCPChat 壁纸列表（文件名 + 缩略图URL）
    adminApiRouter.get('/agents/vcpchat-wallpapers', async (req, res) => {
        try {
            const wallpaperDir = path.join(getVCPChatPath(), 'assets', 'wallpaper');
            const thumbDir = path.join(getVCPChatPath(), 'AppData', 'WallpaperThumbnailCache');
            const entries = await fs.readdir(wallpaperDir);
            const imageExts = ['.jpg', '.jpeg', '.png', '.webp', '.gif'];
            const wallpapers = [];

            // 读取缩略图映射（文件名 hash → 缩略图文件）
            let thumbFiles = [];
            try { thumbFiles = await fs.readdir(thumbDir); } catch { /* 无缩略图目录 */ }

            for (const file of entries) {
                const ext = path.extname(file).toLowerCase();
                if (!imageExts.includes(ext)) continue;
                wallpapers.push({ name: file });
            }

            res.json({ success: true, wallpapers });
        } catch (error) {
            console.error('[VCPChatMobile] Error listing wallpapers:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });

    // GET /agents/vcpchat-wallpaper/:filename
    // 返回壁纸图片文件（支持缩略图模式 ?thumb=1）
    adminApiRouter.get('/agents/vcpchat-wallpaper/:filename', async (req, res) => {
        try {
            const filename = req.params.filename;
            // 安全检查：防止路径穿越
            if (filename.includes('..') || filename.includes('/') || filename.includes('\\')) {
                return res.status(400).json({ success: false, error: '非法文件名' });
            }

            const wallpaperDir = path.join(getVCPChatPath(), 'assets', 'wallpaper');
            const filePath = path.join(wallpaperDir, filename);

            try {
                await fs.access(filePath);
            } catch {
                return res.status(404).json({ success: false, error: '壁纸不存在' });
            }

            // 设置缓存头（壁纸不常变）
            res.setHeader('Cache-Control', 'public, max-age=86400');
            const ext = path.extname(filename).toLowerCase();
            const mimeMap = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp', '.gif': 'image/gif' };
            res.setHeader('Content-Type', mimeMap[ext] || 'application/octet-stream');

            const fsSync = require('fs');
            fsSync.createReadStream(filePath).pipe(res);
        } catch (error) {
            console.error('[VCPChatMobile] Error serving wallpaper:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });
};
