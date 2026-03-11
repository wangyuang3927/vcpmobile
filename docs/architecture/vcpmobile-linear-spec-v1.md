# vcpmobile Linear Spec v1

## 1. Summary

本 spec 用来替换当前混杂的两套叙事：

- 历史 `.trellis` 文档中的 `Bun + Hono Hub`、群聊优先、Android 尚处骨架期
- 当前仓库实际已经推进到的 `Rust Chat Engine + Android Compose thin client`

本轮规划以**当前代码事实**为真相源，并将其整理成可直接拆分到 Linear 的开发规范。

## 2. Current Reality

### 2.1 已经成立的事实

- Rust 聊天引擎已经存在，位于 `rust-engine/`
- bridge 方案已经不是待定，而是 **local HTTP + SSE**
- Rust 侧已有 `domain / protocol / store / session / bridge-http`
- Android 侧已经不是纯骨架，而是已有：
  - `ChatDetailState`
  - `ChatDraftState`
  - `ChatDetailReducer`
  - `OkHttpSseHubApiClient`
  - `ConversationCatalogWorkbench`
  - recovery / catalog / typed parts 相关实现

### 2.2 当前混乱来源

- 旧 PRD 仍把 Hub 写成 `TypeScript + Bun`
- 旧 PRD 把“群聊 + Agent 编排”放在核心主线
- 旧跨层协议仍围绕 `ast / relay.done / relay.error / token passthrough`
- 当前代码已经转向：
  - Rust-first truth
  - typed event
  - catalog/recovery
  - selected variant parts
  - Android 原生投影而非 Web/Hub AST 主导

结论：**不能继续沿用旧 PRD 直接拆 Linear。**

## 3. Product Direction

### 3.1 Primary Goal

打造以聊天为中心的 Android 客户端：

- Rust 负责聊天领域真相、恢复、catalog facts、draft truth、protocol
- Android 负责渲染、交互、视图级投影

### 3.2 Non-Goals For This Phase

- 不以群聊作为当前主线目标
- 不继续扩张 Bun Hub 方案
- 不把浏览器验证壳重新抬升为产品主体
- 不在当前阶段打通真实扫码登录闭环
- 不在 Android 侧重新定义 rich-content 真相

## 4. Target Architecture

```text
Android Compose Client
  - conversation list/workbench
  - conversation detail/timeline
  - draft/input
  - presentation-only catalog grouping

Rust Chat Engine
  - domain truth
  - typed protocol/events
  - session orchestration
  - persistence/store
  - bridge-http (local HTTP + SSE)
  - recovery anchors / catalog facade / draft truth

External Upstream
  - VCPToolBox / OpenAI-compatible provider
```

## 5. Scope

### 5.1 In Scope

- 单聊主链稳定化
- Rust typed event 与 snapshot/upsert/delta 真相收口
- Android list/detail/draft 明确拆边
- conversation catalog / recovery / resume
- draft persistence truth
- release gate / deterministic validation
- 为后续真实上游接入留出明确 seam

### 5.2 Out of Scope

- 多 Agent 群聊编排
- 真实扫码登录闭环
- 多端同步
- 完整工具调用审批系统
- 浏览器工作台产品化

## 6. Functional Requirements

### FR-1 Rust Conversation Truth

- Rust 维护 conversation / node / variant / part 的领域真相
- Android 不再以 `content + reasoning + ast` 三元组充当最终真相

### FR-2 Typed Event Stream

- Rust 对外输出稳定的 typed events
- 至少包含：
  - `conversation_snapshot`
  - `conversation_node_upsert`
  - `generation_started`
  - `generation_part_delta`
  - `generation_completed`
  - `generation_failed`

### FR-3 Recovery And Catalog

- Rust 提供事实型 catalog facade
- Android 可恢复最近可恢复会话
- `current / recent / failed` 等分组仍由客户端 presentation 决定

### FR-4 Android State Separation

- Android 至少收敛为三条状态责任：
  - `list/catalog state`
  - `detail/timeline state`
  - `draft/input state`
- 不允许“发消息导致整个页面用一坨 state 重建”

### FR-5 Draft Truth

- draft 需要可恢复
- Android 可有短暂输入缓存，但长期真相应有明确归属

### FR-6 Validation And Release

- Rust 与 Android 都要有确定性验证入口
- release gate 必须可重复执行

## 7. Quality Bar

### UX Bar

- 聊天过程连续，不出现无意义整页抖动
- 恢复旧会话时能回到正确现场
- catalog/workbench 不打断当前会话现场

### Engineering Bar

- 架构边界清晰：Rust facts vs Android presentation
- 每个主链能力有可验证 done gate
- 新增能力以小步、可回归方式推进

## 8. Readiness Verdict

### Verdict

`ready-with-gaps`

### Why

- 当前目标与技术主线已经清晰：Rust-first、单聊主链、Android thin client
- 但旧文档仍保留了过时叙事，若不显式替换，会导致任务拆分错位

### Gaps

- 群聊是否完全移出近期路线图，尚需在管理层面确认
- draft truth 的最终落点还需进一步定型
- Android `list` 状态与 `detail` 状态仍存在部分耦合

## 9. Acceptance Criteria

- 有一条稳定的单聊发送/流式接收/完成链路
- Rust 可提供可恢复的 conversation snapshot 与 catalog facts
- Android 可恢复会话并保持 scene continuity
- Android 状态至少分成 list/detail/draft 三类责任
- 验证流程可通过既有脚本稳定跑通

## 10. Linear Epics

### Epic A: Rust Truth Consolidation

目标：让 Rust 成为唯一聊天事实源。

Stories:

1. 统一 conversation/node/variant/part 模型与外部协议
2. 补齐 `generation_failed / cancelled` 等 failure semantics
3. 收敛 snapshot/upsert/delta 的真相边界
4. 明确 draft persistence 与 conversation persistence 的接口

### Epic B: Android State Refactor

目标：将 Android 从“单 ViewModel 承载一切”进一步收敛为明确状态边界。

Stories:

1. 抽离 conversation list/catalog state
2. 保持 detail/timeline reducer 只处理详情演进
3. 保持 draft/input 独立状态流
4. 清理 detail 中不应承载的 catalog presentation 逻辑

### Epic C: Recovery And Workbench

目标：让“回到现场”成为稳定产品能力。

Stories:

1. 固化 catalog facade 与 recoverable conversation 规则
2. 完成 current scene anchor / quick switch / workbench 层级
3. 为恢复链路补齐 focused message / stick-to-bottom / continuity 验证

### Epic D: Validation And Release Gate

目标：让当前 Rust + Android 主线具备稳定交付能力。

Stories:

1. 固化 Rust check/test/resume smoke
2. 固化 Android deterministic compile/test
3. 固化 release assemble/install smoke
4. 将验证脚本与 spec 验收对应起来

### Epic E: Upstream Integration Preparation

目标：为真实上游接入做好 seam，但不在本轮把全量外部问题拉进来。

Stories:

1. 定义 provider adapter 接缝
2. 确认请求/鉴权/错误映射策略
3. 识别 mock 到真实上游切换的最小验收面

## 11. Story Template

每个 Linear story 至少包含：

- Objective
- In scope
- Out of scope
- Dependencies
- Acceptance criteria
- Verification command / evidence
- Spec delta / topology delta（如有）

## 12. Recommended Next Move

1. 用本 spec 替换当前“旧 PRD 直接驱动开发”的方式
2. 先在 Linear 建 5 个 epics：A/B/C/D/E
3. 每个 epic 先拆 1 个最小 story，不并行大拆
4. 群聊、扫码、浏览器壳全部挂到 deferred backlog
