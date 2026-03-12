# vcpmobile

Rust-first Android AI client for `rust-vcpmobile`.

## What This Repository Is

`vcpmobile` 的目标不是再做一个“能跑的移动端聊天壳”，而是做一个：

- 以聊天为中心的 Android AI 客户端
- `Rust-first`
- `local-first`
- 对标 `rib` 的聊天体验
- 重点接入 `VCPToolBox`

当前产品主线是：

- Rust chat engine 作为真相源
- Android Compose 作为原生薄客户端

## Current Repository Status

这个仓库目前处于重构过渡期。

同时存在两类内容：

- 新主线：`rust-engine/`、`android-compose/`、`docs/product/`、`docs/architecture/`
- 旧遗留：`src/`、`public/`、`package.json`、`vite.config.js`、旧 `android/`

默认开发方向是新主线。

旧 Vue/Capacitor 相关目录只作为参考、迁移和对照，不是默认实现落点。

## Product Priorities

P0 主能力：

- 单聊
- agent 群聊
- 扫码接入 `VCPToolBox`
- 通用 API 接入
- 完整 agent 配置系统
- 不弱于 `rib` 的富内容聊天体验

P1：

- 论坛
- 笔记

复杂工作台不是当前主线。

## Primary Directories

- `rust-engine/`: Rust 领域模型、store、session、protocol、bridge
- `android-compose/`: Android Compose 客户端
- `docs/product/`: PRD、产品拆解
- `docs/architecture/`: 技术 spec、能力矩阵、执行计划
- `docs/process/`: 本地流程和验证说明
- `scripts/`: 脚本与辅助验证

## Canonical Documents

开始非小改动前，先看这些文档：

- `docs/product/rust-vcpmobile-prd-v1.md`
- `docs/architecture/rust-vcpmobile-tech-spec-v1.md`
- `docs/architecture/rust-chat-blueprint-v1.md`
- `docs/architecture/vcpmobile-linear-spec-v1.md`
- `docs/reference/external-baselines.md`
- `rust-engine/README.md`

## Architecture Posture

### Rust owns truth

优先放到 Rust 的内容：

- conversation / message / agent 领域模型
- protocol 和事件形状
- store / snapshot / recoverability
- catalog facts / projection
- 与 `VCPToolBox` 和通用 API 的接入编排

### Android Compose owns presentation

Android Compose 主要负责：

- UI 渲染
- 页面交互
- 本地展示派生
- 与 Rust bridge 的接线

默认不要再让 Android 或旧前端承担业务真相源。

## Local Validation

### Rust

```bash
cd rust-engine
cargo check
cargo test
cargo run -p vcpmobile-bridge-http
```

常用本地接口：

- `GET http://127.0.0.1:4001/health`
- `GET http://127.0.0.1:4001/api/chat/conversations`
- `GET http://127.0.0.1:4001/api/chat/catalog`
- `POST http://127.0.0.1:4001/api/chat`

### Android Compose

```bash
cd android-compose
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Planning and Delivery

本仓库默认走：

`spec -> issue -> implementation -> validation`

如果需求边界不清楚，先补 PRD/spec，再拆 issue，不直接开写。

当前自动化和 Linear 规划主线围绕：

- Linear project: `rust-vcp-ec09a1177448`
- Symphony base branch: `symphony-local-base`

## Notes

- 当前根目录还有历史遗留内容，这是现实，不代表主线仍是旧方案。
- 不要被根目录的旧前端文件误导，当前主线是 Rust-first rewrite。
- `AGENTS.md` 定义了本仓库更细的执行规则，开始动手前应先读。
