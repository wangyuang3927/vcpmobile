# rust-vcpmobile Product PRD v1

## 1. Product Summary

`rust-vcpmobile` 是一个以聊天为中心的 Android AI 客户端。

它的目标不是做一个“能跑的移动端聊天壳”，而是成为：

- 比 `rib` 更适合 VCP 工作流的 Android AI 客户端
- 一个能低摩擦接入本地 AI 系统，尤其是 `VCPToolBox` 的移动终端
- 一个你愿意日常主要使用，而不是先回到 `rib` 的主力聊天工具

## 2. Mission

让手机成为你最强的 AI 聊天终端，并把本地 AI 能力自然带到身上。

## 3. Product Positioning

### 3.1 Product Type

- Android AI chat client
- local-first
- VCP workflow oriented

### 3.2 Product Baseline

- 体验与信息架构基线：`rib`
- 扫码接入协议基线：`hapi`
- agent 群聊、论坛、笔记参考：`vcpchat`
- 重点适配后端：`vcptoolbox`

### 3.3 Product Character

以 `rib` 的成熟工具体验为基线，但长成 VCP 自己的表达，而不是做 `rib` 的换皮复刻。

## 4. Primary User

### 4.1 Primary User

- 你自己这样的重度 AI 使用者

### 4.2 Primary Usage Context

- 电脑端运行本地 AI 系统，尤其是 `VCPToolBox`
- 手机端需要随时接入、继续对话、管理 agent、做多 agent 协作
- 使用者希望长期把对话、角色、论坛讨论、笔记沉淀留在同一产品体系里

## 5. Core Product Value

### 5.1 Dual Priority

本产品有两个并列第一优先级：

- 聊天体验
- 接入能力

### 5.2 Core Value Statements

- 至少不能比 `rib` 难用
- 聊天过程必须顺滑、可信、持续可用，不能像技术 demo
- 扫码接入必须做到扫完就能用，几乎没有学习成本，而且稳定
- 本地 AI 能力应被自然带到手机上，而不是要求用户重新配置一套云端系统

## 6. Product Principles

### 6.1 Product Principles

- Chat-first, not workspace-first
- Local-first, sync-later
- Controlled multi-agent collaboration, not generic social chat
- Reference strong products, but do not collapse into imitation

### 6.2 Technical Product Principle

- 能 Rust 就 Rust
- Android 尽量保持极薄壳
- 只有在体验明显变差或实现代价明显失控时，才允许退回其他技术

## 7. Core Modules

### 7.1 P0 Modules

- 单聊
- agent 群聊
- 扫码接入 `VCPToolBox`
- 通用 API 接入
- 完整 agent 配置系统
- 对标 `rib` 的聊天体验与富内容能力

### 7.2 P1 Modules

- 论坛
- 笔记

### 7.3 Non-goals For First Release

- 复杂工作台
- 多人真人社交产品
- 先做跨端同步再做本地可用
- 为了技术纯度牺牲体验

## 8. P0 Definition

### 8.1 Single Chat

用户可以用一个角色或模型进行稳定、顺滑、可持续的单聊。

最低要求：

- 聊天流程不比 `rib` 难用
- 富内容能力不低于 `rib`
- 本地优先保存会话

### 8.2 Agent Group Chat

群聊的本质是多 agent 协作，不是多人真人社交。

P0 必须成立的能力：

- 用户可以 `@指定某个 agent 回复`
- 多个 agent 可以在同一会话中参与
- agent 可接力讨论，但这是次于 `@指定` 的能力

### 8.3 QR Onboarding For VCPToolBox

扫码接入由三部分组成：

- Android 客户端
- 独立的本地二维码桥接/接入服务
- `VCPToolBox`

P0 目标不是“理论上能接入”，而是：

- 扫码即接入
- 几乎没有学习成本
- 稳定可用

协议思路参考 `hapi`。

### 8.4 API Access

通用 API 接入与 `VCPToolBox` 接入并列，都是 P0 主能力。

设计基线参考 `rib`，意味着：

- 用户可配置 API
- 用户可切换接入方式
- 用户不被锁死在单一后端

### 8.5 Full Agent Configuration

手机端就能完整创建和编辑 agent。

P0 中 agent 配置系统至少应覆盖：

- 名称
- 头像
- system prompt
- 提示词变量与 `{{}}` 占位
- 在单聊与 agent 群聊中的调用

具体字段与交互基线参考 `rib`，并兼容 `VCPToolBox` 的占位与提示词体系。

### 8.6 Rich Chat Experience

`rib` 有什么，`rust-vcpmobile` 的 P0 聊天能力就应该至少有什么。

这意味着 P0 的富内容聊天不按“最低能发文本”定义，而按“对标成熟产品”定义。

## 9. P1 Definition

### 9.1 Forum

论坛不是本次主线，但必须在产品规划中保留。

策略：

- 以 `vcp` / `vcpchat` 现有设计为准
- 作为聊天之外的二级模块
- 优先级明显低于聊天和接入

### 9.2 Notes

笔记同样属于二级模块。

目标：

- 支持从聊天沉淀笔记
- 也支持独立笔记能力

参考基线来自 `vcpchat`。

## 10. Core User Flows

### Flow 1: First Useful Run

1. 用户安装应用
2. 用户可以直接开始本地可用的体验
3. 用户可选择扫码接入 `VCPToolBox`
4. 扫码后应快速进入可用状态，而不是继续进行复杂配置

### Flow 2: Configure An Agent On Phone

1. 用户在手机上新建一个 agent
2. 设置名字、头像、system prompt
3. 配置 `{{}}` 变量或模板
4. 直接把这个 agent 用于单聊或群聊

### Flow 3: Controlled Multi-agent Chat

1. 用户进入 agent 群聊
2. 通过 `@agent` 指定某个 agent 回复
3. 其他 agent 可继续参与接力
4. 用户始终知道是谁在说话、为什么这样回复

### Flow 4: Continue Local AI On Phone

1. 用户在电脑端运行 `VCPToolBox`
2. 本地桥接服务生成二维码
3. 手机扫码接入
4. 用户在手机端继续完成主要聊天工作，而不是只做远程控制

## 11. Information Architecture Direction

产品信息架构与首屏策略以 `rib` 为设计基线，不在 PRD v1 中自行发明新的壳结构。

当前约束：

- 首屏和主导航不应偏离 `rib` 的成熟工具逻辑
- 复杂工作台不是首发重点
- 聊天主链必须始终处于最高优先级

## 12. Success Criteria

### 12.1 P0 Success Statement

你已经愿意日常主要使用 `rust-vcpmobile` 完成：

- AI 聊天
- agent 群聊
- agent 配置
- 扫码接入
- API 接入

而不是优先回到 `rib`。

### 12.2 Product Success Checks

- 聊天体验至少不弱于 `rib`
- 扫码接入足够低摩擦且稳定
- agent 群聊中的 `@指定 agent` 能力成立
- 手机端可以独立完成完整 agent 配置
- 本地优先的会话与配置管理可被长期使用

## 13. Out of Scope For This PRD

- 详细技术架构
- Rust crate 切分
- Android 与 Rust 的桥接实现细节
- 同步协议设计
- Linear issue 级别拆分

这些内容属于下一阶段技术 spec。

## 14. Risks

- 如果只追求 Rust 纯度而不守住体验，产品会变成技术正确但不好用
- 如果只对标 `rib` 而不处理 VCP 独有能力，产品会失去存在理由
- 如果过早拉高论坛与笔记优先级，会削弱聊天主链和接入主线
- 如果扫码接入变成“能连上但不好用”，核心卖点会失效

## 15. Open Questions

- `rib` 的富内容、角色配置、API 接入细节还需要进一步做代码级盘点
- `vcpchat` 的 agent 群聊机制需要进一步抽样，避免只按想象写需求
- 论坛与笔记的继承边界需要和 `vcp` / `vcpchat` 做更细映射

## 16. Readiness Verdict

### Verdict

`ready-with-gaps`

### Why

- 产品目标、优先级、P0/P1、参考源、核心底线已经清晰
- 已经足够进入下一轮技术 spec

### Visible Gaps

- 还缺少对 `rib / vcpchat` 代码层面的更细盘点
- 还缺少更明确的信息架构与页面级模块定义
- 还缺少论坛与笔记的继承边界说明

## 17. Recommended Next Move

1. 基于这份 PRD 补写技术 spec
2. 对 `rib / vcpchat / hapi / vcptoolbox` 做代码级能力盘点
3. 再把产品 PRD 与技术 spec 合并为可拆 Linear 的执行基线
