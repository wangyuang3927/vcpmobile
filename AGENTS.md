# vcpmobile Repository AGENTS

本文件定义 `vcpmobile` 仓库的本地开发规则。

目标不是抽象治理，而是让 agent 一进仓库就知道：

- 现在主线是什么
- 哪些目录该碰，哪些目录先别碰
- 需求、spec、实现、验证应该怎么走

## 1. Repository Reality

这个仓库目前处于重构过渡期，不是干净的新仓库。

同时存在两套东西：

- 新主线：`rust-engine/` + `android-compose/`
- 旧遗留：`src/`、`public/`、`package.json`、`vite.config.js`、旧 `android/`

默认判断：

- **新主线是真正要继续建设的产品**
- **旧遗留只用于参考、迁移、对照，不作为默认实现落点**

如果任务没有明确要求，默认不要继续扩写旧 Vue/Capacitor 方案。

## 2. Product North Star

产品定位：

- 以聊天为中心的 Android AI 客户端
- `Rust-first`
- `local-first`
- 对标 `rib` 的聊天体验
- 重点接入 `VCPToolBox`

P0 主能力：

- 单聊
- agent 群聊
- 扫码接入 `VCPToolBox`
- 通用 API 接入
- 完整 agent 配置系统
- 不弱于 `rib` 的聊天富内容能力

P1：

- 论坛
- 笔记

复杂工作台不是当前主线。

## 3. Canonical Sources

做非小改动前，优先读这些文件：

- `docs/product/rust-vcpmobile-prd-v1.md`
- `docs/architecture/rust-vcpmobile-tech-spec-v1.md`
- `docs/architecture/rust-chat-blueprint-v1.md`
- `docs/architecture/vcpmobile-linear-spec-v1.md`
- `docs/reference/external-baselines.md`
- `rust-engine/README.md`

如果这些文档与代码冲突：

- 先确认代码现状
- 再判断是文档过期还是实现跑偏
- 不要直接凭记忆实现

## 4. Active vs Legacy Areas

默认优先操作这些目录：

- `rust-engine/`
- `android-compose/`
- `docs/product/`
- `docs/architecture/`
- `docs/process/`
- `PRD/`
- `scripts/`

默认视为遗留或次级区域：

- `src/`
- `public/`
- `package.json`
- `vite.config.js`
- `index.html`
- `android/`
- `hub/`
- 各类历史 release 目录和 zip

规则：

- 要做新能力，先落在新主线
- 要借鉴旧逻辑，可以读，但不要默认在旧目录继续写
- 如果确实需要迁移旧实现，必须显式说明迁移目标和淘汰边界

## 5. Architecture Boundary

### Rust owns truth

以下内容优先下沉到 Rust：

- conversation / message / agent 的领域模型
- 聊天事件协议
- store / snapshot / recoverability
- catalog facts / projection
- 与 `VCPToolBox` 或通用 API 的接入编排
- agent 群聊的执行状态与路由事实

### Android Compose owns presentation

Android 主要负责：

- Compose UI
- 页面状态
- 交互编排
- 本地展示派生
- 与 Rust bridge 的接线

不要把以下内容继续堆在 Android 里当真相源：

- 会话真实状态
- 协议事实
- 恢复锚点
- catalog 真相
- agent 编排事实

### Thin-client exception rule

如果某个能力放进 Rust 会显著拖慢体验，或者明显不值得，其它实现可以接受。

但默认要先证明：

- 为什么 Rust 方案不合适
- 为什么这部分确实只是客户端视角，而不是系统真相

## 6. Current Implementation Direction

当前推荐路线：

1. 先把 Rust chat engine 做成稳定真相源
2. 再让 Android Compose 对接 Rust bridge
3. 再逐步吞掉旧实现中的可复用体验和交互

不要反过来做成：

1. 先在 Android 或旧前端里堆业务
2. 以后再想办法迁到 Rust

那会继续制造第二套真相。

## 7. Spec-First Delivery

本仓库默认走 `spec -> issue -> implementation -> validation`。

### 需求与 spec

当需求不清晰、边界模糊、拆分不稳时：

- 先整理 PRD/spec
- 再拆 issue
- 不要直接开写

### 任务拆分

默认把任务拆成：

- 可执行
- 可验证
- 原子化
- 能独立进 review

如果任务大到无法明确 done gate，说明拆分还不够。

### 实现顺序

对聊天主线能力，默认顺序：

1. domain / protocol
2. store / projection / bridge
3. Android integration
4. UI polish

## 8. Linear and Symphony

当前规划与自动化默认围绕 Linear 项目：

- `rust-vcp-ec09a1177448`

如果在做 issue 流转或自动开发，遵循：

- 小步推进
- 一个 issue 一个清晰目标
- 先验证再进 review

当前自动化基线分支是：

- `symphony-local-base`

如果使用 Symphony：

- workspace 从本地干净基线起
- issue 分支对 `symphony-local-base` 开 PR
- 不要默认直接对旧 `main` 开 PR
- GitHub 交付探针与最短提交流程以 `docs/process/gh-delivery.md` 为准
- issue 引用写法以 `docs/process/symphony-issue-authoring.md` 为准

## 9. Validation Rules

改动后至少跑与范围匹配的验证。

Rust 相关优先：

```bash
cd rust-engine
cargo check
cargo test
```

Android Compose 相关优先：

```bash
cd android-compose
./gradlew :app:assembleDebug
```

如果只改文档或纯 spec，可以不跑编译，但要明确说明。

如果只改了局部，也要跑最能证明该局部成立的最小验证，不要完全跳过。

## 10. Secrets and Local Artifacts

以下内容不要提交，除非用户明确要求：

- `.codex/`
- `.harness/`
- `.runtime/`
- `android-compose/local.properties`
- `android-compose/keystore.properties`
- `android-compose/signing/`
- `*.jks`
- `*.keystore`
- 构建产物
- release 包和临时导出物

如果要整理工作树，优先用“隔离基线”方案，不要直接删除用户当前脏工作。

## 11. Documentation Rules

以下情况要同步更新文档：

- 产品边界变化
- 协议变化
- domain model 变化
- store / recovery 机制变化
- onboarding / QR 接入流程变化
- 外部参考基线发生重解释

优先更新：

- `docs/product/`
- `docs/architecture/`
- `docs/reference/`
- 必要时更新 `README.md`

不要让文档长期停留在旧 Vue 模板状态。

## 12. Skills Routing

遇到这些情况时，优先使用对应 skill：

- 需求分析：`analyze`
- spec 是否够落地：`spec-readiness`
- 长任务与状态落盘：`self-manager`
- Linear 读写：`linear`
- Notion spec 拆 implementation / issue：`notion-spec-to-implementation`

如果用户明确点名 skill，按用户要求执行。

## 13. Communication Rule

与用户协作时：

- 直接
- 简洁
- 面向执行

默认不要反复回灌抽象总结。

只有在以下情况下才停下来问用户：

- 真正的方向选择
- 破坏性操作
- 外部权限或资源缺失
- 需求边界无法安全假设

除此之外，默认继续推进。
