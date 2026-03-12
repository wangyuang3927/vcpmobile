# vcpmobile Rust Chat Blueprint v1

> 目标：吸收 RikkaHub 的高价值设计原则，但不复制其实现形态；将 vcpmobile 从“Hub relay + 单页演示”推进为“聊天优先的原生客户端系统”。

## 1. 结论先行

### 我们真正要学的
- 结构化会话模型
- 本地热状态
- 列表/详情/输入解耦
- typed event / node diff
- thinking/tool/content 分层
- 把“顺滑”当作状态与渲染协同问题，而不是皮肤问题

### 我们不该照搬的
- Android 内嵌 Web server + React 壳作为产品主体
- Web 特有 markdown/workbench 细节实现
- 围绕浏览器认证、localStorage、Web SSE parser 建系统
- 继续扩张旧 Vue/Capacitor 主线

### 我们的 working tag
**热状态在地，冷历史可回，界面克制但操作不断裂。**

---

## 2. 目标架构

```text
Android App (Kotlin + Compose)
   ├─ Chat UI / Timeline / Input / Topic List / Agent List
   ├─ Local draft cache / image picker / interaction layer
   └─ Rust Engine Client (JNI / local HTTP / local socket, 二选一)

Rust Chat Engine
   ├─ Conversation session manager
   ├─ Topic / Agent / Draft / Attachment store
   ├─ Upstream adapters (VCPToolBox / OpenAI-compatible)
   ├─ Stream normalizer (token -> typed events)
   ├─ Markdown/AST pipeline
   ├─ SQLite persistence
   ├─ QR auth placeholder state machine
   └─ Local event bus / SSE or socket bridge

VCPToolBox
   └─ 保持外部上游，不承担 UI 状态真相
```

### 立场
- **Rust Engine** 成为聊天领域真相源。
- **Android Compose** 成为高质量原生渲染层。
- 浏览器只是验证壳，不是产品本体。

---

## 3. 模块边界

### 3.1 Rust Engine 模块

#### `crates/domain`
定义统一领域模型：
- `AgentProfile`
- `Topic`
- `Conversation`
- `MessageNode`
- `MessageVariant`
- `MessagePart`
- `DraftState`
- `AttachmentRef`
- `GenerationState`

#### `crates/session`
负责热状态：
- active conversation sessions
- generation jobs
- in-flight tool approval
- scroll anchor hints / unread markers / draft sync points

#### `crates/protocol`
负责 App ↔ Engine typed protocol：
- request DTOs
- response DTOs
- event schema
- schema versioning

#### `crates/upstream`
负责对接 VCPToolBox / OpenAI-compatible：
- auth headers
- request mapping
- upstream stream parsing
- vendor quirks normalization

#### `crates/renderer`
负责 markdown / AST / rich content normalization：
- markdown -> AST
- code block metadata
- link / image / table / quote / list
- reasoning / tool block normalization

#### `crates/store`
负责 SQLite：
- conversations
- topics
- message_nodes
- message_variants
- attachments
- drafts
- qr_sessions
- agent_cache

#### `crates/bridge`
负责和 Android 通信：
- phase 1 可用本地 HTTP + SSE
- phase 2 可切到 Unix domain socket / local websocket / JNI

---

## 4. 领域模型

## 4.1 Conversation

```text
Conversation
- id
- topic_id
- agent_id
- title
- created_at
- updated_at
- pinned
- summary
- generation_state
- current_cursor
```

## 4.2 MessageNode

> 借鉴 RikkaHub 的分支思想，但做得更窄更清晰。

```text
MessageNode
- id
- conversation_id
- parent_node_id?
- role (user/assistant/system/tool)
- select_index
- created_at
- updated_at
```

## 4.3 MessageVariant

```text
MessageVariant
- id
- node_id
- status (streaming/completed/error/cancelled)
- model_id
- usage_json
- created_at
- finished_at?
```

## 4.4 MessagePart

```text
MessagePart
- id
- variant_id
- order_index
- type
- payload_json
```

支持类型：
- `text`
- `reasoning`
- `tool`
- `image`
- `document`
- `error`

后续扩展可继续支持：
- `quote`
- `code_block`
- `markdown_block`

### 关键原则
不要把一切都塞回 markdown 字符串。  
markdown 只是 text/content 的一种来源，不是领域真相。

## 4.5 Invariants

- `Conversation.current_cursor` 指向当前选中分支的叶子 `MessageNode`，不是 `MessageVariant`。
- `MessageNode.select_index` 是节点当前选中 variant 的唯一真相源；Android 只能投影，不能补真相。
- `MessageVariant` 是同一节点的一次具体实现，不自带 branch identity；branch 由 `current_cursor + parent_node_id` 链恢复。
- `conversation.node.select` 只切换同一 `MessageNode` 的选中 variant，不改写 node identity、parent links 或 parts。
- assistant regenerate/retry 发生在同一 `MessageNode` 上：追加一个新的 `MessageVariant` 并切换选中项。
- 编辑已持久化 user turn 时，必须从原 parent 新建 `MessageNode` 分支，而不是原地改写旧节点内容。

---

## 5. Typed Event 协议

### 5.1 为什么要 typed event
当前 Hub 更像“字节中继器”。新架构里，Engine 应输出**可局部应用、可恢复、可重放**的事件。

### 5.2 事件集合

#### Conversation 列表面
- `conversation.list.invalidate`
- `conversation.list.patch`

#### Conversation 详情面
- `conversation.snapshot`
- `conversation.node.upsert`
- `conversation.node.select`
- `conversation.node.remove`
- `conversation.meta.update`

#### 生成面
- `generation.started`
- `generation.part.delta`
- `generation.reasoning.delta`
- `generation.tool.request`
- `generation.tool.result`
- `generation.completed`
- `generation.failed`
- `generation.cancelled`

#### 输入 / 草稿面
- `draft.updated`
- `draft.cleared`

#### 系统面
- `auth.qr.placeholder`
- `agent.cache.updated`
- `engine.warning`
- `engine.error`

### 5.3 事件设计原则
- 事件要能独立应用，不依赖隐式顺序推理。
- 同一 node 的 streaming 更新尽量局部化。
- snapshot 只作为回退机制，而不是常态。
- 每个事件都带 `schema_version` 和 `conversation_id`。

---

## 6. 存储策略

### 6.1 热状态 vs 冷状态

#### 热状态（内存）
- 当前会话的 in-flight generation
- 当前输入草稿镜像
- 临时 scroll/selection 状态
- tool approval pending

#### 冷状态（SQLite）
- topics
- conversations
- nodes / variants / parts
- attachments
- recent agent cache
- qr session placeholder

### 6.2 草稿策略
- 每个 conversation 独立草稿
- 输入内容和附件分开存
- Android 本地可有短时 UI cache，但 Engine 是草稿真相源

### 6.3 搜索策略
Phase 1：SQLite LIKE / FTS5 搜文本  
Phase 2：按 `MessagePart(text/reasoning/tool)` 建立搜索权重

---

## 7. Android Compose 层如何改

### 保留
- OkHttp / SSE 骨架
- AST 解析与映射思路
- Compose 聊天页面种子

### 重构
- `ChatMessage` 从“文本气泡”升级为“node + selected variant + typed parts”
- `ChatViewModel` 从单页状态升级为：
  - conversation list state
  - conversation detail state
  - draft state
  - generation state
- 列表、详情、输入三条状态链解耦

### UI 节奏原则
- 时间线留白比现在更克制
- 操作按钮只在 hover/press/focus/selected 时显性增强
- reasoning 默认折叠，但 streaming 中半展开
- tool/result 用弱边框容器，不和正文争中心
- 生成态只点亮正在变化的 node，不让全页抖动

---

## 8. 为什么 RikkaHub 看起来顺：对我们的翻译

| RikkaHub 原理 | 我们的翻译 |
|---|---|
| 本地会话状态 | Rust session manager |
| Conversation / MessageNode / UIMessagePart | Conversation / MessageNode / MessageVariant / MessagePart |
| 单节点 diff | typed node patch event |
| stick-to-bottom | Compose 中显式滚动吸附策略 |
| reasoning/tool/content 分层 | Compose 多段气泡结构 |
| 列表与详情 SSE 分离 | list channel / detail channel 分离 |

---

## 9. 扫码能力如何保留但不阻塞主线

### 本轮只做三件事
1. 保留数据模型：`QrSessionPlaceholder`
2. 保留接口：
   - `POST /auth/qr/session`
   - `GET /auth/qr/session/:id`
   - `POST /auth/qr/session/:id/approve`
3. Android UI 只做占位页与状态展示

### 明确不做
- 不做真实扫码串联
- 不做真实 token 绑定
- 不做跨设备安全收口

这样未来能扩，但不会污染当前聊天主线。

---

## 10. 浏览器验证策略

浏览器不是产品本体，但非常适合验证信息密度和节奏。

### 验证壳目标
做一个极轻的 web prototype，只验证：
- 时间线密度
- 思考块折叠策略
- 工具结果展示
- 底部输入区层级
- 多 Agent / 多 Topic 左侧信息架构

### 验证方法
- Rust Engine 提供 demo conversation snapshots / typed events
- 浏览器壳只消费事件并渲染
- 用截图对比三版：
  - 当前 Android
  - RikkaHub 截图
  - 我们 prototype

### 通过标准
- 不靠“更花”获得高级感
- 大量信息时仍然有中心
- streaming 时眼睛知道该看哪里
- 停止生成时页面没有整体抖动

---

## 11. 分阶段实施

### Phase A：建真相源
- Rust crate workspace
- domain / protocol / store / session
- SQLite schema
- demo event generator

### Phase B：打通单会话主链路
- Android 连接 Rust Engine
- 发送消息
- 流式接收 typed events
- 渲染 text/reasoning/code block

### Phase C：补齐列表与多会话
- topic list
- conversation list
- draft isolation
- node patching

### Phase D：补齐工具与附件
- attachment upload
- tool request/result
- richer markdown/AST

### Phase E：扫码占位与后续能力
- qr placeholder screens
- auth state slot
- future integration hooks

---

## 12. 第一批任务拆解

### Task 1
建立 `rust-engine/` workspace 与 4 个 crate：
- `domain`
- `protocol`
- `store`
- `session`

### Task 2
定义统一 schema：
- conversation
- node
- variant
- part
- event

### Task 3
把现有 Hub `markdownAst.ts` 的能力迁移为 Rust renderer 契约草案

### Task 4
Android 侧建立新的 repository / state holder，支持：
- list state
- detail state
- draft state

### Task 5
做一个浏览器 prototype，用 Rust demo events 验证时间线与输入区节奏

### Task 6
把扫码逻辑改为 placeholder feature，不再耦合真实联调

---

## 13. 最后的立场

如果我们的目标真的是“聊天体验产品”，那下一步不该是继续给当前 thin relay 打补丁。  
真正该做的是：

**把 Rust 变成聊天状态与结构化内容的引擎，把 Compose 变成最好的原生呈现层。**
