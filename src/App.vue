<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { fetchModels, normalizeBaseUrl, streamChat, interruptChat } from './services/vcpApi'
import { cleanupAllBubbleStyles, renderMessageHtml } from './utils/messageRenderer'
import { checkSyncStatus, syncTopic, mergeServerMessages, fullSync } from './services/chatSync'
import { connect as pushConnect, disconnect as pushDisconnect, onPushMessage, onStatusChange as onPushStatusChange } from './services/vcpPush'

const isLightTheme = ref(false)
const isSettingsOpen = ref(false)
const isSidebarOpen = ref(false)
const isStreaming = ref(false)
const isRecording = ref(false)
const isSyncing = ref(false)
const syncStatus = ref('')
const mediaRecorder = ref(null)
const audioChunks = ref([])
const statusMessage = ref('')
const streamAbortController = ref(null)
const models = ref([])
const pendingAttachments = ref([])
const fileInput = ref(null)

const topics = ref([])
const currentTopicId = ref(null)

const config = ref({
  baseUrl: '',
  apiKey: '',
  model: '',
  systemPrompt: '',
  enableAgentBubbleTheme: false,
  temperature: 0.7,
  maxTokens: 2048,
  syncEnabled: false,
  adminUsername: '',
  adminPassword: '',
  imageKey: '',
})

const pushStatus = ref('disconnected') // WebSocket 推送状态

const activeAgent = ref({
  name: 'Nova',
  status: '就绪',
})

const messages = ref([])

const loadHistory = () => {
  const savedTopics = localStorage.getItem('vcpMobileTopics')
  if (savedTopics) {
    topics.value = JSON.parse(savedTopics)
  }

  if (topics.value.length === 0) {
    createNewTopic()
  } else {
    const lastTopicId = localStorage.getItem('vcpMobileLastTopicId')
    if (lastTopicId && topics.value.find(t => t.id === lastTopicId)) {
      switchTopic(lastTopicId)
    } else {
      switchTopic(topics.value[0].id)
    }
  }
}

const saveHistory = () => {
  localStorage.setItem('vcpMobileTopics', JSON.stringify(topics.value))
  if (currentTopicId.value) {
    localStorage.setItem(`vcpMessages_${currentTopicId.value}`, JSON.stringify(messages.value))
    localStorage.setItem('vcpMobileLastTopicId', currentTopicId.value)
  }
}

const createNewTopic = () => {
  const newId = `topic_${Date.now()}`
  const newTopic = {
    id: newId,
    title: '新话题',
    timestamp: Date.now()
  }
  topics.value.unshift(newTopic)
  switchTopic(newId)
  saveHistory()
}

const switchTopic = (topicId) => {
  if (isStreaming.value) interruptStream()
  cleanupAllBubbleStyles()
  
  currentTopicId.value = topicId
  const savedMessages = localStorage.getItem(`vcpMessages_${topicId}`)
  if (savedMessages) {
    messages.value = JSON.parse(savedMessages)
  } else {
    messages.value = []
  }
  isSidebarOpen.value = false
}

const deleteTopic = (topicId) => {
  topics.value = topics.value.filter(t => t.id !== topicId)
  localStorage.removeItem(`vcpMessages_${topicId}`)
  if (currentTopicId.value === topicId) {
    if (topics.value.length > 0) {
      switchTopic(topics.value[0].id)
    } else {
      createNewTopic()
    }
  }
  saveHistory()
}

const updateTopicTitle = (message) => {
  const topic = topics.value.find(t => t.id === currentTopicId.value)
  if (topic && topic.title === '新话题' && message) {
    topic.title = message.slice(0, 20) + (message.length > 20 ? '...' : '')
    saveHistory()
  }
}

const draftMessage = ref('')

const formatTime = (value) =>
  new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

const renderContent = (message) =>
  renderMessageHtml(message.content, {
    messageId: message.id,
    role: message.role,
    allowBubbleCss: config.value.enableAgentBubbleTheme,
    baseUrl: config.value.baseUrl,
    imageKey: config.value.imageKey,
    isStreaming: message.isStreaming,
  })

const agentBubbleThemeSpec = `【VCP-Mobile 沉浸式气泡渲染协议】
你的核心任务是将每一次回复构建为美观的HTML气泡。

1. 根级封装：必须将所有回复内容包裹在 <div id="vcp-root" style="你的内联样式"> 中。
2. 样式注入：使用 <style> 标签注入额外CSS（会被自动作用域隔离）。
3. 内联样式优先：由于是流式渲染，推荐使用内联 style 属性确保样式即时生效。
4. 移动端适配：宽度不要超出容器，使用 word-wrap:break-word，图片用 max-width:100%。
5. 禁止Markdown：在div模式下不输出md格式，代码用 <pre style="..."><code>...</code></pre>。
6. 贴纸：使用 <img src="/pw=STICKER_NAME" style="width:80px;"/>

示例：
<div id="vcp-root" style="background:linear-gradient(135deg,#667eea,#764ba2);padding:20px;border-radius:20px;color:#fff;">
  <p style="font-size:16px;">✨ 你好，主人！</p>
  <img src="/pw=A_01" style="width:80px;border-radius:10px;"/>
</div>
<style>
#vcp-root { box-shadow: 0 4px 15px rgba(0,0,0,0.3); }
#vcp-root p { margin: 8px 0; line-height: 1.6; }
</style>`

const toggleTheme = () => {
  isLightTheme.value = !isLightTheme.value
  document.body.classList.toggle('light-theme', isLightTheme.value)
}

const loadConfig = () => {
  const saved = localStorage.getItem('vcpMobileConfig')
  if (!saved) return
  try {
    const parsed = JSON.parse(saved)
    config.value = { ...config.value, ...parsed }
    document.body.classList.toggle('agent-bubble-theme', !!config.value.enableAgentBubbleTheme)
  } catch (error) {
    console.warn('Failed to parse config', error)
  }
}

const saveConfig = async () => {
  localStorage.setItem('vcpMobileConfig', JSON.stringify(config.value))
  document.body.classList.toggle('agent-bubble-theme', !!config.value.enableAgentBubbleTheme)
  await refreshModels()
  isSettingsOpen.value = false
  statusMessage.value = '设置已保存'
  setTimeout(() => { if (statusMessage.value === '设置已保存') statusMessage.value = '' }, 2000)
  // 重连 WebSocket 推送
  initPushConnection()
}

const refreshModels = async () => {
  const baseUrl = normalizeBaseUrl(config.value.baseUrl)
  if (!baseUrl) {
    statusMessage.value = '请先在设置中配置后端地址。'
    return
  }
  try {
    models.value = await fetchModels({
      baseUrl,
      apiKey: config.value.apiKey,
    })
    if (!config.value.model && models.value.length > 0) {
      config.value.model = models.value[0]
    }
    statusMessage.value = '模型列表已更新'
    setTimeout(() => { if (statusMessage.value === '模型列表已更新') statusMessage.value = '' }, 2000)
  } catch (error) {
    statusMessage.value = `获取模型失败: ${error.message || error}`
  }
}

const interruptStream = async () => {
  if (streamAbortController.value) {
    streamAbortController.value.abort()
    streamAbortController.value = null
  }
  
  const baseUrl = normalizeBaseUrl(config.value.baseUrl)
  if (baseUrl) {
    // Attempt to notify server to stop generation
    const lastAssistantMsg = messages.value.filter(m => m.role === 'assistant').pop()
    if (lastAssistantMsg) {
      await interruptChat({
        baseUrl,
        apiKey: config.value.apiKey,
        requestId: lastAssistantMsg.id
      })
    }
  }
  
  isStreaming.value = false
}

const buildPayloadMessages = (items) => {
  const payload = []
  const systemParts = []
  if (config.value.systemPrompt) systemParts.push(config.value.systemPrompt)
  if (config.value.enableAgentBubbleTheme) systemParts.push(agentBubbleThemeSpec)
  const systemContent = systemParts.join('\n\n').trim()
  if (systemContent) payload.push({ role: 'system', content: systemContent })

  const history = items
    .filter((message) => ['user', 'assistant'].includes(message.role))
    .map((message) => {
      if (message.role === 'user' && message.attachments && message.attachments.length > 0) {
        const contentParts = [{ type: 'text', text: message.content || '' }]
        message.attachments.forEach((att) => {
          const mimeType = att.mimeType.toLowerCase()
          if (att.kind === 'audio' || att.kind === 'image' || mimeType.startsWith('audio/') || mimeType.startsWith('image/')) {
            contentParts.push({
              type: 'image_url',
              image_url: { url: att.url }
            })
          } else {
            contentParts.push({
              type: 'file',
              file: {
                filename: att.name,
                file_data: att.url
              }
            })
          }
        })
        return { role: message.role, content: contentParts }
      }
      return { role: message.role, content: message.content || '' }
    })
  
  return [...payload, ...history]
}

const triggerFileInput = () => {
  fileInput.value?.click()
}

const handleFileChange = async (event) => {
  const files = event.target.files
  if (!files.length) return

  for (const file of files) {
    const reader = new FileReader()
    reader.onload = (e) => {
      let kind = 'file'
      if (file.type.startsWith('image/')) kind = 'image'
      else if (file.type.startsWith('video/')) kind = 'video'
      else if (file.type.startsWith('audio/')) kind = 'audio'

      pendingAttachments.value.push({
        id: `att_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        name: file.name,
        mimeType: file.type,
        url: e.target.result,
        kind
      })
    }
    reader.readAsDataURL(file)
  }
  event.target.value = ''
}

const removeAttachment = (id) => {
  pendingAttachments.value = pendingAttachments.value.filter(a => a.id !== id)
}

const startRecording = async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    mediaRecorder.value = new MediaRecorder(stream)
    audioChunks.value = []

    mediaRecorder.value.ondataavailable = (event) => {
      audioChunks.value.push(event.data)
    }

    mediaRecorder.value.onstop = async () => {
      const audioBlob = new Blob(audioChunks.value, { type: 'audio/mp4' })
      const reader = new FileReader()
      reader.onload = (e) => {
        pendingAttachments.value.push({
          id: `rec_${Date.now()}`,
          name: `语音录制_${new Date().toLocaleTimeString()}.m4a`,
          mimeType: 'audio/mp4',
          url: e.target.result,
          kind: 'audio'
        })
      }
      reader.readAsDataURL(audioBlob)
      stream.getTracks().forEach(track => track.stop())
    }

    mediaRecorder.value.start()
    isRecording.value = true
    statusMessage.value = '正在录音...'
  } catch (err) {
    console.error('Record error:', err)
    statusMessage.value = '麦克风权限被拒绝'
  }
}

const stopRecording = () => {
  if (mediaRecorder.value && isRecording.value) {
    mediaRecorder.value.stop()
    isRecording.value = false
    statusMessage.value = '录音完成'
  }
}

const toggleRecording = () => {
  if (isRecording.value) stopRecording()
  else startRecording()
}

const handleBubbleToggle = (event) => {
  const toolHeader = event.target.closest('.vcp-tool-result-header')
  if (toolHeader) {
    toolHeader.closest('.vcp-tool-result-bubble')?.classList.toggle('expanded')
    return
  }

  const thoughtHeader = event.target.closest('.vcp-thought-chain-header')
  if (thoughtHeader) {
    thoughtHeader.closest('.vcp-thought-chain-bubble')?.classList.toggle('expanded')
  }
}

const sendMessage = () => {
  const text = draftMessage.value.trim()
  if (!text && pendingAttachments.value.length === 0) return

  const userMessage = {
    id: `msg_${Date.now()}`,
    role: 'user',
    name: 'You',
    content: text,
    attachments: [...pendingAttachments.value],
    timestamp: Date.now(),
  }

  updateTopicTitle(text || '多模态消息')
  const payloadMessages = buildPayloadMessages([...messages.value, userMessage])
  messages.value.push(userMessage)
  draftMessage.value = ''
  pendingAttachments.value = []

  const assistantId = `assistant_${Date.now()}`
  const assistantMessage = {
    id: assistantId,
    role: 'assistant',
    name: activeAgent.value.name,
    content: '',
    timestamp: Date.now(),
    isStreaming: true,
  }
  messages.value.push(assistantMessage)

  const baseUrl = normalizeBaseUrl(config.value.baseUrl)
  if (!baseUrl || !config.value.model) {
    assistantMessage.content = '⚠️ 请先配置后端地址和模型。'
    assistantMessage.isStreaming = false
    statusMessage.value = '缺少后端配置。'
    return
  }

  if (isStreaming.value) {
    interruptStream()
  }

  isStreaming.value = true
  statusMessage.value = '正在思考...'
  const controller = new AbortController()
  streamAbortController.value = controller

  streamChat({
    baseUrl,
    apiKey: config.value.apiKey,
    messages: payloadMessages,
    model: config.value.model,
    temperature: Number(config.value.temperature),
    maxTokens: Number(config.value.maxTokens),
    requestId: assistantId,
    signal: controller.signal,
    onChunk: (chunk) => {
      assistantMessage.content += chunk
    },
    onError: (error) => {
      const message = error?.message || error?.toString?.() || '流传输错误'
      assistantMessage.content = assistantMessage.content
        ? `${assistantMessage.content}\n\n${message}`
        : message
      statusMessage.value = '请求出错'
      isStreaming.value = false
      saveHistory()
    },
  })
    .then(() => {
      assistantMessage.isStreaming = false
      isStreaming.value = false
      streamAbortController.value = null
      statusMessage.value = '就绪'
      saveHistory()
      backgroundSync(currentTopicId.value, messages.value)
    })
    .catch((error) => {
      const message = error?.message || error?.toString?.() || '流传输失败'
      assistantMessage.content = assistantMessage.content
        ? `${assistantMessage.content}\n\n${message}`
        : message
      assistantMessage.isStreaming = false
      isStreaming.value = false
      streamAbortController.value = null
      statusMessage.value = '请求失败'
      saveHistory()
    })
}

const getSyncConfig = () => ({
  baseUrl: config.value.baseUrl,
  adminUsername: config.value.adminUsername,
  adminPassword: config.value.adminPassword,
})

const SYNC_AGENT_ID = 'mobile-default'

const backgroundSync = async (topicId, localMessages) => {
  if (!config.value.syncEnabled || !config.value.baseUrl || !config.value.adminUsername) return
  try {
    const result = await syncTopic(getSyncConfig(), SYNC_AGENT_ID, topicId, localMessages)
    if (result.success && result.serverNewMessages && result.serverNewMessages.length > 0) {
      const merged = mergeServerMessages(localMessages, result.serverNewMessages)
      if (merged !== localMessages) {
        messages.value = merged
        saveHistory()
      }
    }
  } catch (e) {
    console.warn('[ChatSync] 后台同步失败:', e.message)
  }
}

const manualSync = async () => {
  if (isSyncing.value) return
  if (!config.value.syncEnabled || !config.value.baseUrl || !config.value.adminUsername) {
    syncStatus.value = '请先在设置中配置同步'
    return
  }
  isSyncing.value = true
  syncStatus.value = '正在同步...'

  try {
    const status = await checkSyncStatus(getSyncConfig())
    if (!status.available) {
      syncStatus.value = `同步服务不可用: ${status.error}`
      isSyncing.value = false
      return
    }

    const getMessages = (topicId) => {
      const saved = localStorage.getItem(`vcpMessages_${topicId}`)
      return saved ? JSON.parse(saved) : []
    }
    const setMessages = (topicId, msgs) => {
      localStorage.setItem(`vcpMessages_${topicId}`, JSON.stringify(msgs))
      if (topicId === currentTopicId.value) {
        messages.value = msgs
      }
    }

    const result = await fullSync(
      getSyncConfig(),
      SYNC_AGENT_ID,
      topics.value,
      getMessages,
      setMessages,
      (current, total, title) => {
        syncStatus.value = `同步中 ${current}/${total}: ${title || ''}`
      }
    )

    if (result.success) {
      syncStatus.value = `同步完成: ${result.syncedCount}/${result.total} 个话题`
    } else {
      syncStatus.value = `同步失败: ${result.error}`
    }
  } catch (e) {
    syncStatus.value = `同步出错: ${e.message}`
  } finally {
    isSyncing.value = false
    setTimeout(() => { syncStatus.value = '' }, 5000)
  }
}

// WebSocket 推送初始化
function initPushConnection() {
  if (config.value.baseUrl && config.value.apiKey) {
    pushConnect({ baseUrl: config.value.baseUrl, apiKey: config.value.apiKey })
  }
}

// 处理服务端推送的消息
onPushMessage((data) => {
  console.log('[App] 收到推送消息:', data.type)
  if (data.type === 'agent_message' || data.type === 'mobile_push') {
    const payload = data.data || data
    const pushMsg = {
      role: 'assistant',
      content: payload.message || payload.content || JSON.stringify(payload),
      name: payload.recipient || 'AI',
      timestamp: Date.now(),
      isPush: true, // 标记为推送消息
    }
    messages.value.push(pushMsg)
    saveHistory()
    // 更新状态栏
    statusMessage.value = '💬 收到新消息'
    setTimeout(() => { if (statusMessage.value === '💬 收到新消息') statusMessage.value = '' }, 3000)
  }
})

onPushStatusChange((status) => {
  pushStatus.value = status
  console.log('[App] 推送状态:', status)
})

onMounted(() => {
  document.body.classList.toggle('light-theme', isLightTheme.value)
  loadConfig()
  loadHistory()
  if (config.value.baseUrl) {
    refreshModels()
    initPushConnection()
    if (config.value.syncEnabled && config.value.adminUsername) {
      setTimeout(() => backgroundSync(currentTopicId.value, messages.value), 2000)
    }
  }
})

onUnmounted(() => {
  pushDisconnect()
})
</script>

<template>
  <div class="app-shell">
    <header class="chat-header">
      <div class="header-left">
        <button class="icon-button" type="button" @click="isSidebarOpen = true">菜单</button>
        <div class="header-title">
          <span class="agent-name">{{ activeAgent.name }}</span>
          <span class="agent-status">{{ activeAgent.status }}</span>
          <span class="push-dot" :class="pushStatus" :title="pushStatus === 'connected' ? '推送已连接' : '推送未连接'"></span>
        </div>
      </div>
      <div class="header-actions">
        <button class="icon-button" type="button" @click="initPushConnection">
          连接
        </button>
        <button class="icon-button" type="button" @click="toggleTheme">
          主题
        </button>
        <button class="icon-button" type="button" @click="isSettingsOpen = true">
          设置
        </button>
      </div>
    </header>

    <main class="chat-body">
      <div v-if="isStreaming" class="stream-banner">
        <span>模型正在响应...</span>
        <button class="icon-button" type="button" @click="interruptStream">
          停止
        </button>
      </div>
      <div v-if="statusMessage" class="status-banner">{{ statusMessage }}</div>
      <div class="chat-messages-container" @click="handleBubbleToggle">
        <div class="chat-messages">
          <div
            v-for="message in messages"
            :key="message.id"
            :class="['message-item', message.role]"
          >
            <div class="details-and-bubble-wrapper">
              <div class="name-time-block">
                <div class="chat-avatar">
                  <span>{{ (message.name || (message.role === 'user' ? 'U' : 'A')).slice(0, 1).toUpperCase() }}</span>
                </div>
                <div class="sender-name">{{ message.role === 'user' ? '你' : (message.name || 'AI') }}</div>
                <div class="message-timestamp">{{ formatTime(message.timestamp) }}</div>
              </div>
              <div class="md-content">
                <div v-html="renderContent(message)"></div>
                <!-- 消息附件 -->
                <div v-if="message.attachments && message.attachments.length > 0" class="message-attachments-preview">
                  <div v-for="att in message.attachments" :key="att.id" class="attachment-item-bubble">
                    <img v-if="att.kind === 'image'" :src="att.url" class="msg-att-img" />
                    <video v-else-if="att.kind === 'video'" :src="att.url" controls class="msg-att-video"></video>
                    <div v-else class="msg-att-file">
                      <span class="file-icon">{{ att.kind === 'audio' ? '🎵' : '📄' }}</span>
                      <span class="file-name">{{ att.name }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>

    <footer class="input-bar">
      <!-- 附件预览行 -->
      <div v-if="pendingAttachments.length > 0" class="pending-attachments-row">
        <div v-for="att in pendingAttachments" :key="att.id" class="pending-attachment-chip">
          <span class="chip-text">{{ att.name }}</span>
          <button class="remove-chip" @click="removeAttachment(att.id)">×</button>
        </div>
      </div>

      <div class="input-controls">
        <button class="icon-button" type="button" @click="triggerFileInput">+</button>
        <button 
          class="icon-button recording-btn" 
          :class="{ active: isRecording }" 
          type="button" 
          @click="toggleRecording"
        >
          {{ isRecording ? '⏹' : '🎤' }}
        </button>
        <label class="input-wrapper">
          <span class="sr-only">消息</span>
          <textarea
            v-model="draftMessage"
            rows="1"
            placeholder="输入消息..."
            @keydown.enter.exact.prevent="sendMessage"
          ></textarea>
        </label>
        <button class="send-button" type="button" @click="sendMessage">发送</button>
      </div>

      <!-- 隐藏的文件输入 -->
      <input 
        ref="fileInput" 
        type="file" 
        multiple 
        style="display: none" 
        @change="handleFileChange"
      />
    </footer>

    <div v-if="isSettingsOpen" class="settings-panel">
      <div class="settings-card">
        <div class="settings-header">
          <h3>VCP 移动端设置</h3>
          <button class="icon-button" type="button" @click="isSettingsOpen = false">
            关闭
          </button>
        </div>
        <div class="settings-body">
          <label class="settings-toggle">
            <span>启用 Agent 气泡主题</span>
            <input v-model="config.enableAgentBubbleTheme" type="checkbox" />
          </label>
          <label>
            <span>接口地址 (主机:端口)</span>
            <input v-model="config.baseUrl" placeholder="例如 http://127.0.0.1:6005" />
          </label>
          <label>
            <span>API 密钥</span>
            <input v-model="config.apiKey" placeholder="Bearer 令牌" />
          </label>
          <label>
            <span>模型</span>
            <select v-model="config.model">
              <option value="">选择模型</option>
              <option v-for="model in models" :key="model" :value="model">
                {{ model }}
              </option>
            </select>
          </label>
          <label>
            <span>温度 (Temperature)</span>
            <input v-model.number="config.temperature" type="number" min="0" max="2" step="0.1" />
          </label>
          <label>
            <span>最大令牌数 (Max Tokens)</span>
            <input v-model.number="config.maxTokens" type="number" min="64" max="4096" step="64" />
          </label>
          <div class="settings-divider">聊天记录同步</div>
          <label class="settings-toggle">
            <span>启用跨设备同步</span>
            <input v-model="config.syncEnabled" type="checkbox" />
          </label>
          <label v-if="config.syncEnabled">
            <span>管理面板用户名</span>
            <input v-model="config.adminUsername" placeholder="AdminPanel 用户名" />
          </label>
          <label v-if="config.syncEnabled">
            <span>管理面板密码</span>
            <input v-model="config.adminPassword" type="password" placeholder="AdminPanel 密码" />
          </label>
          <label>
            <span>图片密钥 (Image Key)</span>
            <input v-model="config.imageKey" placeholder="服务器 Image_Key，用于加载表情图" />
          </label>
          <div class="settings-divider">其他</div>
          <label>
            <span>系统提示词 (System Prompt)</span>
            <textarea 
              v-model="config.systemPrompt" 
              placeholder="例如：你是一个得力的助手..."
              rows="4"
              class="settings-textarea"
            ></textarea>
          </label>
        </div>
        <div class="settings-footer">
          <button class="icon-button" type="button" @click="refreshModels">
            刷新模型
          </button>
          <button class="send-button" type="button" @click="saveConfig">
            保存
          </button>
        </div>
      </div>
    </div>

    <div v-if="isSidebarOpen" class="sidebar-overlay" @click.self="isSidebarOpen = false">
      <div class="sidebar">
        <div class="sidebar-header">
          <h3>话题管理</h3>
          <button class="icon-button" @click="isSidebarOpen = false">关闭</button>
        </div>
        <div class="sidebar-content">
          <button class="new-topic-btn" @click="createNewTopic">
            + 开启新话题
          </button>
          
          <div class="sidebar-section-title">活跃 Agent</div>
          <div class="agent-item active">
            <div class="agent-avatar">{{ activeAgent.name[0] }}</div>
            <div class="agent-info">
              <div class="agent-name">{{ activeAgent.name }}</div>
              <div class="agent-meta">当前对话中</div>
            </div>
          </div>

          <div class="sidebar-section-title">历史话题</div>
          <div class="topic-list">
            <div 
              v-for="topic in topics" 
              :key="topic.id" 
              class="topic-item"
              :class="{ active: currentTopicId === topic.id }"
              @click="switchTopic(topic.id)"
            >
              <span class="topic-icon">💬</span>
              <span class="topic-title">{{ topic.title }}</span>
              <button class="delete-topic-btn" @click.stop="deleteTopic(topic.id)">×</button>
            </div>
          </div>

          <button 
            class="new-topic-btn sync-btn" 
            :disabled="isSyncing" 
            @click="manualSync"
          >
            {{ isSyncing ? '同步中...' : '🔄 同步聊天记录' }}
          </button>
          <div v-if="syncStatus" class="sync-status">{{ syncStatus }}</div>

          <div class="sidebar-footer-info">
            VCP Mobile v1.0.0
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
