# rust-vcpmobile Linear Breakdown v1

## 1. Purpose

本文件把以下文档压缩成可推到 Linear 的交付拆分：

- `docs/product/rust-vcpmobile-prd-v1.md`
- `docs/architecture/rust-vcpmobile-capability-matrix-v1.md`
- `docs/architecture/rust-vcpmobile-tech-spec-v1.md`
- `docs/reference/external-baselines.md`

目标不是一次把所有工单都写爆，而是先建立正确的 epic topology，让开发不会再被旧 `vcpmobile` 叙事带偏。

## 2. Planning Rules

- P0 只有两条并列主线：聊天体验、接入能力
- `rib` 是聊天与配置体验基线
- `hapi` 是扫码 onboarding 协议基线
- `vcpchat` 是 agent 群聊、论坛、笔记的参考源
- `vcptoolbox` 是兼容目标，不是移动端真相源
- 能下沉 Rust 的事实，一律优先下沉 Rust
- Android 负责壳、渲染、输入和少量展示态

## 3. Delivery Order

建议按以下顺序进 Linear：

1. Foundation: Rust truth + store + protocol
2. Chat Core: 单聊主链 + rich-content parity
3. Config: provider/API + full agent editor
4. Onboarding: `VCPToolBox` QR pairing
5. Group Chat: agent 群聊
6. Release Gate: verification/release
7. Forum: P1
8. Notes: P1

不要先开 forum/notes，也不要先做大而全 UI 美化。

## 4. Epics

### Epic A: Rust Truth Foundation

目标：

- 把 Rust 固化成聊天、agent、provider、pairing 的唯一事实源

Stories:

1. A1 Conversation truth hardening
   - Objective: 固化 `Conversation -> MessageNode -> MessageVariant -> MessagePart`
   - In scope: variant selection, regenerate/edit against node truth, selected variant persistence
   - Acceptance:
     - snapshot 可表达分支会话
     - Android 不需要自己拼装“假 branch truth”
     - stream completion 后 selected variant 稳定

2. A2 SQLite store migration
   - Objective: 从 JSON store 升级到 Rust-owned SQLite store
   - In scope: conversations, nodes, parts, drafts, agents, providers, pairing sessions
   - Acceptance:
     - 主要 P0 truth 可从 SQLite 恢复
     - 旧 JSON 不再作为最终真相

3. A3 Typed event protocol freeze v1
   - Objective: 冻结第一版 HTTP/SSE app-facing 协议
   - In scope: snapshot, node upsert, generation started/delta/completed/failed, tool state events
   - Acceptance:
     - Android reducer 不依赖 ad-hoc event 猜测
     - rich-content streaming 可被稳定表达

### Epic B: Chat Core Parity

目标：

- 单聊主链达到“不弱于 rib”的 P0 水平

Stories:

1. B1 Single chat send/stream/resume
   - Objective: 稳定单聊发送、流式接收、恢复现场
   - In scope: draft send, stream lifecycle, interruption, resume anchor
   - Acceptance:
     - 单聊主链不依赖 mock echo 才能成立
     - 中断/恢复后 timeline 连续

2. B2 Rich typed parts parity floor
   - Objective: 实现 P0 必需 typed parts
   - In scope: `text`, `image`, `document`, `reasoning`, `tool`, `error`
   - Acceptance:
     - Rust truth 支持上述 part types
     - Android 能正确渲染上述内容
     - reasoning/tool 不被压平成纯文本

3. B3 Markdown/code/document ingestion
   - Objective: 对齐 `rib` 的富内容底线
   - In scope: headings, lists, tables, checkboxes, math, fenced code, document-as-prompt
   - Acceptance:
     - markdown/code 可读性不劣化
     - 附件 document 可进入 prompt transform

4. B4 Branch/edit/regenerate UX
   - Objective: 对齐 `rib` 的 branch chat 基线
   - In scope: edit user message, regenerate assistant branch, branch selection UI
   - Acceptance:
     - 会话不是单一平面 transcript
     - 用户可看见并切换分支

### Epic C: Provider And Agent Config

目标：

- 手机端能完整配置 provider 与 agent，不输 `rib`

Stories:

1. C1 Provider config system
   - Objective: 建立 Rust-owned provider config truth
   - In scope: base URL, auth, model catalog, headers, body fragments, presets, local IDs
   - Acceptance:
     - 用户可新增、编辑、切换 provider
     - 会话历史不因 endpoint 编辑而失联

2. C2 Full mobile agent editor
   - Objective: 手机端完整创建/编辑 agent
   - In scope: name, avatar, system prompt, placeholder vars, model override, tool toggles, group metadata
   - Acceptance:
     - 不需要回桌面端才能完成 agent 配置
     - 单聊与群聊都能复用同一 agent truth

3. C3 Placeholder resolution pipeline
   - Objective: 对齐 `vcptoolbox` 的 `{{}}` 管线，但由 Rust 结算
   - In scope: agent placeholders, generic placeholders, plugin/static placeholders provenance
   - Acceptance:
     - resolved prompt 可预览
     - Android 不自己做最终替换

### Epic D: VCPToolBox Onboarding

目标：

- 建立“扫完就能用”的 `VCPToolBox` 接入链路

Stories:

1. D1 Desktop QR bridge contract
   - Objective: 定义独立桌面桥接服务输出的二维码 payload
   - In scope: minimal payload, bootstrap secret, device binding, expiry
   - Acceptance:
     - payload 极小且可版本化
     - 不把长效敏感凭证直接留在二维码里

2. D2 Mobile pairing exchange
   - Objective: 手机扫码后换取可撤销 mobile session
   - In scope: bootstrap validation, short-lived token issuance, trusted device registration, resume anchor
   - Acceptance:
     - 扫码后可直接进入可用状态
     - pairing failure 能明确提示

3. D3 VCPToolBox adapter auth normalization
   - Objective: 吞掉 `vcptoolbox` auth quirks
   - In scope: bearer/basic-cookie/path-key domain split, error normalization, route shaping
   - Acceptance:
     - Android 不理解 `/admin_api` 与 `/pw=...` 细节
     - route/order quirks 不污染产品层

### Epic E: Agent Group Chat

目标：

- 落地 agent-centric 群聊，不做真人社交产品

Stories:

1. E1 Group truth and membership
   - Objective: Rust 持有 group/topic/member/turn-policy truth
   - In scope: group definition, topic history, membership, active speaker state
   - Acceptance:
     - group chat 不依赖 UI 临时状态拼起来

2. E2 Deterministic dispatch policy
   - Objective: 收敛 `vcpchat` 中可保留的规则
   - In scope: direct mention -> tags -> `@所有人` -> fallback, `invite_only`, `sequential`, `naturerandom`, anti-self-trigger
   - Acceptance:
     - `@指定某个 agent` 必然成立
     - `invite_only` 不会自动回复
     - 不使用 renderer randomness 当系统真相

3. E3 Group chat Android surface
   - Objective: 提供可用的群聊 UI
   - In scope: speaker identity, topic UI, mention composer, interrupt/redo entry points
   - Acceptance:
     - 用户始终知道是谁在回复
     - mention 成本不高于普通单聊切角色

### Epic F: Verification And Release

目标：

- 把 P0 变成可重复交付的工程主线

Stories:

1. F1 Capability-level verification map
   - Objective: 每个 P0 能力挂上明确验证
   - In scope: single chat, rich parts, agent config, QR onboarding, provider config, group chat `@agent`
   - Acceptance:
     - spec 能映射到脚本/测试/人工 smoke

2. F2 Release gate stabilization
   - Objective: 固化 Rust + Android release gate
   - In scope: check/test/smoke, deterministic build, install verification
   - Acceptance:
     - release 不是一次性手工流程

### Epic G: Forum

目标：

- 保留 `vcpchat` / `vcptoolbox` 兼容方向，但明确降级为 P1

Stories:

1. G1 Forum normalized model
   - Objective: 定义 `Thread / Reply / Board / Metadata` Rust truth
2. G2 Forum adapter compatibility
   - Objective: 对接现有 backend seam，但不继承 markdown-floor parsing

### Epic H: Notes

目标：

- 保留聊天沉淀笔记能力，先做 local-first

Stories:

1. H1 Note model and local storage
   - Objective: 定义 local-first notes truth
2. H2 Chat-to-note ingress
   - Objective: 从 chat 稳定沉淀到 note
3. H3 Backend-backed notes compatibility
   - Objective: 兼容 `vcpchat` / `vcptoolbox` notes seam

## 5. Suggested First Story Per Epic

如果要控制风险，第一轮只建这些：

1. `A1 Conversation truth hardening`
2. `B1 Single chat send/stream/resume`
3. `C1 Provider config system`
4. `D1 Desktop QR bridge contract`
5. `E1 Group truth and membership`
6. `F1 Capability-level verification map`

P1 epics 先只建 epic，不拆 story。

## 6. Story Template

每个 Linear story 建议固定字段：

- Objective
- User value
- In scope
- Out of scope
- Dependencies
- Acceptance criteria
- Verification evidence
- Spec references

## 7. Anti-Drift Notes

- 不要把旧 `vcpmobile-linear-spec-v1.md` 当成当前主线
- 不要把 `forum` 和 `notes` 提前抬成主链
- 不要把 `vcptoolbox` 的偶然 route/auth 行为直接写成产品需求
- 不要为了 Rust 纯度牺牲 `rib` 级别的聊天体验
