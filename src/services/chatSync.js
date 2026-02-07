// services/chatSync.js
// vcp-mobile 聊天记录同步模块
// 与 VCPToolBox 的 ChatSync 插件通信，实现跨设备消息级增量同步

import { normalizeBaseUrl } from './vcpApi'

const SYNC_PREFIX = '[ChatSync]'
const SYNC_TIMESTAMP_KEY = 'vcpSyncTimestamps' // localStorage key for per-topic sync timestamps

// ========== 工具函数 ==========

function log(...args) {
  console.log(SYNC_PREFIX, ...args)
}

function warn(...args) {
  console.warn(SYNC_PREFIX, ...args)
}

/**
 * 获取每个话题的上次同步时间戳
 */
function getSyncTimestamps() {
  try {
    const raw = localStorage.getItem(SYNC_TIMESTAMP_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

/**
 * 保存同步时间戳
 */
function saveSyncTimestamp(topicId, timestamp) {
  const timestamps = getSyncTimestamps()
  timestamps[topicId] = timestamp
  localStorage.setItem(SYNC_TIMESTAMP_KEY, JSON.stringify(timestamps))
}

/**
 * 构建同步 API 的请求头
 * 使用 Admin Panel 的 Basic Auth 认证
 */
function buildSyncHeaders(syncConfig) {
  const headers = { 'Content-Type': 'application/json' }
  if (syncConfig.adminUsername && syncConfig.adminPassword) {
    const credentials = btoa(`${syncConfig.adminUsername}:${syncConfig.adminPassword}`)
    headers['Authorization'] = `Basic ${credentials}`
  }
  return headers
}

/**
 * 构建同步 API 的基础 URL
 */
function buildSyncBaseUrl(syncConfig) {
  const baseUrl = normalizeBaseUrl(syncConfig.baseUrl || '')
  if (!baseUrl) return ''
  return `${baseUrl}/admin_api/chat-sync`
}

// ========== 核心同步函数 ==========

/**
 * 检查同步服务是否可用
 */
export async function checkSyncStatus(syncConfig) {
  const syncUrl = buildSyncBaseUrl(syncConfig)
  if (!syncUrl) return { available: false, error: '未配置同步地址' }

  try {
    const response = await fetch(`${syncUrl}/status`, {
      headers: buildSyncHeaders(syncConfig),
    })
    if (!response.ok) {
      return { available: false, error: `HTTP ${response.status}` }
    }
    const data = await response.json()
    return { available: data.success, data }
  } catch (error) {
    return { available: false, error: error.message }
  }
}

/**
 * 同步单个话题的聊天记录
 * @param {Object} syncConfig - { baseUrl, adminUsername, adminPassword }
 * @param {string} agentId - Agent ID（移动端可用固定值如 'mobile-default'）
 * @param {string} topicId - 话题 ID
 * @param {Array} localMessages - 本地消息列表
 * @returns {Object} { success, newMessages, mergedCount }
 */
export async function syncTopic(syncConfig, agentId, topicId, localMessages) {
  const syncUrl = buildSyncBaseUrl(syncConfig)
  if (!syncUrl) return { success: false, error: '未配置同步地址' }

  const timestamps = getSyncTimestamps()
  const lastSyncTimestamp = timestamps[topicId] || 0

  // 筛选出本地在上次同步之后的新消息
  const clientNewMessages = localMessages.filter(
    (msg) => (msg.timestamp || 0) > lastSyncTimestamp
  )

  try {
    const response = await fetch(`${syncUrl}/sync`, {
      method: 'POST',
      headers: buildSyncHeaders(syncConfig),
      body: JSON.stringify({
        agentId,
        topicId,
        clientMessages: clientNewMessages,
        lastSyncTimestamp,
      }),
    })

    if (!response.ok) {
      const text = await response.text()
      return { success: false, error: `HTTP ${response.status}: ${text}` }
    }

    const data = await response.json()
    if (!data.success) {
      return { success: false, error: data.error }
    }

    // 更新同步时间戳
    saveSyncTimestamp(topicId, data.lastSyncTimestamp)

    // 返回服务端的新消息（需要合并到本地）
    return {
      success: true,
      serverNewMessages: data.serverNewMessages || [],
      mergedCount: data.mergedCount,
      newFromClient: data.newFromClient,
    }
  } catch (error) {
    warn(`同步话题 ${topicId} 失败:`, error.message)
    return { success: false, error: error.message }
  }
}

/**
 * 将服务端新消息合并到本地消息列表
 * @param {Array} localMessages - 本地消息
 * @param {Array} serverNewMessages - 服务端返回的新消息
 * @returns {Array} 合并后的消息列表
 */
export function mergeServerMessages(localMessages, serverNewMessages) {
  if (!serverNewMessages || serverNewMessages.length === 0) return localMessages

  const messageMap = new Map()

  // 先放入本地消息
  for (const msg of localMessages) {
    if (msg.id) messageMap.set(msg.id, msg)
  }

  // 合并服务端新消息（本地没有的才加入）
  let addedCount = 0
  for (const msg of serverNewMessages) {
    if (msg.id && !messageMap.has(msg.id)) {
      messageMap.set(msg.id, msg)
      addedCount++
    }
  }

  if (addedCount === 0) return localMessages

  // 按时间戳排序
  const merged = Array.from(messageMap.values())
  merged.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0))

  log(`合并了 ${addedCount} 条来自服务端的新消息`)
  return merged
}

/**
 * 同步话题元数据列表
 */
export async function syncTopics(syncConfig, agentId, localTopics) {
  const syncUrl = buildSyncBaseUrl(syncConfig)
  if (!syncUrl) return { success: false, error: '未配置同步地址' }

  try {
    // 1. 上传本地话题列表
    const putResponse = await fetch(`${syncUrl}/agents/${agentId}/topics`, {
      method: 'PUT',
      headers: buildSyncHeaders(syncConfig),
      body: JSON.stringify(localTopics),
    })

    if (!putResponse.ok) {
      return { success: false, error: `上传话题列表失败: HTTP ${putResponse.status}` }
    }

    // 2. 拉取合并后的话题列表
    const getResponse = await fetch(`${syncUrl}/agents/${agentId}/topics`, {
      headers: buildSyncHeaders(syncConfig),
    })

    if (!getResponse.ok) {
      return { success: false, error: `拉取话题列表失败: HTTP ${getResponse.status}` }
    }

    const data = await getResponse.json()
    return { success: true, topics: data.topics || [] }
  } catch (error) {
    warn('同步话题列表失败:', error.message)
    return { success: false, error: error.message }
  }
}

/**
 * 全量同步：同步所有话题的聊天记录
 * @param {Object} syncConfig - 同步配置
 * @param {string} agentId - Agent ID
 * @param {Array} localTopics - 本地话题列表
 * @param {Function} getMessages - (topicId) => messages[] 获取本地消息的函数
 * @param {Function} setMessages - (topicId, messages[]) => void 设置本地消息的函数
 * @param {Function} onProgress - (current, total, topicTitle) => void 进度回调
 */
export async function fullSync(syncConfig, agentId, localTopics, getMessages, setMessages, onProgress) {
  const syncUrl = buildSyncBaseUrl(syncConfig)
  if (!syncUrl) return { success: false, error: '未配置同步地址' }

  log(`开始全量同步，共 ${localTopics.length} 个话题`)

  // 1. 先同步话题列表
  await syncTopics(syncConfig, agentId, localTopics)

  // 2. 逐个同步话题的聊天记录
  let syncedCount = 0
  let errorCount = 0

  for (let i = 0; i < localTopics.length; i++) {
    const topic = localTopics[i]
    const topicId = topic.id || topic.topicId

    if (onProgress) onProgress(i + 1, localTopics.length, topic.title)

    const localMessages = getMessages(topicId)
    const result = await syncTopic(syncConfig, agentId, topicId, localMessages)

    if (result.success) {
      // 合并服务端新消息到本地
      const merged = mergeServerMessages(localMessages, result.serverNewMessages)
      if (merged !== localMessages) {
        setMessages(topicId, merged)
      }
      syncedCount++
    } else {
      errorCount++
      warn(`话题 ${topicId} 同步失败: ${result.error}`)
    }
  }

  log(`全量同步完成: ${syncedCount} 成功, ${errorCount} 失败`)
  return { success: true, syncedCount, errorCount, total: localTopics.length }
}

/**
 * 拉取某话题的完整聊天记录（用于首次同步或恢复）
 */
export async function pullHistory(syncConfig, agentId, topicId) {
  const syncUrl = buildSyncBaseUrl(syncConfig)
  if (!syncUrl) return { success: false, error: '未配置同步地址' }

  try {
    const response = await fetch(`${syncUrl}/history/${agentId}/${topicId}`, {
      headers: buildSyncHeaders(syncConfig),
    })

    if (!response.ok) {
      return { success: false, error: `HTTP ${response.status}` }
    }

    const data = await response.json()
    if (data.success) {
      // 更新同步时间戳
      if (data.messages && data.messages.length > 0) {
        const maxTs = Math.max(...data.messages.map((m) => m.timestamp || 0))
        saveSyncTimestamp(topicId, maxTs)
      }
      return { success: true, messages: data.messages || [] }
    }
    return { success: false, error: data.error }
  } catch (error) {
    return { success: false, error: error.message }
  }
}
