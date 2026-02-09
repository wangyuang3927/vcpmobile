// services/vcpPush.js
// vcp-mobile WebSocket 推送服务：接收 VCPToolBox 主动推送的消息
// 前台用 WebSocket 实时接收，断线自动重连

import { normalizeBaseUrl } from './vcpApi'

const TAG = '[VCPPush]'
let ws = null
let reconnectTimer = null
let heartbeatTimer = null
let onMessageCallback = null
let onStatusChangeCallback = null
let currentConfig = null
let reconnectAttempts = 0
const MAX_RECONNECT_DELAY = 30000 // 最大重连间隔 30s

function getWsUrl(config) {
  const baseUrl = normalizeBaseUrl(config.baseUrl)
  if (!baseUrl) return null
  // http://host:port → ws://host:port
  const wsBase = baseUrl.replace(/^http/, 'ws')
  const vcpKey = config.apiKey || ''
  return `${wsBase}/vcp-mobile/VCP_Key=${vcpKey}`
}

function updateStatus(status) {
  if (onStatusChangeCallback) onStatusChangeCallback(status)
}

function scheduleReconnect() {
  if (reconnectTimer) return
  reconnectAttempts++
  const delay = Math.min(2000 * Math.pow(1.5, reconnectAttempts - 1), MAX_RECONNECT_DELAY)
  console.log(`${TAG} 将在 ${Math.round(delay / 1000)}s 后重连 (第${reconnectAttempts}次)`)
  updateStatus('reconnecting')
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect(currentConfig)
  }, delay)
}

function startHeartbeat() {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'heartbeat', timestamp: Date.now() }))
    }
  }, 25000) // 每 25s 发一次心跳
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

export function connect(config) {
  if (!config || !config.baseUrl || !config.apiKey) {
    console.warn(`${TAG} 缺少 baseUrl 或 apiKey，跳过连接`)
    return
  }
  currentConfig = config

  // 关闭已有连接
  disconnect(true)

  const wsUrl = getWsUrl(config)
  if (!wsUrl) return

  console.log(`${TAG} 正在连接 ${wsUrl.replace(/VCP_Key=.*/, 'VCP_Key=***')}`)
  updateStatus('connecting')

  try {
    ws = new WebSocket(wsUrl)
  } catch (err) {
    console.error(`${TAG} WebSocket 创建失败:`, err)
    scheduleReconnect()
    return
  }

  ws.onopen = () => {
    console.log(`${TAG} ✅ 已连接`)
    reconnectAttempts = 0
    updateStatus('connected')
    startHeartbeat()
  }

  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)

      if (data.type === 'connection_ack') {
        console.log(`${TAG} 收到连接确认`)
        return
      }

      if (data.type === 'heartbeat_ack') return

      // 所有其他消息交给回调处理
      console.log(`${TAG} 📨 收到推送:`, data.type)
      if (onMessageCallback) onMessageCallback(data)
    } catch (err) {
      console.error(`${TAG} 消息解析失败:`, err)
    }
  }

  ws.onclose = (event) => {
    console.log(`${TAG} 连接关闭 (code: ${event.code})`)
    stopHeartbeat()
    ws = null
    updateStatus('disconnected')
    // 非主动关闭时自动重连
    if (currentConfig) {
      scheduleReconnect()
    }
  }

  ws.onerror = (error) => {
    console.error(`${TAG} WebSocket 错误:`, error)
  }
}

export function disconnect(skipStatusUpdate = false) {
  currentConfig = null
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  stopHeartbeat()
  reconnectAttempts = 0
  if (ws) {
    ws.onclose = null // 防止触发重连
    ws.close()
    ws = null
  }
  if (!skipStatusUpdate) updateStatus('disconnected')
}

// 通过 WebSocket 发送消息给服务端（用于用户回复等场景）
export function sendMessage(data) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data))
    return true
  }
  return false
}

export function isConnected() {
  return ws && ws.readyState === WebSocket.OPEN
}

export function onPushMessage(callback) {
  onMessageCallback = callback
}

export function onStatusChange(callback) {
  onStatusChangeCallback = callback
}
