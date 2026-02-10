// services/agentService.js
// 从 VCPToolBox 服务端拉取 Agent 列表

import { normalizeBaseUrl } from './vcpApi'

const LOG_PREFIX = '[AgentService]'

function log(...args) {
  console.log(LOG_PREFIX, ...args)
}

function warn(...args) {
  console.warn(LOG_PREFIX, ...args)
}

/**
 * 构建 Admin API 请求头（Basic Auth）
 */
function buildAdminHeaders(config) {
  const headers = { 'Content-Type': 'application/json' }
  if (config.adminUsername && config.adminPassword) {
    const credentials = btoa(`${config.adminUsername}:${config.adminPassword}`)
    headers['Authorization'] = `Basic ${credentials}`
  }
  return headers
}

/**
 * 从服务端拉取 Agent 列表
 * @param {Object} config - { baseUrl, adminUsername, adminPassword }
 * @returns {Promise<{success: boolean, agents?: Array, error?: string}>}
 */
export async function fetchAgentList(config) {
  const baseUrl = normalizeBaseUrl(config.baseUrl || '')
  if (!baseUrl) return { success: false, error: '未配置服务器地址' }

  try {
    const response = await fetch(`${baseUrl}/admin_api/agents/mobile-list`, {
      headers: buildAdminHeaders(config),
    })

    if (!response.ok) {
      const text = await response.text()
      return { success: false, error: `HTTP ${response.status}: ${text}` }
    }

    const data = await response.json()
    if (!data.success || !Array.isArray(data.agents)) {
      return { success: false, error: data.error || '返回数据格式错误' }
    }

    log(`拉取到 ${data.agents.length} 个 Agent (来源: ${data.source || 'unknown'})`)
    return { success: true, agents: data.agents, source: data.source || 'unknown' }
  } catch (error) {
    warn('拉取 Agent 列表失败:', error.message)
    return { success: false, error: error.message }
  }
}

/**
 * 将服务端 Agent 数据转换为手机端格式
 * @param {Array} serverAgents - 服务端返回的 Agent 列表
 * @returns {Array} 手机端格式的 Agent 列表
 */
export function normalizeAgents(serverAgents) {
  return serverAgents.map((agent) => ({
    id: agent.name, // 用 name 作为唯一标识（与 ChatSync 的 agentId 一致）
    name: agent.name,
    description: agent.description || '',
    systemPrompt: agent.systemPrompt || '',
    modelId: agent.modelId || '',
    temperature: agent.temperature ?? 0.7,
    maxOutputTokens: agent.maxOutputTokens ?? 40000,
    file: agent.file || '',
    agentDirId: agent.agentDirId || '', // VCPChat 内部目录名
    topics: (agent.topics || []).map(t => ({
      id: t.id,
      name: t.name,
      createdAt: t.createdAt || 0,
    })),
  }))
}

/**
 * 从服务端拉取 VCPChat 指定话题的聊天记录
 * @param {Object} config - { baseUrl, adminUsername, adminPassword }
 * @param {string} agentDirId - VCPChat Agent 目录名
 * @param {string} topicId - 话题 ID
 * @returns {Promise<{success: boolean, messages?: Array, error?: string}>}
 */
export async function fetchTopicHistory(config, agentDirId, topicId, ifModifiedSince = 0) {
  const baseUrl = normalizeBaseUrl(config.baseUrl || '')
  if (!baseUrl) return { success: false, error: '未配置服务器地址' }
  if (!agentDirId) return { success: false, error: '缺少 agentDirId' }

  try {
    const params = new URLSearchParams({ agentDirId, topicId })
    if (ifModifiedSince) params.set('ifModifiedSince', String(ifModifiedSince))
    const response = await fetch(`${baseUrl}/admin_api/agents/vcpchat-history?${params}`, {
      headers: buildAdminHeaders(config),
    })

    if (!response.ok) {
      const text = await response.text()
      return { success: false, error: `HTTP ${response.status}: ${text}` }
    }

    const data = await response.json()
    if (!data.success) {
      return { success: false, error: data.error || '返回数据格式错误' }
    }

    // 服务端返回 notModified 表示内容未变化
    if (data.notModified) {
      return { success: true, notModified: true, lastModified: data.lastModified || 0 }
    }

    log(`拉取话题 ${topicId} 的 ${(data.messages || []).length} 条消息`)
    return { success: true, messages: data.messages || [], lastModified: data.lastModified || 0 }
  } catch (error) {
    warn('拉取聊天记录失败:', error.message)
    return { success: false, error: error.message }
  }
}

/**
 * 将手机端新消息追加到 VCPChat 桌面端的 history.json（双向同步）
 * @param {Object} config - { baseUrl, adminUsername, adminPassword }
 * @param {string} agentDirId - VCPChat Agent 目录名
 * @param {string} topicId - 话题 ID
 * @param {Array} messages - 要追加的消息数组
 * @param {string} [topicName] - 话题名称（新话题时传入）
 * @returns {Promise<{success: boolean, appended?: number, error?: string}>}
 */
export async function appendToHistory(config, agentDirId, topicId, messages, topicName) {
  const baseUrl = normalizeBaseUrl(config.baseUrl || '')
  if (!baseUrl) return { success: false, error: '未配置服务器地址' }
  if (!agentDirId || !messages || messages.length === 0) return { success: false, error: '参数不完整' }

  try {
    const response = await fetch(`${baseUrl}/admin_api/agents/vcpchat-append-history`, {
      method: 'POST',
      headers: { ...buildAdminHeaders(config), 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentDirId, topicId, topicName, messages }),
    })

    if (!response.ok) {
      const text = await response.text()
      return { success: false, error: `HTTP ${response.status}: ${text}` }
    }

    const data = await response.json()
    if (!data.success) {
      return { success: false, error: data.error || '写入失败' }
    }

    log(`追加 ${data.appended} 条消息到桌面端 ${topicId}`)
    return { success: true, appended: data.appended }
  } catch (error) {
    warn('追加消息到桌面端失败:', error.message)
    return { success: false, error: error.message }
  }
}

/**
 * 从 VCPChat 桌面端删除话题（同步删除 config.json 条目 + 话题目录）
 * @param {Object} config - { baseUrl, adminUsername, adminPassword }
 * @param {string} agentDirId - VCPChat Agent 目录名
 * @param {string} topicId - 话题 ID
 * @returns {Promise<{success: boolean, error?: string}>}
 */
export async function deleteTopicFromDesktop(config, agentDirId, topicId) {
  const baseUrl = normalizeBaseUrl(config.baseUrl || '')
  if (!baseUrl) return { success: false, error: '未配置服务器地址' }

  try {
    const response = await fetch(`${baseUrl}/admin_api/agents/vcpchat-delete-topic`, {
      method: 'POST',
      headers: { ...buildAdminHeaders(config), 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentDirId, topicId }),
    })

    if (!response.ok) {
      const text = await response.text()
      return { success: false, error: `HTTP ${response.status}: ${text}` }
    }

    const data = await response.json()
    if (!data.success) return { success: false, error: data.error || '删除失败' }

    log(`已从桌面端删除话题 ${topicId}`)
    return { success: true }
  } catch (error) {
    warn('删除桌面端话题失败:', error.message)
    return { success: false, error: error.message }
  }
}

/**
 * 获取 VCPChat 壁纸列表
 * @param {Object} config - { baseUrl, adminUsername, adminPassword }
 * @returns {Promise<{success: boolean, wallpapers?: Array<{name: string}>, error?: string}>}
 */
export async function fetchWallpapers(config) {
  const baseUrl = normalizeBaseUrl(config.baseUrl || '')
  if (!baseUrl) return { success: false, error: '未配置服务器地址' }

  try {
    const response = await fetch(`${baseUrl}/admin_api/agents/vcpchat-wallpapers`, {
      headers: buildAdminHeaders(config),
    })
    if (!response.ok) return { success: false, error: `HTTP ${response.status}` }
    const data = await response.json()
    return data
  } catch (error) {
    return { success: false, error: error.message }
  }
}

/**
 * 获取壁纸图片URL
 * @param {Object} config - { baseUrl, adminUsername, adminPassword }
 * @param {string} filename - 壁纸文件名
 * @returns {string} 壁纸图片URL
 */
export function getWallpaperUrl(config, filename) {
  const baseUrl = normalizeBaseUrl(config.baseUrl || '')
  return `${baseUrl}/admin_api/agents/vcpchat-wallpaper/${encodeURIComponent(filename)}`
}

// localStorage 持久化 key
const AGENTS_STORAGE_KEY = 'vcpMobileAgents'
const ACTIVE_AGENT_KEY = 'vcpMobileActiveAgentId'

/**
 * 从 localStorage 加载缓存的 Agent 列表
 */
export function loadCachedAgents() {
  try {
    const raw = localStorage.getItem(AGENTS_STORAGE_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

/**
 * 保存 Agent 列表到 localStorage
 */
export function saveCachedAgents(agents) {
  localStorage.setItem(AGENTS_STORAGE_KEY, JSON.stringify(agents))
}

/**
 * 获取上次选中的 Agent ID
 */
export function getActiveAgentId() {
  return localStorage.getItem(ACTIVE_AGENT_KEY) || ''
}

/**
 * 保存当前选中的 Agent ID
 */
export function saveActiveAgentId(agentId) {
  localStorage.setItem(ACTIVE_AGENT_KEY, agentId)
}
