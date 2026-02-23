# 如何向 VCPToolBox / VCPChat 原作者提交 PR

本文档详细说明如何将你为 VCPMobile 适配而修改的 VCPToolBox / VCPChat 代码，以 Pull Request 的方式提交给原作者 `lioensky`。

---

## 前置条件

- 你有 GitHub 账号（`wangyuang3927`）
- 你已登录 GitHub 网页版
- 你的本地 VCPToolBox 仓库在 `/Users/jiaozi/Documents/vcp/VCPToolBox`
- 你的本地 VCPChat 仓库在 `/Users/jiaozi/Documents/vcp/VCPChat`

## 当前仓库状态

```
VCPToolBox remotes:
  mine     → https://github.com/wangyuang3927/VCPToolBox-private.git  (你的私有仓库)
  upstream → https://github.com/lioensky/VCPToolBox.git               (原作者)
```

⚠️ **问题**：你的 `VCPToolBox-private` 是**私有仓库**，GitHub 不允许从私有仓库向公开仓库提 PR。

---

## 方案一：通过公开 Fork 提 PR（推荐）

### 第 1 步：在 GitHub 上 Fork 原作者仓库

1. 打开浏览器，访问 https://github.com/lioensky/VCPToolBox
2. 点击右上角的 **Fork** 按钮
3. 在弹出的页面中：
   - **Owner**: 选择 `wangyuang3927`
   - **Repository name**: 保持 `VCPToolBox`（不要改名）
   - 取消勾选 "Copy the `main` branch only"（如果有的话）
4. 点击 **Create fork**
5. 等待 Fork 完成，你会得到 `https://github.com/wangyuang3927/VCPToolBox`

对 VCPChat 重复同样操作：
1. 访问 https://github.com/lioensky/VCPChat
2. Fork → `https://github.com/wangyuang3927/VCPChat`

### 第 2 步：在本地添加公开 Fork 为 remote

```bash
cd /Users/jiaozi/Documents/vcp/VCPToolBox

# 添加你的公开 fork 作为新的 remote
git remote add fork https://github.com/wangyuang3927/VCPToolBox.git

# 验证
git remote -v
# 应该看到：
# mine     https://github.com/wangyuang3927/VCPToolBox-private.git
# upstream https://github.com/lioensky/VCPToolBox.git
# fork     https://github.com/wangyuang3927/VCPToolBox.git
```

对 VCPChat 同样操作：
```bash
cd /Users/jiaozi/Documents/vcp/VCPChat
git remote add fork https://github.com/wangyuang3927/VCPChat.git
```

### 第 3 步：创建 PR 分支并推送

```bash
cd /Users/jiaozi/Documents/vcp/VCPToolBox

# 基于原作者最新代码创建分支
git fetch upstream
git checkout -b vcpmobile-support upstream/main

# 将你的 VCPMobile 适配改动 cherry-pick 或手动复制过来
# 需要的文件（参考 docs/VCPMobile_Adaptation_Guide.md）：

# 新增文件：
# - routes/vcpchatMobileRoutes.js
# - Plugin/ChatSync/ChatSync.js  (及其 plugin-manifest.json)
# - Plugin/VCPChatDesktopSync/VCPChatDesktopSync.js  (及其 plugin-manifest.json)

# 修改文件：
# - routes/adminPanelRoutes.js  (第847行附近，引入 vcpchatMobileRoutes)
# - WebSocketServer.js  (新增 mobilePathRegex 和 MobileClient 通道)
# - server.js  (注册 ChatSync 插件)

# 提交
git add .
git commit -m "feat: add VCPMobile support - mobile API routes, ChatSync plugin, WebSocket mobile channel"

# 推送到你的公开 fork
git push fork vcpmobile-support
```

### 第 4 步：在 GitHub 网页上创建 Pull Request

1. 打开 https://github.com/wangyuang3927/VCPToolBox
2. GitHub 会自动显示一个黄色横幅：**"vcpmobile-support had recent pushes — Compare & pull request"**
3. 点击 **Compare & pull request**
4. 填写 PR 信息：

**Title（标题）**：
```
feat: 新增 VCPMobile 手机端支持（API 路由 + ChatSync 插件 + WebSocket 通道）
```

**Description（描述）**：
```markdown
## 概述

为 VCPToolBox 新增 VCPMobile 手机端支持，包括：
- 专用 API 路由（Agent 列表、聊天历史、消息追加、话题管理、壁纸）
- ChatSync 插件（跨设备消息同步）
- WebSocket 移动端通道（实时推送）

## 新增文件

| 文件 | 说明 |
|------|------|
| `routes/vcpchatMobileRoutes.js` | 12 个移动端专用 API 端点 |
| `Plugin/ChatSync/ChatSync.js` | 聊天同步插件核心逻辑 |
| `Plugin/VCPChatDesktopSync/VCPChatDesktopSync.js` | 桌面端同步适配 |

## 修改文件

| 文件 | 改动 |
|------|------|
| `routes/adminPanelRoutes.js` | 引入并挂载 vcpchatMobileRoutes |
| `WebSocketServer.js` | 新增 `/vcp-mobile/VCP_Key=xxx` 通道 |
| `server.js` | 注册 ChatSync 插件 |

## 相关项目

- VCPMobile 仓库：https://github.com/wangyuang3927/vcpmobile
- 详细 API 文档：见 VCPMobile 仓库的 `docs/VCPMobile_Adaptation_Guide.md`

## 测试

- ✅ VCPMobile v1.4.1 已在 Android 16 上测试通过
- ✅ Agent 列表拉取、聊天历史同步、消息追加、WebSocket 推送均正常
- ✅ 不影响 VCPChat 桌面端现有功能
```

5. **Base repository**: `lioensky/VCPToolBox`，**base**: `main`
6. **Head repository**: `wangyuang3927/VCPToolBox`，**compare**: `vcpmobile-support`
7. 点击 **Create pull request**

### 第 5 步：对 VCPChat 重复同样操作

VCPChat 的改动较少（如果有的话），同样流程：
```bash
cd /Users/jiaozi/Documents/vcp/VCPChat
git fetch upstream
git checkout -b vcpmobile-support upstream/main
# 复制改动文件...
git add . && git commit -m "feat: add VCPMobile compatibility"
git push fork vcpmobile-support
```
然后在 GitHub 网页上创建 PR。

---

## 方案二：不 Fork，直接发 Issue + 附件

如果你不想创建公开 Fork，可以：

1. 在 https://github.com/lioensky/VCPToolBox/issues 创建 Issue
2. 标题：`[Feature Request] VCPMobile 手机端支持`
3. 在 Issue 中：
   - 描述功能
   - 附上 patch 文件（`vcp-mobile-release/patch/vcptoolbox/` 中的文件）
   - 或者直接贴代码差异

---

## 需要 PR 的文件清单

### VCPToolBox（6 个文件）

**新增 3 个文件：**

| 文件路径 | 说明 |
|---------|------|
| `routes/vcpchatMobileRoutes.js` | 移动端 API 路由（12 个端点） |
| `Plugin/ChatSync/ChatSync.js` + `plugin-manifest.json` | 聊天同步插件 |
| `Plugin/VCPChatDesktopSync/VCPChatDesktopSync.js` + `plugin-manifest.json` | 桌面端同步适配 |

**修改 3 个文件：**

| 文件路径 | 改动位置 | 说明 |
|---------|---------|------|
| `routes/adminPanelRoutes.js` | ~第 847 行 | `require('./vcpchatMobileRoutes')(adminApiRouter, ...)` |
| `WebSocketServer.js` | ~第 70, 78, 108 行 | 新增 `mobilePathRegex` 和 MobileClient 处理 |
| `server.js` | 插件注册区域 | 注册 ChatSync 插件 |

### VCPChat（视情况）

目前 VCPChat 桌面端不需要改动即可与 VCPMobile 配合使用。如果后续需要改动，同样流程。

---

## 常见问题

### Q: Fork 和我现有的私有仓库冲突吗？
A: 不冲突。Fork 是一个独立的公开仓库，你的私有仓库 `VCPToolBox-private` 继续保留。两者互不影响。

### Q: 原作者更新了代码，我的 PR 怎么办？
A: 在 PR 页面会显示是否有冲突。如果有，需要在本地 rebase：
```bash
git fetch upstream
git rebase upstream/main
git push fork vcpmobile-support --force
```

### Q: PR 被拒绝了怎么办？
A: 根据原作者的反馈修改代码，然后 `git push fork vcpmobile-support` 更新 PR（不需要重新创建）。

### Q: 我不想公开我的代码怎么办？
A: 用方案二（发 Issue），或者直接在 VCP 社区群里联系原作者，把 patch 文件发给他。
