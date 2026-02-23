# VCPMobile 适配指南：VCPToolBox / VCPChat 新增与改动文件

**版本**: v1.3.0  
**更新时间**: 2026-02-23

---

## 一、概述

VCPMobile 是 VCPToolBox + VCPChat 生态的移动端客户端。为了支持手机端的聊天、同步、推送等功能，需要在 VCPToolBox 服务端新增若干 API 路由和插件。

本文档详细列出：
1. **VCPToolBox 中为适配 VCPMobile 而新增/修改的文件**（PR 清单）
2. **每个 API 的调用方式、参数、返回值**（API 文档）
3. **调用示例和预期结果**（测试报告）

---

## 二、PR 清单：VCPToolBox 新增/修改的文件

### 2.1 新增文件

| 文件路径 | 用途 | 行数 |
|---------|------|------|
| `routes/vcpchatMobileRoutes.js` | VCPChat Mobile 同步路由（Agent 列表 + 聊天记录读写 + 壁纸） | ~384 |
| `Plugin/ChatSync/ChatSync.js` | 聊天记录跨设备同步插件（REST API，消息级增量同步） | ~384 |
| `Plugin/VCPChatDesktopSync/VCPChatDesktopSync.js` | VCPChat 桌面端双向同步插件（远程 ↔ 本地） | ~397 |

### 2.2 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `routes/adminPanelRoutes.js` (第 847 行) | 引入 `vcpchatMobileRoutes` 模块，挂载到 `adminApiRouter` |
| `WebSocketServer.js` (第 70、78、108-110 行) | 新增 `MobileClient` WebSocket 通道 (`/vcp-mobile/VCP_Key=xxx`) |
| `server.js` | 注册 ChatSync 插件路由 |

### 2.3 文件依赖关系

```
server.js
├── routes/adminPanelRoutes.js
│   └── routes/vcpchatMobileRoutes.js    ← 新增（Agent列表 + 聊天记录 API）
├── WebSocketServer.js                   ← 修改（新增 MobileClient 通道）
└── Plugin/
    ├── ChatSync/ChatSync.js             ← 新增（消息同步 REST API）
    └── VCPChatDesktopSync/              ← 新增（桌面端双向同步）
```

---

## 三、API 文档

### 3.1 Agent 列表 API

#### `GET /admin_api/agents/mobile-list`

**用途**：获取所有可用的 Agent 列表（优先从 VCPChat AppData 读取，回退到 agent_map.json）

**认证**：Basic Auth（Admin Panel 用户名/密码）

**请求头**：
```http
Authorization: Basic base64(username:password)
Content-Type: application/json
```

**响应**：
```json
{
  "success": true,
  "source": "vcpchat",  // "vcpchat" 或 "agent_map"
  "agents": [
    {
      "name": "Nova",
      "agentDirId": "agent_1234567890",
      "systemPrompt": "你是 Nova...",
      "modelId": "gemini-2.5-pro",
      "temperature": 0.7,
      "maxOutputTokens": 60000,
      "contextTokenLimit": 1000000,
      "description": "",
      "topics": [
        {
          "id": "topic_1770624921406",
          "name": "日常聊天",
          "createdAt": 1770624921406
        }
      ]
    }
  ]
}
```

**调用函数** (vcp-mobile `agentService.js`)：
```javascript
import { fetchAgentList } from './services/agentService'

const result = await fetchAgentList({
  baseUrl: 'http://192.168.1.100:5005',
  adminUsername: 'admin',
  adminPassword: 'password'
})

// result.success → true
// result.agents → Agent 数组
// result.source → "vcpchat" 或 "agent_map"
```

**预期输出**：
```
[INFO][Agent] GET http://192.168.1.100:5005/admin_api/agents/mobile-list
[INFO][Agent] 拉取到 3 个 Agent (来源: vcpchat)
```

---

### 3.2 聊天记录读取 API

#### `GET /admin_api/agents/vcpchat-history`

**用途**：从 VCPChat 桌面端读取指定话题的聊天记录

**参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agentDirId` | string | ✅ | VCPChat Agent 目录名（如 `agent_1234567890`） |
| `topicId` | string | ✅ | 话题 ID（如 `topic_1770624921406`） |
| `raw` | string | 可选 | 传 `1` 返回原始 HTML，否则返回精简文本 |
| `ifModifiedSince` | number | 可选 | 上次缓存的 lastModified 时间戳，未变化时返回 notModified |

**响应（正常）**：
```json
{
  "success": true,
  "messages": [
    {
      "id": "msg_1770624921406",
      "role": "user",
      "name": "",
      "content": "你好",
      "timestamp": 1770624921406,
      "attachments": []
    },
    {
      "id": "msg_1770624930000",
      "role": "assistant",
      "name": "Nova",
      "content": "你好！有什么可以帮你的吗？",
      "timestamp": 1770624930000,
      "attachments": []
    }
  ],
  "lastModified": 1770624930000
}
```

**响应（未变化）**：
```json
{
  "success": true,
  "notModified": true,
  "lastModified": 1770624930000
}
```

**调用函数** (vcp-mobile `agentService.js`)：
```javascript
import { fetchTopicHistory } from './services/agentService'

const result = await fetchTopicHistory(
  { baseUrl, adminUsername, adminPassword },
  'agent_1234567890',  // agentDirId
  'topic_1770624921406', // topicId
  lastModifiedTimestamp   // 可选，用于增量拉取
)

// result.success → true
// result.messages → 消息数组
// result.notModified → true（如果未变化）
// result.lastModified → 时间戳
```

---

### 3.3 聊天记录写入 API

#### `POST /admin_api/agents/vcpchat-append-history`

**用途**：将手机端新消息追加到 VCPChat 桌面端的 history.json（双向同步）

**请求体**：
```json
{
  "agentDirId": "agent_1234567890",
  "topicId": "topic_1770624921406",
  "topicName": "手机话题 2026/2/23",
  "messages": [
    {
      "id": "msg_mobile_1771844000000",
      "role": "user",
      "name": "",
      "content": "这是从手机发送的消息",
      "timestamp": 1771844000000
    }
  ]
}
```

**响应**：
```json
{
  "success": true,
  "appended": 1,
  "total": 15
}
```

**行为说明**：
1. 基于消息 `id` 去重（已存在的不会重复追加）
2. 如果 `topicId` 在桌面端不存在，会自动创建话题目录
3. 同时更新 `config.json` 的 `topics` 列表（确保新话题出现在桌面端侧边栏）

**调用函数** (vcp-mobile `agentService.js`)：
```javascript
import { appendToHistory } from './services/agentService'

const result = await appendToHistory(
  { baseUrl, adminUsername, adminPassword },
  'agent_1234567890',
  'topic_1770624921406',
  [{ id: 'msg_1', role: 'user', content: '你好', timestamp: Date.now() }],
  '手机话题'
)

// result.success → true
// result.appended → 1
```

---

### 3.4 话题删除 API

#### `POST /admin_api/agents/vcpchat-delete-topic`

**用途**：从 VCPChat 桌面端删除话题（同步删除 config.json 条目 + 话题目录）

**请求体**：
```json
{
  "agentDirId": "agent_1234567890",
  "topicId": "topic_1770624921406"
}
```

**响应**：
```json
{
  "success": true
}
```

**调用函数** (vcp-mobile `agentService.js`)：
```javascript
import { deleteTopicFromDesktop } from './services/agentService'

const result = await deleteTopicFromDesktop(
  { baseUrl, adminUsername, adminPassword },
  'agent_1234567890',
  'topic_1770624921406'
)
```

---

### 3.5 壁纸 API

#### `GET /admin_api/agents/vcpchat-wallpapers`

**用途**：获取 VCPChat 壁纸列表

**响应**：
```json
{
  "success": true,
  "wallpapers": [
    { "name": "sunset.jpg" },
    { "name": "mountain.png" }
  ]
}
```

#### `GET /admin_api/agents/vcpchat-wallpaper/:filename`

**用途**：获取壁纸图片文件

**响应**：图片二进制流（带 `Cache-Control: public, max-age=86400`）

---

### 3.6 WebSocket 推送

#### `ws://host:port/vcp-mobile/VCP_Key=xxx`

**用途**：实时接收 VCPToolBox 推送的消息（新消息通知、Agent 状态变化等）

**认证**：URL 中的 `VCP_Key` 参数

**消息格式**：
```json
// 连接确认
{ "type": "connection_ack" }

// 心跳响应
{ "type": "heartbeat_ack" }

// 新消息推送
{
  "type": "new_message",
  "agentId": "Nova",
  "topicId": "topic_xxx",
  "message": { "id": "...", "role": "assistant", "content": "..." }
}
```

**客户端心跳**：
```json
{ "type": "heartbeat", "timestamp": 1771844000000 }
```

**调用函数** (vcp-mobile `vcpPush.js`)：
```javascript
import { connect, disconnect, onPushMessage, onStatusChange } from './services/vcpPush'

// 连接
connect({ baseUrl: 'http://192.168.1.100:5005', apiKey: 'Vcp_Secret_xxx' })

// 监听推送消息
onPushMessage((data) => {
  console.log('收到推送:', data.type)
})

// 监听连接状态
onStatusChange((status) => {
  // status: 'connected' | 'connecting' | 'reconnecting' | 'disconnected'
})

// 断开
disconnect()
```

---

### 3.7 ChatSync 插件 API

#### `GET /admin_api/chat-sync/status`

**用途**：检查 ChatSync 同步服务是否可用

**响应**：
```json
{ "success": true }
```

#### `POST /admin_api/chat-sync/sync`

**用途**：增量同步单个话题的聊天记录

**请求体**：
```json
{
  "agentId": "mobile-default",
  "topicId": "topic_xxx",
  "clientMessages": [...],
  "lastSyncTimestamp": 1771844000000
}
```

**响应**：
```json
{
  "success": true,
  "serverNewMessages": [...],
  "mergedCount": 5,
  "newFromClient": 2,
  "lastSyncTimestamp": 1771844100000
}
```

---

## 四、VCPChat 桌面端文件结构

VCPMobile 读写的 VCPChat 数据目录结构：

```
VCPChat/
├── AppData/
│   ├── Agents/
│   │   └── agent_1234567890/
│   │       └── config.json          ← Agent 配置（name, model, topics 列表）
│   └── UserData/
│       └── agent_1234567890/
│           └── topics/
│               └── topic_xxx/
│                   └── history.json  ← 聊天记录（消息数组）
└── assets/
    └── wallpaper/                    ← 壁纸图片
```

### config.json 格式
```json
{
  "name": "Nova",
  "model": "gemini-2.5-pro",
  "systemPrompt": "你是 Nova...",
  "temperature": 0.7,
  "maxOutputTokens": 60000,
  "topics": [
    { "id": "topic_xxx", "name": "日常聊天", "createdAt": 1770624921406 }
  ]
}
```

### history.json 格式
```json
[
  {
    "id": "msg_xxx",
    "role": "user",
    "name": "",
    "content": "你好",
    "timestamp": 1770624921406,
    "attachments": []
  }
]
```

---

## 五、认证方式

所有 `/admin_api/` 路由使用 **HTTP Basic Auth**：

```http
Authorization: Basic base64(username:password)
```

WebSocket 使用 **URL 参数认证**：

```
ws://host:port/vcp-mobile/VCP_Key=<VCP_Secret>
```

其中 `VCP_Secret` 是 VCPToolBox `config.env` 中的 `VCP_Secret` 值。

---

## 六、错误处理

所有 API 的错误响应格式：

```json
{
  "success": false,
  "error": "错误描述"
}
```

常见错误码：

| HTTP 状态码 | 含义 |
|------------|------|
| 400 | 参数缺失或格式错误 |
| 401 | 认证失败（用户名/密码错误） |
| 404 | 资源不存在（Agent/话题/壁纸） |
| 500 | 服务器内部错误 |

---

## 七、Windows 部署说明

### 7.1 前提条件

- Node.js 18+ 已安装
- VCPToolBox 已部署并运行
- VCPChat 桌面端已安装（`AppData/Agents/` 目录存在）

### 7.2 配置步骤

1. **确保 VCPChat 路径正确**

   在 `config.env` 中设置：
   ```env
   VarVchatPath=C:\Users\YourName\Documents\VCPChat
   ```

   或者将 VCPChat 放在 VCPToolBox 同级目录：
   ```
   Documents/
   ├── VCPToolBox/
   └── VCPChat/        ← 自动检测
   ```

2. **启动 VCPToolBox**
   ```cmd
   cd C:\Users\YourName\Documents\VCPToolBox
   node server.js
   ```

3. **配置手机端**
   - 打开 VCPMobile App
   - 设置 → 接口地址：`http://你的电脑IP:5005`
   - 设置 → API 密钥：`config.env` 中的 `VCP_Secret` 值
   - 设置 → 启用跨设备同步 → 输入管理面板用户名/密码

4. **确保防火墙允许访问**
   ```cmd
   netsh advfirewall firewall add rule name="VCPToolBox" dir=in action=allow protocol=TCP localport=5005
   ```

### 7.3 验证连接

在手机端：
1. 点击 Agent 名称旁边的状态点（绿色/灰色圆点）
2. 点击"测试连接"按钮
3. 查看 HTTP、WebSocket、同步状态

或在电脑端浏览器访问：
```
http://localhost:5005/admin_api/agents/mobile-list
```
（需要输入管理面板用户名/密码）

---

## 八、调试日志

VCPMobile v1.3.0+ 内置调试日志功能：

1. **打开日志**：设置 → 调试日志 → 查看日志
2. **复制日志**：点击"📋 复制日志"按钮
3. **连接诊断**：点击 Agent 名称旁的状态点 → 测试连接

日志包含：
- 应用启动信息（版本、设备、配置）
- API 请求/响应（URL、状态码、耗时）
- WebSocket 连接状态（连接/断开/重连）
- 同步状态（ChatSync 操作结果）
- 错误堆栈

---

## 九、总结

### VCPMobile 调用的所有 API 一览

| 方法 | 路径 | 用途 | 认证 |
|------|------|------|------|
| GET | `/v1/models` | 获取模型列表 | Bearer Token |
| POST | `/v1/chat/completions` | 发送聊天（流式） | Bearer Token |
| POST | `/v1/interrupt` | 中断流式响应 | Bearer Token |
| GET | `/admin_api/agents/mobile-list` | 获取 Agent 列表 | Basic Auth |
| GET | `/admin_api/agents/vcpchat-history` | 读取聊天记录 | Basic Auth |
| POST | `/admin_api/agents/vcpchat-append-history` | 写入聊天记录 | Basic Auth |
| POST | `/admin_api/agents/vcpchat-delete-topic` | 删除话题 | Basic Auth |
| GET | `/admin_api/agents/vcpchat-wallpapers` | 获取壁纸列表 | Basic Auth |
| GET | `/admin_api/agents/vcpchat-wallpaper/:name` | 获取壁纸图片 | Basic Auth |
| GET | `/admin_api/chat-sync/status` | 检查同步状态 | Basic Auth |
| POST | `/admin_api/chat-sync/sync` | 增量同步消息 | Basic Auth |
| WS | `/vcp-mobile/VCP_Key=xxx` | 实时推送 | URL Key |
