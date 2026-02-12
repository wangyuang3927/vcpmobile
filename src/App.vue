<script setup>
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { fetchModels, normalizeBaseUrl, streamChat, interruptChat } from './services/vcpApi'
import { cleanupAllBubbleStyles, renderMessageHtml } from './utils/messageRenderer'
import { checkSyncStatus, syncTopic, mergeServerMessages, fullSync } from './services/chatSync'
import { connect as pushConnect, disconnect as pushDisconnect, onPushMessage, onStatusChange as onPushStatusChange } from './services/vcpPush'
import { fetchAgentList, normalizeAgents, loadCachedAgents, saveCachedAgents, getActiveAgentId, saveActiveAgentId, fetchTopicHistory, appendToHistory, deleteTopicFromDesktop } from './services/agentService'
import { getCachedMessages, setCachedMessages } from './services/messageCache'

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
const cameraInput = ref(null)
const videoInput = ref(null)
const isAttachMenuOpen = ref(false)
const isCompressing = ref(false)

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
  screenshotPresetMessage: '识别截图内容并记录日记',
  clipPresetMessage: '分析以下内容',
})

const pushStatus = ref('disconnected') // WebSocket 推送状态

// 长按消息操作菜单
const actionSheetVisible = ref(false)
const actionSheetMessage = ref(null)
let longPressTimer = null

const onMsgTouchStart = (event, message) => {
  longPressTimer = setTimeout(() => {
    actionSheetMessage.value = message
    actionSheetVisible.value = true
  }, 500)
}
const onMsgTouchEnd = () => {
  clearTimeout(longPressTimer)
}
const closeActionSheet = () => {
  actionSheetVisible.value = false
  actionSheetMessage.value = null
}

// 复制文本
const doCopyMessage = async () => {
  if (!actionSheetMessage.value) return
  try {
    await navigator.clipboard.writeText(actionSheetMessage.value.content || '')
    statusMessage.value = '已复制'
    setTimeout(() => { statusMessage.value = '' }, 1500)
  } catch {
    statusMessage.value = '复制失败'
  }
  closeActionSheet()
}

// 删除消息
const doDeleteMessage = () => {
  if (!actionSheetMessage.value) return
  const msgId = actionSheetMessage.value.id
  messages.value = messages.value.filter(m => m.id !== msgId)
  saveHistory()
  // VCPChat Agent：同步到桌面端（重写整个话题历史）
  const agent = activeAgent.value
  if (agent.agentDirId && config.value.baseUrl && config.value.adminUsername) {
    const syncConfig = { baseUrl: config.value.baseUrl, adminUsername: config.value.adminUsername, adminPassword: config.value.adminPassword }
    const topicName = topics.value.find(t => t.id === currentTopicId.value)?.title || ''
    appendToHistory(syncConfig, agent.agentDirId, currentTopicId.value, messages.value.map(m => ({
      id: m.id, role: m.role, name: m.name, content: m.content, timestamp: m.timestamp,
    })), topicName).catch(e => console.warn('[App] 同步删除到桌面端失败:', e.message))
  }
  statusMessage.value = '已删除'
  setTimeout(() => { statusMessage.value = '' }, 1500)
  closeActionSheet()
}

// 阅读模式
const readModeVisible = ref(false)
const readModeMessage = ref(null)
const openReadMode = () => {
  readModeMessage.value = actionSheetMessage.value
  readModeVisible.value = true
  closeActionSheet()
}
const closeReadMode = () => {
  readModeVisible.value = false
  readModeMessage.value = null
}

// 编辑消息
const editModeVisible = ref(false)
const editModeText = ref('')
const editModeMessageId = ref(null)
const openEditMode = () => {
  if (!actionSheetMessage.value) return
  editModeMessageId.value = actionSheetMessage.value.id
  editModeText.value = actionSheetMessage.value.content || ''
  editModeVisible.value = true
  closeActionSheet()
}
const saveEditMessage = () => {
  const msg = messages.value.find(m => m.id === editModeMessageId.value)
  if (msg) {
    msg.content = editModeText.value
    saveHistory()
    statusMessage.value = '已保存'
    setTimeout(() => { statusMessage.value = '' }, 1500)
  }
  editModeVisible.value = false
  editModeText.value = ''
  editModeMessageId.value = null
}
const cancelEditMode = () => {
  editModeVisible.value = false
  editModeText.value = ''
  editModeMessageId.value = null
}

// 转发消息
const forwardPickerVisible = ref(false)
const forwardMessage = ref(null)
const openForwardPicker = () => {
  forwardMessage.value = actionSheetMessage.value
  forwardPickerVisible.value = true
  closeActionSheet()
}
const closeForwardPicker = () => {
  forwardPickerVisible.value = false
  forwardMessage.value = null
}
const doForwardMessage = async (targetAgent, targetTopicId) => {
  if (!forwardMessage.value || !targetAgent.agentDirId) return
  const syncConfig = { baseUrl: config.value.baseUrl, adminUsername: config.value.adminUsername, adminPassword: config.value.adminPassword }
  const fwdMsg = {
    id: `fwd_${Date.now()}`,
    role: forwardMessage.value.role,
    name: forwardMessage.value.name || (forwardMessage.value.role === 'user' ? 'You' : 'AI'),
    content: `[转发消息]\n${forwardMessage.value.content}`,
    timestamp: Date.now(),
  }
  const topicTitle = `转发的消息 ${new Date().toLocaleDateString()}`
  try {
    const result = await appendToHistory(syncConfig, targetAgent.agentDirId, targetTopicId || `fwd_${Date.now()}`, [fwdMsg], topicTitle)
    if (result.success) {
      statusMessage.value = `已转发到 ${targetAgent.name}`
    } else {
      statusMessage.value = '转发失败: ' + (result.error || '')
    }
  } catch (e) {
    statusMessage.value = '转发失败: ' + e.message
  }
  setTimeout(() => { statusMessage.value = '' }, 2000)
  closeForwardPicker()
}

// 音量键快捷操作
const volumeKeyAccessibility = ref(false)
const volumeKeyEnabled = ref(true)

const checkVolumeKeyStatus = async () => {
  try {
    const { Capacitor, registerPlugin } = await import('@capacitor/core')
    if (!Capacitor.isNativePlatform()) return
    const VolumeKey = registerPlugin('VolumeKey')
    const res = await VolumeKey.isEnabled()
    volumeKeyAccessibility.value = res.accessibilityGranted
    volumeKeyEnabled.value = res.enabled
  } catch (e) {
    console.warn('[VolumeKey] 检查状态失败:', e)
  }
}

const openAccessibilitySettings = async () => {
  try {
    const { Capacitor, registerPlugin } = await import('@capacitor/core')
    if (!Capacitor.isNativePlatform()) return
    const VolumeKey = registerPlugin('VolumeKey')
    await VolumeKey.openAccessibilitySettings()
  } catch (e) {
    console.warn('[VolumeKey] 打开设置失败:', e)
  }
}

const toggleVolumeKey = async (enabled) => {
  try {
    const { Capacitor, registerPlugin } = await import('@capacitor/core')
    if (!Capacitor.isNativePlatform()) return
    const VolumeKey = registerPlugin('VolumeKey')
    await VolumeKey.setEnabled({ enabled })
    volumeKeyEnabled.value = enabled
  } catch (e) {
    console.warn('[VolumeKey] 切换失败:', e)
  }
}

// 壁纸
const selectedWallpaper = ref(localStorage.getItem('vcpMobileWallpaper') || '')
const isWallpaperPickerOpen = ref(false)

// 多 Agent 支持
const agents = ref([]) // 从服务端拉取的 Agent 列表
const activeAgentId = ref('') // 当前选中的 Agent ID
const isLoadingAgents = ref(false)

// 计算当前活跃 Agent 对象
const activeAgent = computed(() => {
  if (activeAgentId.value && agents.value.length > 0) {
    const found = agents.value.find(a => a.id === activeAgentId.value)
    if (found) return { ...found, status: '就绪' }
  }
  return { id: '', name: 'Nova', status: '未配置', systemPrompt: '', modelId: '', temperature: 0.7, maxOutputTokens: 40000 }
})

const messages = ref([])
const displayLimit = ref(20)
const displayMessages = computed(() => {
  if (messages.value.length <= displayLimit.value) return messages.value
  return messages.value.slice(messages.value.length - displayLimit.value)
})
const hasMoreMessages = computed(() => messages.value.length > displayLimit.value)
const loadMoreMessages = () => { displayLimit.value += 20 }

// ========== 壁纸（本地资源） ==========

const LOCAL_WALLPAPERS = [
  'dark.jpg', 'light.jpeg', 'forest_night.jpg', 'mountain.jpg', 'leaf.jpg',
  'sakuranight.png', 'wallpaper_ci.jpg', 'wallpaper_jin.jpg',
  'wallpaper-mountain-nightgold.jpg', 'themes_snow_realm_light.jpg',
  'themes_star_abyss_dark.jpg', 'watermelon_day.jpg',
  'win22coffee.png', 'wincoffee.png',
  'ComfyUI_010842_894361418827477_00027.png', 'ComfyUI_012952_1030647063343854_00033.png',
  '樱夜倒影.png', '绿影猫咪.png',
]

const localWpUrl = (name) => `/wallpapers/${encodeURIComponent(name)}`

const wallpaperBgStyle = computed(() => {
  if (!selectedWallpaper.value) return {}
  return { backgroundImage: `url('${localWpUrl(selectedWallpaper.value)}')`, backgroundSize: 'cover', backgroundPosition: 'center' }
})

const selectWallpaper = (name) => {
  selectedWallpaper.value = name
  localStorage.setItem('vcpMobileWallpaper', name)
  isWallpaperPickerOpen.value = false
}

const clearWallpaper = () => {
  selectedWallpaper.value = ''
  localStorage.removeItem('vcpMobileWallpaper')
  isWallpaperPickerOpen.value = false
}

// ========== Agent 加载与切换 ==========

const loadAgents = async () => {
  // 先从缓存加载
  const cached = loadCachedAgents()
  if (cached.length > 0) {
    agents.value = cached
  }
  // 恢复上次选中的 Agent
  const savedId = getActiveAgentId()
  if (savedId && agents.value.find(a => a.id === savedId)) {
    activeAgentId.value = savedId
  } else if (agents.value.length > 0) {
    activeAgentId.value = agents.value[0].id
  }
}

const refreshAgents = async () => {
  if (!config.value.baseUrl || !config.value.adminUsername) return
  isLoadingAgents.value = true
  try {
    const result = await fetchAgentList({
      baseUrl: config.value.baseUrl,
      adminUsername: config.value.adminUsername,
      adminPassword: config.value.adminPassword,
    })
    if (result.success && result.agents.length > 0) {
      agents.value = normalizeAgents(result.agents)
      saveCachedAgents(agents.value)

      // VCPChat 来源：用服务端话题列表同步本地（删除桌面端已删除的，保留本地新建的）
      if (result.source === 'vcpchat') {
        for (const agent of agents.value) {
          const topicsKey = `vcpTopics_${agent.id}`
          const serverTopics = (agent.topics || []).map(t => ({
            id: t.id,
            title: t.name || '未命名话题',
            timestamp: t.createdAt || Date.now(),
          }))
          const serverIds = new Set(serverTopics.map(t => t.id))
          const existingRaw = localStorage.getItem(topicsKey)
          const localTopics = existingRaw ? JSON.parse(existingRaw) : []
          // 保留本地独有的话题（手机端新建但尚未同步到桌面端的）
          const localOnly = localTopics.filter(t => !serverIds.has(t.id))
          const merged = [...serverTopics, ...localOnly]
          localStorage.setItem(topicsKey, JSON.stringify(merged))
          if (localTopics.length !== merged.length) {
            console.log(`[App] 同步 ${agent.name} 话题：服务端 ${serverTopics.length}，本地独有 ${localOnly.length}，合计 ${merged.length}`)
          }
        }
      }

      // 如果当前选中的 Agent 不在新列表中，切换到第一个
      if (!agents.value.find(a => a.id === activeAgentId.value)) {
        switchAgent(agents.value[0].id)
      } else {
        // 重新加载当前 Agent 的话题（可能刚被初始化）
        loadHistory()
      }
      console.log(`[App] 已刷新 ${agents.value.length} 个 Agent (来源: ${result.source})`)
      // 刷新后同步原生层配置（agentDirId 可能已更新）
      syncScreenshotConfig()
    }
  } catch (e) {
    console.warn('[App] 刷新 Agent 列表失败:', e.message)
  } finally {
    isLoadingAgents.value = false
  }
}

const switchAgent = (agentId) => {
  if (agentId === activeAgentId.value) return
  if (isStreaming.value) interruptStream()
  cleanupAllBubbleStyles()

  activeAgentId.value = agentId
  saveActiveAgentId(agentId)

  // 同步原生层配置（更新 agentDirId）
  syncScreenshotConfig()

  // 加载该 Agent 的话题列表
  loadHistory()
}

// ========== 话题管理（按 Agent 分组） ==========

const getTopicsKey = () => `vcpTopics_${activeAgentId.value || 'default'}`
const getMessagesKey = (topicId) => `vcpMessages_${activeAgentId.value || 'default'}_${topicId}`

const loadHistory = () => {
  const savedTopics = localStorage.getItem(getTopicsKey())
  if (savedTopics) {
    topics.value = JSON.parse(savedTopics)
  } else {
    topics.value = []
  }

  if (topics.value.length === 0) {
    createNewTopic()
  } else {
    const lastTopicId = localStorage.getItem(`vcpLastTopic_${activeAgentId.value}`)
    if (lastTopicId && topics.value.find(t => t.id === lastTopicId)) {
      switchTopic(lastTopicId)
    } else {
      switchTopic(topics.value[0].id)
    }
  }
}

const saveHistory = () => {
  localStorage.setItem(getTopicsKey(), JSON.stringify(topics.value))
  if (currentTopicId.value) {
    const agent = activeAgent.value
    if (agent.agentDirId) {
      // VCPChat Agent：存 IndexedDB（避免 localStorage 超限）
      // JSON 深拷贝去掉 Vue reactive proxy，否则 IndexedDB 无法序列化
      const plain = JSON.parse(JSON.stringify(messages.value.map(({ fromHistory, ...rest }) => rest)))
      setCachedMessages(agent.agentDirId, currentTopicId.value, plain, Date.now())
    } else {
      // 非 VCPChat Agent：存 localStorage
      localStorage.setItem(getMessagesKey(currentTopicId.value), JSON.stringify(messages.value))
    }
    localStorage.setItem(`vcpLastTopic_${activeAgentId.value}`, currentTopicId.value)
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

const switchTopic = async (topicId) => {
  if (isStreaming.value) interruptStream()
  cleanupAllBubbleStyles()
  displayLimit.value = 20
  
  currentTopicId.value = topicId
  const agent = activeAgent.value

  // VCPChat 来源的 Agent：先显示 IndexedDB 缓存，后台比对更新
  if (agent.agentDirId && config.value.baseUrl && config.value.adminUsername) {
    // 1. 先从 IndexedDB 加载缓存（秒开）
    let cachedLastModified = 0
    try {
      const cached = await getCachedMessages(agent.agentDirId, topicId)
      if (cached && cached.messages && cached.messages.length > 0) {
        messages.value = cached.messages.map(m => m.isLocal ? m : { ...m, fromHistory: true })
        cachedLastModified = cached.lastModified || 0
        console.log(`[App] 从缓存加载了 ${cached.messages.length} 条消息`)
      } else {
        messages.value = []
        statusMessage.value = '正在加载聊天记录...'
      }
    } catch (e) {
      messages.value = []
      statusMessage.value = '正在加载聊天记录...'
    }

    // 2. 后台向服务端比对，有变化才更新
    try {
      const syncConfig = { baseUrl: config.value.baseUrl, adminUsername: config.value.adminUsername, adminPassword: config.value.adminPassword }
      const result = await fetchTopicHistory(syncConfig, agent.agentDirId, topicId, cachedLastModified)
      if (result.success) {
        if (result.notModified) {
          console.log(`[App] 话题 ${topicId} 未变化，使用缓存`)
        } else if (result.messages && result.messages.length > 0) {
          // 合并：isLocal 消息优先保留本地版本（有完整渲染），服务端版本可能被 simplifyContent 剥离了 HTML
          const localMap = new Map()
          messages.value.forEach(m => { if (m.isLocal) localMap.set(m.id, m) })
          const serverIds = new Set(result.messages.map(m => m.id))
          const localOnly = messages.value.filter(m => m.isLocal && !serverIds.has(m.id))
          const merged = [
            ...result.messages.map(m => localMap.has(m.id) ? localMap.get(m.id) : { ...m, fromHistory: true }),
            ...localOnly,
          ]
          messages.value = merged
          // 缓存合并后的完整消息
          const toCache = merged.map(({ fromHistory, ...rest }) => rest)
          setCachedMessages(agent.agentDirId, topicId, JSON.parse(JSON.stringify(toCache)), result.lastModified)
          console.log(`[App] 从服务端更新了 ${result.messages.length} 条消息，保留 ${localMap.size + localOnly.length} 条本地消息`)
        } else {
          // 服务端无消息，但保留本地独有的
          const localOnly = messages.value.filter(m => m.isLocal)
          messages.value = localOnly
        }
      }
      statusMessage.value = ''
    } catch (e) {
      console.warn('[App] 后台同步失败:', e.message)
      statusMessage.value = ''
    }
  } else {
    // 非 VCPChat Agent：从 localStorage 加载
    const savedMessages = localStorage.getItem(getMessagesKey(topicId))
    messages.value = savedMessages ? JSON.parse(savedMessages) : []
  }
  isSidebarOpen.value = false
}

const deleteTopic = (topicId) => {
  topics.value = topics.value.filter(t => t.id !== topicId)
  localStorage.removeItem(getMessagesKey(topicId))
  if (currentTopicId.value === topicId) {
    if (topics.value.length > 0) {
      switchTopic(topics.value[0].id)
    } else {
      createNewTopic()
    }
  }
  saveHistory()
  // VCPChat Agent：同步删除桌面端话题
  const agent = activeAgent.value
  if (agent.agentDirId && config.value.baseUrl && config.value.adminUsername) {
    const syncConfig = { baseUrl: config.value.baseUrl, adminUsername: config.value.adminUsername, adminPassword: config.value.adminPassword }
    deleteTopicFromDesktop(syncConfig, agent.agentDirId, topicId)
      .then(r => { if (r.success) console.log(`[App] 已同步删除桌面端话题 ${topicId}`) })
      .catch(e => console.warn('[App] 同步删除桌面端话题失败:', e.message))
  }
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

// 去掉 HTML 行首缩进，避免 marked 把缩进的 HTML 当代码块
const deIndentHtml = (text) => {
  if (!text || !/<[a-z][\s\S]*>/i.test(text)) return text
  return text.replace(/^[ \t]+(<!?[a-z/])/gim, '$1')
}

const renderContent = (message) => {
  const isVCPChat = !!activeAgent.value.agentDirId
  // 非 VCPChat 的本地 Agent 历史消息：纯文本渲染（避免大量 HTML 卡顿）
  if (!isVCPChat && message.fromHistory && !message.isStreaming) {
    const text = message.content || ''
    const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    return escaped.replace(/\n/g, '<br>')
  }
  // VCPChat Agent 的 assistant 消息：去掉 HTML 缩进避免 marked 当代码块
  let content = message.content
  if (isVCPChat && message.role === 'assistant') {
    content = deIndentHtml(content)
  }
  return renderMessageHtml(content, {
    messageId: message.id,
    role: message.role,
    allowBubbleCss: !isVCPChat && config.value.enableAgentBubbleTheme,
    baseUrl: normalizeBaseUrl(config.value.baseUrl),
    imageKey: config.value.imageKey,
    isStreaming: message.isStreaming,
  })
}

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

const syncScreenshotConfig = async () => {
  try {
    const { Capacitor } = await import('@capacitor/core')
    if (Capacitor.isNativePlatform()) {
      const { registerPlugin } = await import('@capacitor/core')
      const ScreenshotSender = registerPlugin('ScreenshotSender')
      await ScreenshotSender.configure({
        baseUrl: config.value.baseUrl,
        apiKey: config.value.apiKey,
        model: config.value.model,
        presetMessage: config.value.screenshotPresetMessage || '识别截图内容并记录日记',
        clipPresetMessage: config.value.clipPresetMessage || '分析以下内容',
        systemPrompt: config.value.systemPrompt || '',
        adminUsername: config.value.adminUsername || '',
        adminPassword: config.value.adminPassword || '',
        agentDirId: activeAgent.value?.agentDirId || '',
      })
      console.log('[ScreenshotSender] 配置已同步到原生层')
    }
  } catch (e) {
    console.warn('[ScreenshotSender] 同步配置失败:', e)
  }
}

const saveConfig = async () => {
  localStorage.setItem('vcpMobileConfig', JSON.stringify(config.value))
  document.body.classList.toggle('agent-bubble-theme', !!config.value.enableAgentBubbleTheme)
  await refreshModels()
  refreshAgents() // 刷新 Agent 列表
  isSettingsOpen.value = false
  statusMessage.value = '设置已保存'
  setTimeout(() => { if (statusMessage.value === '设置已保存') statusMessage.value = '' }, 2000)
  // 重连 WebSocket 推送
  initPushConnection()
  // 同步截图发送配置到原生层
  syncScreenshotConfig()
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
  // 优先使用当前 Agent 的 systemPrompt，其次使用全局设置
  const agentPrompt = activeAgent.value.systemPrompt || config.value.systemPrompt
  if (agentPrompt) systemParts.push(agentPrompt)
  if (config.value.enableAgentBubbleTheme) systemParts.push(agentBubbleThemeSpec)
  const systemContent = systemParts.join('\n\n').trim()
  if (systemContent) payload.push({ role: 'system', content: systemContent })

  const history = items
    .filter((message) => ['user', 'assistant'].includes(message.role))
    .map((message) => {
      if (message.role === 'user' && message.attachments && message.attachments.length > 0) {
        const contentParts = [{ type: 'text', text: message.content || '' }]
        message.attachments.forEach((att) => {
          const mimeType = (att.mimeType || '').toLowerCase()
          if (att.kind === 'audio' || att.kind === 'image' || att.kind === 'video' ||
              mimeType.startsWith('audio/') || mimeType.startsWith('image/') || mimeType.startsWith('video/')) {
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

const toggleAttachMenu = () => {
  isAttachMenuOpen.value = !isAttachMenuOpen.value
}

const triggerCamera = () => {
  isAttachMenuOpen.value = false
  cameraInput.value?.click()
}

const triggerVideo = () => {
  isAttachMenuOpen.value = false
  videoInput.value?.click()
}

const triggerFilePicker = () => {
  isAttachMenuOpen.value = false
  fileInput.value?.click()
}

// 视频压缩：通过 Canvas + MediaRecorder 重编码降低码率
const compressVideo = (file, targetBitrate = 800000) => {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video')
    video.muted = true
    video.playsInline = true
    video.src = URL.createObjectURL(file)

    video.onloadedmetadata = () => {
      // 限制分辨率：最大 720p
      let w = video.videoWidth
      let h = video.videoHeight
      const maxDim = 720
      if (Math.max(w, h) > maxDim) {
        const scale = maxDim / Math.max(w, h)
        w = Math.round(w * scale)
        h = Math.round(h * scale)
      }
      // 确保宽高为偶数
      w = w % 2 === 0 ? w : w + 1
      h = h % 2 === 0 ? h : h + 1

      const canvas = document.createElement('canvas')
      canvas.width = w
      canvas.height = h
      const ctx = canvas.getContext('2d')

      const stream = canvas.captureStream(24)
      // 尝试添加音轨
      try {
        if (video.captureStream) {
          const videoStream = video.captureStream()
          const audioTracks = videoStream.getAudioTracks()
          audioTracks.forEach(t => stream.addTrack(t))
        }
      } catch (e) { /* 部分浏览器不支持 captureStream */ }

      const mimeType = MediaRecorder.isTypeSupported('video/webm;codecs=vp9')
        ? 'video/webm;codecs=vp9'
        : MediaRecorder.isTypeSupported('video/webm;codecs=vp8')
          ? 'video/webm;codecs=vp8'
          : 'video/webm'

      const recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: targetBitrate })
      const chunks = []
      recorder.ondataavailable = (e) => { if (e.data.size > 0) chunks.push(e.data) }
      recorder.onstop = () => {
        URL.revokeObjectURL(video.src)
        const blob = new Blob(chunks, { type: mimeType })
        resolve(blob)
      }
      recorder.onerror = (e) => {
        URL.revokeObjectURL(video.src)
        reject(e)
      }

      recorder.start()
      video.play()

      const drawFrame = () => {
        if (video.ended || video.paused) {
          recorder.stop()
          return
        }
        ctx.drawImage(video, 0, 0, w, h)
        requestAnimationFrame(drawFrame)
      }
      requestAnimationFrame(drawFrame)

      video.onended = () => {
        setTimeout(() => recorder.stop(), 100)
      }
    }

    video.onerror = () => {
      URL.revokeObjectURL(video.src)
      reject(new Error('无法加载视频'))
    }
  })
}

// 图片压缩：通过 Canvas 缩放 + JPEG 压缩，避免大图 base64 导致 OOM
const compressImage = (file, maxDim = 1920, quality = 0.8) => {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      let w = img.width
      let h = img.height
      if (Math.max(w, h) > maxDim) {
        const scale = maxDim / Math.max(w, h)
        w = Math.round(w * scale)
        h = Math.round(h * scale)
      }
      const canvas = document.createElement('canvas')
      canvas.width = w
      canvas.height = h
      const ctx = canvas.getContext('2d')
      ctx.drawImage(img, 0, 0, w, h)
      URL.revokeObjectURL(img.src)
      canvas.toBlob(
        (blob) => {
          if (blob) resolve(blob)
          else reject(new Error('Canvas toBlob 失败'))
        },
        'image/jpeg',
        quality
      )
    }
    img.onerror = () => {
      URL.revokeObjectURL(img.src)
      reject(new Error('无法加载图片'))
    }
    img.src = URL.createObjectURL(file)
  })
}

const addFileAsAttachment = (file, dataUrl) => {
  let kind = 'file'
  if (file.type.startsWith('image/')) kind = 'image'
  else if (file.type.startsWith('video/')) kind = 'video'
  else if (file.type.startsWith('audio/')) kind = 'audio'

  pendingAttachments.value.push({
    id: `att_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
    name: file.name,
    mimeType: file.type,
    url: dataUrl,
    kind
  })
}

const handleFileChange = async (event) => {
  const files = event.target.files
  if (!files.length) return

  for (const file of files) {
    // 视频文件 > 5MB 自动压缩
    if (file.type.startsWith('video/') && file.size > 5 * 1024 * 1024) {
      try {
        isCompressing.value = true
        statusMessage.value = `正在压缩视频 (${(file.size / 1024 / 1024).toFixed(1)}MB)...`
        const compressed = await compressVideo(file)
        statusMessage.value = `压缩完成: ${(file.size / 1024 / 1024).toFixed(1)}MB → ${(compressed.size / 1024 / 1024).toFixed(1)}MB`
        const reader = new FileReader()
        reader.onload = (e) => {
          const ext = compressed.type.includes('webm') ? 'webm' : 'mp4'
          addFileAsAttachment(
            { name: file.name.replace(/\.[^.]+$/, `.${ext}`), type: compressed.type, size: compressed.size },
            e.target.result
          )
        }
        reader.readAsDataURL(compressed)
      } catch (err) {
        console.error('视频压缩失败，使用原始文件:', err)
        statusMessage.value = '视频压缩失败，使用原始文件'
        const reader = new FileReader()
        reader.onload = (e) => addFileAsAttachment(file, e.target.result)
        reader.readAsDataURL(file)
      } finally {
        isCompressing.value = false
        setTimeout(() => { statusMessage.value = '' }, 3000)
      }
    } else if (file.type.startsWith('image/')) {
      // 图片压缩后再转 base64，防止大图 OOM 闪退
      try {
        const compressed = await compressImage(file)
        const reader = new FileReader()
        reader.onload = (e) => {
          addFileAsAttachment(
            { name: file.name.replace(/\.[^.]+$/, '.jpg'), type: 'image/jpeg', size: compressed.size },
            e.target.result
          )
        }
        reader.readAsDataURL(compressed)
      } catch (err) {
        console.error('图片压缩失败，使用原始文件:', err)
        const reader = new FileReader()
        reader.onload = (e) => addFileAsAttachment(file, e.target.result)
        reader.readAsDataURL(file)
      }
    } else {
      const reader = new FileReader()
      reader.onload = (e) => addFileAsAttachment(file, e.target.result)
      reader.readAsDataURL(file)
    }
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
    isLocal: true,
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
    isLocal: true,
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

  // 优先使用 Agent 的模型和参数，其次使用全局设置
  const chatModel = activeAgent.value.modelId || config.value.model
  const chatTemperature = activeAgent.value.temperature ?? Number(config.value.temperature)
  const chatMaxTokens = activeAgent.value.maxOutputTokens || Number(config.value.maxTokens)

  streamChat({
    baseUrl,
    apiKey: config.value.apiKey,
    messages: payloadMessages,
    model: chatModel,
    temperature: chatTemperature,
    maxTokens: chatMaxTokens,
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
      // VCPChat Agent：将新消息写回桌面端 history.json（双向同步）
      const agent = activeAgent.value
      if (agent.agentDirId && config.value.baseUrl && config.value.adminUsername) {
        const syncConfig = { baseUrl: config.value.baseUrl, adminUsername: config.value.adminUsername, adminPassword: config.value.adminPassword }
        const newMsgs = [userMessage, { id: assistantMessage.id, role: assistantMessage.role, name: assistantMessage.name, content: assistantMessage.content, timestamp: assistantMessage.timestamp }]
        const currentTopic = topics.value.find(t => t.id === currentTopicId.value)
        appendToHistory(syncConfig, agent.agentDirId, currentTopicId.value, newMsgs, currentTopic?.title)
          .then(r => { if (r.success) console.log(`[App] 已同步 ${r.appended} 条消息到桌面端`) })
          .catch(e => console.warn('[App] 同步到桌面端失败:', e.message))
      } else {
        backgroundSync(currentTopicId.value, messages.value)
      }
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

// 同步使用当前 Agent 的真实 ID
const getSyncAgentId = () => activeAgentId.value || 'mobile-default'

const backgroundSync = async (topicId, localMessages) => {
  if (!config.value.syncEnabled || !config.value.baseUrl || !config.value.adminUsername) return
  try {
    const result = await syncTopic(getSyncConfig(), getSyncAgentId(), topicId, localMessages)
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
  if (!config.value.baseUrl || !config.value.adminUsername) {
    syncStatus.value = '请先在设置中配置服务器和管理员账号'
    return
  }
  isSyncing.value = true
  syncStatus.value = '正在同步...'

  try {
    const agent = activeAgent.value

    // ====== VCPChat 来源：直接从 VCPChat AppData 拉取聊天记录 ======
    if (agent.agentDirId) {
      const syncConfig = { baseUrl: config.value.baseUrl, adminUsername: config.value.adminUsername, adminPassword: config.value.adminPassword }

      // 1. 先用服务端的话题列表覆盖本地（确保话题 ID 与桌面端一致）
      if (agent.topics && agent.topics.length > 0) {
        const serverTopics = agent.topics.map(t => ({
          id: t.id,
          title: t.name || '未命名话题',
          timestamp: t.createdAt || Date.now(),
        }))
        topics.value = serverTopics
        localStorage.setItem(getTopicsKey(), JSON.stringify(serverTopics))
        console.log(`[App] 已同步 ${serverTopics.length} 个话题列表`)
      }

      // 2. 加载当前话题的聊天记录（VCPChat 不存 localStorage，实时拉取）
      const targetTopicId = currentTopicId.value && topics.value.find(t => t.id === currentTopicId.value)
        ? currentTopicId.value
        : (topics.value.length > 0 ? topics.value[0].id : null)

      if (targetTopicId) {
        currentTopicId.value = targetTopicId
        syncStatus.value = '正在加载聊天记录...'
        try {
          const result = await fetchTopicHistory(syncConfig, agent.agentDirId, targetTopicId)
          if (result.success && result.messages.length > 0) {
            messages.value = result.messages
            console.log(`[App] 加载了 ${result.messages.length} 条消息`)
          } else {
            messages.value = []
          }
        } catch (e) {
          console.warn('[App] 加载聊天记录失败:', e.message)
        }
      }

      syncStatus.value = `同步完成: ${topics.value.length} 个话题已同步`
    }
    // ====== Fallback: ChatSync 插件同步 ======
    else if (config.value.syncEnabled) {
      const status = await checkSyncStatus(getSyncConfig())
      if (!status.available) {
        syncStatus.value = `同步服务不可用: ${status.error}`
        isSyncing.value = false
        return
      }

      const agentId = getSyncAgentId()
      const getMessages = (topicId) => {
        const saved = localStorage.getItem(getMessagesKey(topicId))
        return saved ? JSON.parse(saved) : []
      }
      const setMessages = (topicId, msgs) => {
        localStorage.setItem(getMessagesKey(topicId), JSON.stringify(msgs))
        if (topicId === currentTopicId.value) {
          messages.value = msgs
        }
      }

      const result = await fullSync(
        getSyncConfig(),
        agentId,
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
    } else {
      syncStatus.value = '当前 Agent 不支持同步'
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

const closeAttachMenuOnOutsideClick = (e) => {
  if (isAttachMenuOpen.value && !e.target.closest('.attach-menu-wrapper')) {
    isAttachMenuOpen.value = false
  }
}

onMounted(async () => {
  document.body.classList.toggle('light-theme', isLightTheme.value)
  document.addEventListener('click', closeAttachMenuOnOutsideClick)
  loadConfig()
  checkVolumeKeyStatus() // 检查音量键快捷操作状态
  await loadAgents() // 先从缓存加载 Agent 列表
  syncScreenshotConfig() // 同步截图发送配置到原生层（需在 loadAgents 之后，确保 agentDirId 已加载）
  loadHistory()
  if (config.value.baseUrl) {
    refreshModels()
    refreshAgents() // 异步从服务端刷新 Agent 列表
    initPushConnection()
    if (config.value.syncEnabled && config.value.adminUsername) {
      setTimeout(() => backgroundSync(currentTopicId.value, messages.value), 2000)
    }
  }
})

onUnmounted(() => {
  document.removeEventListener('click', closeAttachMenuOnOutsideClick)
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
        <button v-if="!activeAgent.agentDirId" class="icon-button" type="button" @click="initPushConnection">
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

    <main class="chat-body" :style="wallpaperBgStyle">
      <div v-if="isStreaming" class="stream-banner">
        <span>模型正在响应...</span>
        <button class="icon-button" type="button" @click="interruptStream">
          停止
        </button>
      </div>
      <div v-if="statusMessage" class="status-banner">{{ statusMessage }}</div>
      <div class="chat-messages-container" @click="handleBubbleToggle">
        <div class="chat-messages">
          <div v-if="hasMoreMessages" class="load-more-bar" @click="loadMoreMessages">
            还有 {{ messages.length - displayLimit }} 条更早的消息，点击加载
          </div>
          <div
            v-for="message in displayMessages"
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
              <div class="md-content"
                @touchstart.passive="onMsgTouchStart($event, message)"
                @touchend="onMsgTouchEnd"
                @touchmove="onMsgTouchEnd"
              >
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

    <!-- 消息操作 ActionSheet -->
    <Teleport to="body">
      <div v-if="actionSheetVisible" class="action-sheet-overlay" @click="closeActionSheet">
        <div class="action-sheet" @click.stop>
          <div class="action-sheet-item" @click="doCopyMessage">复制文本</div>
          <div class="action-sheet-item" @click="openEditMode">编辑消息</div>
          <div class="action-sheet-item" @click="openReadMode">阅读模式</div>
          <div class="action-sheet-item forward-item" @click="openForwardPicker" v-if="agents.length > 0 && config.adminUsername">转发消息</div>
          <div class="action-sheet-item delete-item" @click="doDeleteMessage">删除消息</div>
          <div class="action-sheet-cancel" @click="closeActionSheet">取消</div>
        </div>
      </div>
    </Teleport>

    <!-- 阅读模式 -->
    <Teleport to="body">
      <div v-if="readModeVisible && readModeMessage" class="read-mode-overlay" @click="closeReadMode">
        <div class="read-mode-panel" @click.stop>
          <div class="read-mode-header">
            <span class="read-mode-role">{{ readModeMessage.role === 'user' ? '我' : (readModeMessage.name || 'AI') }}</span>
            <button class="read-mode-close" @click="closeReadMode">✕</button>
          </div>
          <div class="read-mode-body" v-html="renderContent(readModeMessage)"></div>
        </div>
      </div>
    </Teleport>

    <!-- 编辑消息 -->
    <Teleport to="body">
      <div v-if="editModeVisible" class="edit-mode-overlay" @click="cancelEditMode">
        <div class="edit-mode-panel" @click.stop>
          <div class="edit-mode-header">
            <span>编辑消息</span>
            <button class="read-mode-close" @click="cancelEditMode">✕</button>
          </div>
          <textarea class="edit-mode-textarea" v-model="editModeText"></textarea>
          <div class="edit-mode-actions">
            <button class="edit-cancel-btn" @click="cancelEditMode">取消</button>
            <button class="edit-save-btn" @click="saveEditMessage">保存</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 转发消息：选择 Agent -->
    <Teleport to="body">
      <div v-if="forwardPickerVisible" class="forward-picker-overlay" @click="closeForwardPicker">
        <div class="forward-picker-panel" @click.stop>
          <div class="edit-mode-header">
            <span>转发到</span>
            <button class="read-mode-close" @click="closeForwardPicker">✕</button>
          </div>
          <div class="forward-agent-list">
            <div
              v-for="ag in agents.filter(a => a.agentDirId)"
              :key="ag.id"
              class="forward-agent-item"
              @click="doForwardMessage(ag, null)"
            >
              <span class="forward-agent-name">{{ ag.name }}</span>
              <span class="forward-agent-hint">新话题</span>
            </div>
            <div v-if="agents.filter(a => a.agentDirId).length === 0" class="forward-empty">
              没有可转发的 Agent
            </div>
          </div>
        </div>
      </div>
    </Teleport>

    <footer class="input-bar">
      <!-- 附件预览行 -->
      <div v-if="pendingAttachments.length > 0" class="pending-attachments-row">
        <div v-for="att in pendingAttachments" :key="att.id" class="pending-attachment-chip">
          <span class="chip-text">{{ att.name }}</span>
          <button class="remove-chip" @click="removeAttachment(att.id)">×</button>
        </div>
      </div>

      <!-- 压缩状态提示 -->
      <div v-if="isCompressing" class="compress-status-bar">
        <span class="compress-spinner"></span>
        <span>{{ statusMessage }}</span>
      </div>

      <div class="input-controls">
        <div class="attach-menu-wrapper">
          <button class="icon-button" type="button" @click="toggleAttachMenu">+</button>
          <div v-if="isAttachMenuOpen" class="attach-menu">
            <button class="attach-menu-item" @click="triggerCamera">
              <span class="attach-menu-icon">📷</span>
              <span>拍照</span>
            </button>
            <button class="attach-menu-item" @click="triggerVideo">
              <span class="attach-menu-icon">🎬</span>
              <span>拍视频</span>
            </button>
            <button class="attach-menu-item" @click="triggerFilePicker">
              <span class="attach-menu-icon">📁</span>
              <span>选文件</span>
            </button>
          </div>
        </div>
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
      <input ref="fileInput" type="file" multiple style="display: none" @change="handleFileChange" />
      <!-- 拍照输入 -->
      <input ref="cameraInput" type="file" accept="image/*" capture="environment" style="display: none" @change="handleFileChange" />
      <!-- 拍视频输入 -->
      <input ref="videoInput" type="file" accept="video/*" capture="environment" style="display: none" @change="handleFileChange" />
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
          <label v-if="!activeAgent.agentDirId" class="settings-toggle">
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
          <label v-if="!activeAgent.agentDirId">
            <span>模型</span>
            <select v-model="config.model">
              <option value="">选择模型</option>
              <option v-for="model in models" :key="model" :value="model">
                {{ model }}
              </option>
            </select>
          </label>
          <label v-if="!activeAgent.agentDirId">
            <span>温度 (Temperature)</span>
            <input v-model.number="config.temperature" type="number" min="0" max="2" step="0.1" />
          </label>
          <label v-if="!activeAgent.agentDirId">
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
          <div class="settings-divider">音量键快捷操作</div>
          <p class="settings-hint">双击音量上键 → 截图发送给 AI；长按音量上键 → 剪贴板发送给 AI。需开启辅助功能权限。</p>
          <div class="volume-key-status">
            <span>辅助功能权限</span>
            <span v-if="volumeKeyAccessibility" class="vk-badge vk-on">已开启</span>
            <button v-else class="vk-badge vk-off" @click="openAccessibilitySettings">去开启</button>
          </div>
          <label v-if="volumeKeyAccessibility" class="settings-toggle">
            <span>启用音量键监听</span>
            <input type="checkbox" :checked="volumeKeyEnabled" @change="toggleVolumeKey($event.target.checked)" />
          </label>
          <label>
            <span>截图预设消息</span>
            <input v-model="config.screenshotPresetMessage" placeholder="识别截图内容并记录日记" />
          </label>
          <label>
            <span>剪贴板预设消息</span>
            <input v-model="config.clipPresetMessage" placeholder="分析以下内容" />
          </label>
          <div class="settings-divider">外观</div>
          <div class="settings-wallpaper-row">
            <span>聊天壁纸</span>
            <button class="wallpaper-pick-btn" @click="isWallpaperPickerOpen = true">
              {{ selectedWallpaper ? '更换壁纸' : '选择壁纸' }}
            </button>
            <button v-if="selectedWallpaper" class="wallpaper-clear-btn" @click="clearWallpaper">清除</button>
          </div>
          <div class="settings-divider">其他</div>
          <label v-if="!activeAgent.agentDirId">
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
          <button v-if="!activeAgent.agentDirId" class="icon-button" type="button" @click="refreshModels">
            刷新模型
          </button>
          <button class="send-button" type="button" @click="saveConfig">
            保存
          </button>
        </div>
      </div>
    </div>

    <div v-if="isWallpaperPickerOpen" class="settings-panel" @click.self="isWallpaperPickerOpen = false">
      <div class="settings-card wallpaper-picker-card">
        <div class="settings-header">
          <h3>选择壁纸</h3>
          <button class="icon-button" type="button" @click="isWallpaperPickerOpen = false">关闭</button>
        </div>
        <div class="wallpaper-grid">
          <div class="wallpaper-item wallpaper-none" :class="{ active: !selectedWallpaper }" @click="clearWallpaper">
            <span>无壁纸</span>
          </div>
          <div
            v-for="name in LOCAL_WALLPAPERS"
            :key="name"
            class="wallpaper-item"
            :class="{ active: selectedWallpaper === name }"
            @click="selectWallpaper(name)"
          >
            <img :src="localWpUrl(name)" :alt="name" loading="lazy" />
            <span class="wallpaper-label">{{ name.replace(/\.[^.]+$/, '') }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="isSidebarOpen" class="sidebar-overlay" @click.self="isSidebarOpen = false">
      <div class="sidebar">
        <div class="sidebar-header">
          <h3>智能体 & 话题</h3>
          <button class="icon-button" @click="isSidebarOpen = false">关闭</button>
        </div>
        <div class="sidebar-content">
          <!-- Agent 列表 -->
          <div class="sidebar-section-title">
            智能体
            <button v-if="!isLoadingAgents" class="refresh-agents-btn" @click="refreshAgents" title="刷新列表">🔄</button>
            <span v-else class="loading-indicator">...</span>
          </div>
          <div class="agent-list">
            <div 
              v-for="agent in agents" 
              :key="agent.id" 
              class="agent-item"
              :class="{ active: activeAgentId === agent.id }"
              @click="switchAgent(agent.id)"
            >
              <div class="agent-avatar">{{ agent.name.slice(0, 1) }}</div>
              <div class="agent-info">
                <div class="agent-name">{{ agent.name }}</div>
                <div class="agent-meta">{{ agent.description || (agent.modelId ? agent.modelId.split('/').pop() : '') }}</div>
              </div>
            </div>
            <div v-if="agents.length === 0" class="agent-empty">
              暂无智能体，请在设置中配置服务器
            </div>
          </div>

          <!-- 当前 Agent 的话题列表 -->
          <div class="sidebar-section-title">{{ activeAgent.name }} 的话题</div>
          <button class="new-topic-btn" @click="createNewTopic">
            + 开启新话题
          </button>
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
            v-if="!activeAgent.agentDirId"
            class="new-topic-btn sync-btn" 
            :disabled="isSyncing" 
            @click="manualSync"
          >
            {{ isSyncing ? '同步中...' : '🔄 同步聊天记录' }}
          </button>
          <div v-if="syncStatus && !activeAgent.agentDirId" class="sync-status">{{ syncStatus }}</div>

          <div class="sidebar-footer-info">
            VCP Mobile v1.1.0
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
