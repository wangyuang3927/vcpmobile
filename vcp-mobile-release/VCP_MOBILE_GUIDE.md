# VCP Mobile 手机端部署与功能指南

## 概述

VCP Mobile 是 VCPChat 桌面客户端的手机伴侣应用，基于 **Vue 3 + Capacitor** 构建，打包为 Android APK。它通过 VCPToolBox 服务端 API 与桌面端实现双向同步，让你随时随地与 AI 对话，并在手机和电脑之间无缝衔接。

- **VCP Mobile 仓库**：https://github.com/wangyuang3927/vcpmobile
- **VCPToolBox 改造版**：https://github.com/wangyuang3927/VCPToolBox
- **VCPToolBox 原版**：https://github.com/lioensky/VCPToolBox
- **VCPChat 原版**：https://github.com/lioensky/VCPChat

---

## 一、架构总览

```
┌─────────────┐     HTTP/WS      ┌──────────────┐     文件读写     ┌──────────────┐
│  VCP Mobile  │ ◄──────────────► │  VCPToolBox   │ ◄─────────────► │   VCPChat    │
│  (Android)   │   admin_api      │  (Node.js)    │   AppData/      │  (Electron)  │
└─────────────┘                   └──────────────┘                  └──────────────┘
```

- **VCP Mobile**：手机端 App，通过 HTTP API 与 VCPToolBox 通信
- **VCPToolBox**：中间服务层（端口 6005），提供 Agent 列表、聊天记录、消息追加、话题管理等 API
- **VCPChat**：桌面端 Electron 应用，数据存储在 `AppData/` 目录

---

## 二、VCPToolBox 服务端改造

### 2.1 新增文件

**`routes/vcpchatMobileRoutes.js`** — 手机端专用路由，需在 `server.js` 中注册。

### 2.2 提供的 API

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/admin_api/agents/mobile-list` | 获取 Agent 列表（优先读取 VCPChat AppData，回退到 agent_map.json） |
| GET | `/admin_api/agents/vcpchat-history` | 获取指定话题的聊天记录（支持 `ifModifiedSince` 增量同步） |
| POST | `/admin_api/agents/vcpchat-append-history` | 将手机端新消息追加到桌面端 history.json（去重 + 自动创建话题） |
| POST | `/admin_api/agents/vcpchat-delete-topic` | 从桌面端删除话题（移除 config.json 条目 + 删除话题目录） |
| GET | `/admin_api/agents/vcpchat-wallpapers` | 获取壁纸文件列表 |
| GET | `/admin_api/agents/vcpchat-wallpaper/:filename` | 获取壁纸图片文件 |

### 2.3 注册路由

在 `server.js` 中添加：

```javascript
const vcpchatMobileRoutes = require('./routes/vcpchatMobileRoutes');
vcpchatMobileRoutes(adminApiRouter, AGENT_MAP_FILE, parseAgentAssistantConfig);
```

### 2.4 数据读写路径

服务端通过 `getVCPChatPath()` 定位 VCPChat 安装目录，读写以下路径：

- **Agent 配置**：`VCPChat/AppData/Agents/{agentDirId}/config.json`
- **聊天记录**：`VCPChat/AppData/UserData/{agentDirId}/topics/{topicId}/history.json`
- **全局设置**：`VCPChat/AppData/settings.json`

路径优先从环境变量 `VarVchatPath` 获取，回退到 `../VCPChat`。

### 2.5 认证

所有 API 需要 AdminPanel 认证头：
- `X-Admin-Username`
- `X-Admin-Password`

---

## 三、VCPChat 桌面端改造

**VCPChat 本身无需修改代码**。VCPToolBox 直接读写 VCPChat 的 `AppData` 目录文件，实现数据同步。

唯一的前提：VCPChat 和 VCPToolBox 部署在同一台机器上，VCPToolBox 能访问 VCPChat 的 `AppData` 目录。

---

## 四、VCP Mobile 手机端功能

### 4.1 核心功能

| 功能 | 说明 |
|------|------|
| **多 Agent 切换** | 从桌面端同步 Agent 列表，侧边栏切换 |
| **话题管理** | 新建、切换、删除话题，双向同步到桌面端 |
| **AI 对话** | 流式输出，支持中断 |
| **聊天记录同步** | IndexedDB 缓存 + lastModified 增量同步，秒开 |
| **双向消息同步** | 手机发的消息自动追加到桌面端 history.json |
| **壁纸选择** | 18 张内置壁纸，本地存储偏好 |
| **WebSocket 推送** | 实时接收服务端推送消息 |
| **附件支持** | 图片、文件、音频附件发送 |
| **语音输入** | 录音转文字 |
| **Markdown 渲染** | 完整的 Markdown + 代码高亮 + HTML 气泡渲染 |

### 4.2 功能边界（手机端不支持的操作）

| 不支持的操作 | 原因 |
|-------------|------|
| 新增 Agent | Agent 配置复杂（模型、系统提示词、参数），需在桌面端 VCPChat 中创建 |
| 编辑 Agent | 同上，涉及系统提示词模板、TTS 配置等 |
| 删除 Agent | 避免误操作，需在桌面端操作 |
| 修改 Agent 模型/参数 | 手机端使用桌面端配置的模型和参数 |
| 主题同步 | 壁纸为手机端本地功能，不与桌面端同步 |

**设计理念**：手机端是桌面端的**轻量伴侣**，专注于对话和查看，配置管理留在桌面端。

### 4.3 同步机制

```
手机发消息 ──► AI 回复完成 ──► appendToHistory() ──► 桌面端 history.json 更新
                                                  ──► 桌面端 config.json 话题列表更新

手机删除话题 ──► deleteTopicFromDesktop() ──► 桌面端 config.json 移除 + 话题目录删除

桌面端删除话题 ──► 手机刷新 Agent 列表 ──► 服务端话题列表与本地合并 ──► 已删除话题消失
```

### 4.4 缓存策略

- **Agent 列表**：localStorage 缓存，启动时秒加载，后台异步刷新
- **聊天记录**：IndexedDB 缓存，带 `lastModified` 时间戳，增量同步
- **本地消息**：`isLocal` 标记，优先保留本地版本（有完整 HTML 渲染），不被服务端简化版覆盖
- **壁纸偏好**：localStorage 存储文件名

---

## 五、手机端项目结构

```
vcp-mobile/
├── public/
│   └── wallpapers/          # 18 张内置壁纸（约 41MB）
├── src/
│   ├── App.vue              # 主组件（UI + 逻辑）
│   ├── style.css            # 全局样式
│   ├── services/
│   │   ├── agentService.js  # Agent/话题/消息同步 API
│   │   ├── chatSync.js      # 跨设备聊天记录同步
│   │   ├── messageCache.js  # IndexedDB 消息缓存
│   │   ├── vcpApi.js        # AI 对话 API（流式）
│   │   └── vcpPush.js       # WebSocket 推送
│   └── utils/
│       └── messageRenderer.js # Markdown + HTML 渲染
├── android/                 # Capacitor Android 项目
├── capacitor.config.ts
├── vite.config.js
└── package.json
```

---

## 六、原版用户使用指南（最小改动）

经与原版 `lioensky/VCPToolBox` 仓库对比，手机端功能**仅需改动 VCPToolBox 的 2 个文件，VCPChat 无需任何改动**：

### 6.1 压缩包内容

发布压缩包 `VCP-Mobile-Release.zip` 包含以下文件：

| 压缩包内文件 | 放置路径 | 说明 |
|-------------|---------|------|
| `vcpchatMobileRoutes.js` | `VCPToolBox/routes/vcpchatMobileRoutes.js` | **新增文件**，直接复制到 `routes/` 目录 |
| `adminPanelRoutes.js` | `VCPToolBox/routes/adminPanelRoutes.js` | **替换原文件**，已包含手机端路由注册代码 |
| `app-debug.apk` | 手机安装 | 手机端 App，直接安装到 Android 手机 |
| `VCP_MOBILE_GUIDE.md` | 参考文档 | 本文档 |

### 6.2 服务端改动步骤

**方案 A（推荐）：直接替换文件**

1. 将压缩包中的 `vcpchatMobileRoutes.js` 复制到 `VCPToolBox/routes/` 目录
2. 将压缩包中的 `adminPanelRoutes.js` **替换** `VCPToolBox/routes/adminPanelRoutes.js`
3. 确保 `config.env` 中 `VarVchatPath` 指向 VCPChat 安装目录（默认为 `../VCPChat`，同目录部署无需修改）
4. 重启 VCPToolBox

**方案 B：手动修改（如果你的 adminPanelRoutes.js 有自定义改动）**

1. 将压缩包中的 `vcpchatMobileRoutes.js` 复制到 `VCPToolBox/routes/` 目录
2. 打开 `VCPToolBox/routes/adminPanelRoutes.js`，在文件末尾的 `};` 之前，添加以下 2 行：

```javascript
    // --- VCPChat Mobile 同步路由（Agent 列表 + 聊天记录） ---
    require("./vcpchatMobileRoutes")(adminApiRouter, AGENT_MAP_FILE, parseAgentAssistantConfig);
```

3. 确保 `config.env` 中 `VarVchatPath` 指向 VCPChat 安装目录
4. 重启 VCPToolBox

### 6.3 VCPChat 桌面端

**无需任何改动**。服务端直接读写 VCPChat 的 `AppData` 目录。

### 6.4 手机端

```bash
# 安装依赖
cd vcp-mobile
npm install

# 构建
npx vite build

# 同步到 Android
npx cap sync android

# 构建 APK
cd android && ./gradlew assembleDebug

# 安装到手机
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### 6.3 手机端配置

1. 打开 App → 设置
2. 填写 **接口地址**（如 `http://your-server:6005`）
3. 填写 **API 密钥**（VCPToolBox 的访问密钥，内网穿透场景必填）
4. 启用 **跨设备同步** → 填写 **管理面板用户名/密码**
5. 保存

---

## 七、注意事项

- VCPToolBox 需要能访问 VCPChat 的 `AppData` 目录（同一台机器）
- 内网穿透场景需要配置 API 密钥
- 手机端壁纸为本地资源，不占用网络流量
- 手机端新建的话题会自动同步到桌面端，但 Agent 管理需在桌面端操作
- 聊天记录采用增量同步，首次加载后切换话题秒开
