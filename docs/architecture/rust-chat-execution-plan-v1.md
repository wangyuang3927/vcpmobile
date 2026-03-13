# vcpmobile Rust Chat Execution Plan v1

## 1. 本轮新增决策

### 决策 A：Rust Engine ↔ Android 第一阶段桥接，选 **本地 HTTP + SSE**

#### 原因
1. 当前 Android 已有 `OkHttp + SSE` 骨架，可直接复用。
2. 当前 Hub 也已经在 HTTP/SSE 协议面有原型，Rust 替换时迁移成本最低。
3. 第一阶段目标是**快速得到顺滑聊天主链路**，不是先攻克 Rust/Kotlin FFI 工程复杂度。
4. Android 官方 JNI 指南明确建议：
   - 尽量减少跨 JNI 的 marshalling
   - 尽量避免跨语言的异步通信复杂度
   - 保持 JNI 边界少而清晰
5. UniFFI 虽然支持 Kotlin 绑定和 async FFI，但会引入：
   - 绑定生成链路
   - JNA 依赖
   - async future handle 生命周期管理
   - cancellation/线程附着复杂度

#### 结论
- **Phase 1：local HTTP + SSE**
- **Phase 2：如有必要，再评估 UniFFI/JNI 下沉热路径**

### 决策 B：浏览器验证壳保留，但降级为“视觉/节奏实验场”

浏览器壳只负责验证：
- 列表信息架构
- 时间线密度
- thinking/tool/content 分层
- 输入区视觉重心

不负责：
- 成为第二产品主线
- 承担真实业务真相

### 决策 C：第一批真正开工，不碰扫码真实打通

只保留：
- schema
- placeholder route
- placeholder screen
- session state slot

---

## 2. Rust Engine Phase 1 范围

### 必做
- `POST /api/chat/send`
- `GET /api/chat/stream/:conversation_id`
- `GET /api/conversations`
- `POST /api/conversations`
- `GET /api/conversations/:id`
- `POST /api/drafts/:conversation_id`
- `GET /api/drafts/:conversation_id`
- SQLite persistence
- typed events
- markdown/AST normalization

### 暂不做
- 多 provider 全覆盖
- 工具调用审批完整闭环
- 多端同步
- 真实扫码登录
- 浏览器完整工作台

---

## 3. Android 改造为三条状态链

### 3.1 Conversation List State
负责：
- topic list
- conversation list
- current conversation selection
- generation badge / unread hint

### 3.2 Conversation Detail State
负责：
- snapshot
- node patch 应用
- generation state
- reasoning fold state
- local scroll anchor hints

### 3.3 Draft State
负责：
- text draft
- attachments draft
- quick actions / preset message
- send/cancel/edit mode

### 明确要求
这三条链不共享一坨 UI state，不允许“发消息导致整个页面重建”。

---

## 4. 第一批任务（直接可开工）

### Task 01 — 建立 Rust workspace
- 新目录：`rust-engine/`
- crates：`domain` `protocol` `store` `session` `bridge-http`
- 产物：可编译 workspace

### Task 02 — 定义统一 schema
- `Conversation`
- `MessageNode`
- `MessageVariant`
- `MessagePart`
- `ChatEvent`
- JSON schema / serde struct

### Task 03 — Rust 版 event stream demo
- 不接真实上游
- 直接生成 demo conversation snapshot + node patch + completed events
- 目标：先让 Android UI 吃到稳定事件

### Task 04 — Android 状态层重构
- 新建 list/detail/draft state holders
- 先接 demo events
- 不急着对 VCPToolBox 真打通

### Task 05 — Compose 时间线升级
- `ChatMessage` 支持 typed parts
- reasoning/tool/content 分层
- 流式 node patch 局部更新

### Task 06 — 浏览器验证壳
- 只消费 demo events
- 做 2~3 套 timeline layout 实验
- 输出截图对比结论

---

## 5. 进入实现前的 done gate

在真正写业务代码前，需要满足：
1. Bridge 方案已定：**local HTTP + SSE**
2. 统一 schema 已出初稿
3. Android 三条状态链的改造点已明确
4. 浏览器验证壳范围已收束
5. 扫码 feature 已明确为 placeholder

满足以上条件后，下一轮 loop 应从**Task 01 + Task 02**直接开工。
