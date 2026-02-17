# 表情图/贴纸不显示 — 反复出现的 Bug 记录

## 问题现象

手机端（VCPMobile）查看 VCPChat 桌面端同步过来的聊天记录时，AI 回复中的表情图/贴纸（`<img>` 标签）无法显示。

## 根本原因

**服务端 `simplifyContent` 函数剥离了所有 HTML 标签，包括 `<img>`。**

位置：`VCPToolBox/routes/vcpchatMobileRoutes.js` → `simplifyContent()`

VCPChat 桌面端的 AI 回复中，表情图以 `<img src="...">` 标签嵌入在消息内容里。当 VCPMobile 通过 `/admin_api/agents/vcpchat-history` API 拉取聊天记录时，服务端调用 `simplifyContent()` 把 assistant 消息中的 HTML 精简为纯文本，以减少移动端渲染压力。但该函数原来使用 `/<[^>]+>/g` 正则把**所有** HTML 标签（包括 `<img>`）都删除了。

## 出现历史

| 时间 | 触发原因 | 修复方式 |
|------|----------|----------|
| 2026-02-08 | 首次发现：表情图 401 错误 | 添加 `imageKey` 认证前缀（`fixStickerUrls` 增加 authPrefix） |
| 2026-02-16 | 服务端 `simplifyContent` 剥离 `<img>` | 修改正则为 `/<(?!\/?img\b)[^>]+>/gi`，保留 `<img>` 标签 |
| 2026-02-16 | 客户端渐进渲染误删 `!isVCPChat` 条件 | 恢复 `!isVCPChat` 条件，VCPChat 消息始终完整渲染 |
| 2026-02-16 | sandbox iframe URL 修复不完整 | `mountSandbox` 增加 localhost 替换、/pw= 域名替换、表情包认证前缀 |
| 2026-02-16 | IndexedDB 缓存含旧版被剥离的消息 | 添加一次性缓存版本控制（`vcpCacheVer_2`），首次启动清空旧缓存 |

## 涉及文件

| 文件 | 作用 |
|------|------|
| `VCPToolBox/routes/vcpchatMobileRoutes.js` | 服务端 API，`simplifyContent` 精简消息内容 |
| `vcp-mobile/src/utils/messageRenderer.js` | 客户端渲染，`fixStickerUrls` 修正表情图 URL |
| `vcp-mobile/src/utils/vcpRichSandbox.js` | sandbox iframe 渲染，`mountSandbox` 中的 URL 修复逻辑 |
| `vcp-mobile/src/App.vue` | `renderContent` 调用 `renderMessageHtml`；`mountSandboxForMessage` 传入 `baseUrl` 和 `imageKey` |
| `vcp-mobile/src/services/messageCache.js` | IndexedDB 缓存，`clearAllCache` 用于清理旧版缓存 |

## 表情图 URL 处理链路

表情图有**两条渲染路径**，必须同时保证正确：

### 路径 A：普通消息（marked 渲染）
```
桌面端 AI 回复 (含 <img src="...表情包...">)
  ↓
VCPToolBox 服务端 simplifyContent() ← 🔴 曾在此处被剥离
  ↓
VCPMobile fetchTopicHistory() 拉取消息
  ↓
renderContent() → renderMessageHtml()
  ↓
fixStickerUrls(text, baseUrl, imageKey) ← 修正 URL 域名 + 认证路径
  ↓
marked.parse() → DOMPurify.sanitize()
  ↓
<img> 渲染到页面
```

### 路径 B：sandbox iframe 渲染（enableAgentBubbleTheme）
```
AI 回复 (含 <div id="vcp-root"> + <img src="...表情包...">)
  ↓
renderMessageHtml() → 检测 vcp-root + script → 返回 sandbox-container 占位符
  ↓
mountSandboxForMessage() → mountSandbox(messageId, content, container, baseUrl, imageKey)
  ↓
extractVcpRootHtml() → 提取 HTML
  ↓
URL 修复：localhost 替换 + /pw= 域名替换 + 表情包认证前缀注入 ← 🔴 曾遗漏
  ↓
buildSrcdoc() → iframe.srcdoc
  ↓
<img> 在 iframe 中渲染
```

## 如何避免再次发生

1. **`simplifyContent` 中的 HTML 剥离正则必须排除 `<img>` 标签**
   - 当前正则：`/<(?!\/?img\b)[^>]+>/gi`（负向前瞻排除 img）
   - 函数头部有 ⚠️ 注释提醒

2. **`mountSandbox` 的 URL 修复必须与 `fixStickerUrls` 保持一致**
   - 两处代码处理不同渲染路径，必须同时更新

3. **修改渲染逻辑时注意 `isVCPChat` 条件**
   - VCPChat 消息的内容可能含有 `<img>` 标签，不可用纯文本转义

4. **IndexedDB 缓存可能含有旧版数据**
   - 修改服务端返回格式后，需要递增 `vcpCacheVer_*` 触发缓存清理

5. **测试用例**（手动验证）：
   ```javascript
   // 服务端测试
   const testContent = '你好！<img src="/pw=key/images/表情包/happy.png"> 开心';
   const result = simplifyContent(testContent, 'assistant');
   console.assert(result.includes('<img'), '表情图标签被错误剥离！');

   // sandbox URL 修复测试
   // mountSandbox 后检查 iframe.srcdoc 中的 <img src="..."> 是否包含正确的 baseUrl
   ```
