# External Baselines

本文件把 `rib / hapi / vcpchat / vcptoolbox` 收敛为 `rust-vcpmobile` 的仓库内参考基线。

它不试图复制外部仓库实现，而是回答三个问题：

1. 哪些东西值得继承
2. 哪些东西不要照搬
3. 在 `rust-vcpmobile` 里应该落到哪里

## 1. rib

定位：

- 聊天体验与配置体验基线

应该继承：

- chat-first 信息架构
- conversation tree / branch selection
- rich typed parts
- reasoning / tool 作为一等聊天内容
- provider 配置深度
- assistant / agent 编辑面的完整性
- 本地优先的聊天使用体验

不要直接照搬：

- Kotlin/Android 内部数据结构
- 任何只在 `rib` 内部成立的本地 API 形状
- 未经代码验证的推断性能力

在本项目中的落点：

- 产品体验基线：`docs/product/rust-vcpmobile-prd-v1.md`
- 技术对齐：`docs/architecture/rust-vcpmobile-tech-spec-v1.md`
- 具体实现：优先落到 `rust-engine/`，再由 `android-compose/` 消费
- 代码级参考快照：`references/rib/`

## 2. hapi

定位：

- 扫码 onboarding / bootstrap 协议基线

应该继承：

- 最小二维码 payload
- bootstrap secret -> short-lived mobile token 的思路
- namespace isolation
- 适合移动端的 REST/SSE 状态传输

不要直接照搬：

- 长期敏感凭证留在 URL 或本地存储
- 过强的终端机控制假设

在本项目中的落点：

- QR contract / pairing spec
- `VCPToolBox` onboarding 相关 issue
- 后续桌面桥接和移动 pairing 协议设计
- 代码级参考快照：`references/hapi/`

## 3. vcpchat

定位：

- agent 群聊、论坛、笔记的交互参考

应该继承：

- `@agent` 作为第一控制原语
- 群聊中的 speaker identity 感知
- 论坛与笔记作为聊天外二级模块的产品分层

不要直接照搬：

- 基于旧渲染器时代形成的随机行为
- 不清晰的存储真相或弱结构历史模型

在本项目中的落点：

- 群聊交互规则
- 论坛 / 笔记模块边界
- agent runtime message metadata
- 代码级参考快照：`references/vcpchat/`

## 4. vcptoolbox

定位：

- 核心兼容目标与后端能力承接面

应该继承：

- VCP 工作流下的后端适配价值
- prompt / config / placeholder 的兼容方向
- adapter 层对 host/provider quirks 的吸收

不要直接照搬：

- 让移动端直接依赖后端内部 quirks
- 把服务端实现细节泄漏成 Android 侧真相

在本项目中的落点：

- Rust adapter / provider config / onboarding path
- `VCPToolBox` 兼容层
- provider/API 并列接入的产品主线

## 5. Reference-to-Implementation Rule

外部参考进入本仓库时，必须被转写成下面三种之一：

- 产品原则
- 技术边界
- issue / acceptance criteria

不要只保留一句：

- “参考 rib”
- “参考 hapi”
- “看 vcpchat”

这种写法对人类记忆有帮助，但对 agent 不够可执行。

## 6. Citation Rule

以后在本仓库内写需求、spec、issue 时：

- 优先引用本文件或 `docs/reference/README.md`
- 再引用当前仓库里的产品/架构文档
- 不默认引用本机绝对路径

推荐写法：

- `docs/reference/external-baselines.md`
- `docs/product/rust-vcpmobile-prd-v1.md`
- `docs/architecture/rust-vcpmobile-tech-spec-v1.md`
- `references/rib/README.md`
- `references/hapi/README.md`
- `references/vcpchat/README.md`
