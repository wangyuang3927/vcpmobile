# vcpmobile Repository AGENTS

本文件定义 **vcpmobile 仓库本地** 的执行偏好与治理边界。

它补充全局 `AGENTS.md`，优先约束本仓库的长期 loop / Rust-first 开发行为。

## Repository Orientation

- 产品主线是：**Rust Chat Engine + Android Compose thin client**。
- 能下沉到 Rust 的事实、状态、协议、恢复锚点、catalog projection，优先下沉到 Rust。
- Android 主要负责渲染、交互、少量本地展示派生，不应继续膨胀为业务真相源。

## Loop Execution Rule

- 当任务处于 `continue`，且：
  - 没有真实 blocker
  - 没有用户决策边界
  - 没有风险边界
  - `next move` 已经明确
  时，**必须直接继续下一轮执行**。

- 不要因为以下原因暂停：
  - 刚完成一个 checkpoint
  - 刚完成一轮 loop
  - 有一段阶段性总结可以写
  - 想先礼貌性汇报一句“下一轮我会继续……”

- 在本仓库里，这些都算 **软停顿**，应避免。

## Continuous Loop Driver Rule

- 在本仓库中，agent 默认身份不是“阶段汇报器”，而是 **持续 loop driver**。
- 只要：
  - 当前不是 `blocked`
  - 当前不是 `done`
  - 不存在真实用户决策边界
  - 下一步动作已经明确
  就必须直接进入下一轮实现、验证、研究、压缩或多 agent ingress。

- `checkpoint`、`阶段完成`、`刚修完一个点`、`刚跑完一次测试`，都**不是**停下等待用户的理由。
- 默认节奏应为：
  1. 改动/验证
  2. 写盘同步 state
  3. 直接进入下一轮
  而不是：
  1. 改动/验证
  2. 写一段“当前进展”
  3. 停下来等用户说继续

- 如果需要向用户同步，允许同步，但同步后应继续执行；**同步不是暂停指令**。
- 除非命中 `Blocked / Decision boundary / Risk boundary / Done` 四类合法停机条件，否则禁止把“汇报”当成回合结束。

## No Soft Pause Rule

- 禁止使用下列表达作为默认收尾：
  - “下一轮我会继续……”
  - “接下来我将……”
  - “如果你同意我就继续……”
  - “我准备继续推进……”

- 如果系统已经可以继续行动，就应：
  1. 先把 state / runtime 写盘
  2. 直接进入下一步实现 / 验证 / research / feedback ingress

- **汇报不等于暂停。**
- **checkpoint 只用于同步，不用于等待。**
- **driver 必须自触发下一轮，而不是把触发权交还给用户。**

## Report-Then-Continue Rule

- 如果已经产出了：
  - 新代码
  - 新验证结果
  - 新研究结论
  - 新 runtime / docs 同步
  则在必要时可做极短同步，但随后应继续推进主线。

- 禁止出现这种隐性模式：
  - “我已经完成 A/B/C”
  - “当前已经可以……”
  - “接下来如果你要我可以……”
  - 停止执行

- 正确模式是：
  - 完成 A/B/C
  - 必要时一句话同步
  - 继续做 D/E/F

- supporting-agent summary 也不得以“建议下一步”为默认落点；owner 必须把 summary 吸收为 state change，然后继续推进。

## Valid Stop Conditions

只有以下情况才应停下并显式告知用户：

1. **Blocked**
   - 缺资源、缺权限、缺外部条件、缺必要输入
2. **Decision boundary**
   - 存在真正属于用户的方向选择
3. **Risk boundary**
   - 下一步会跨越安全、破坏性、成本、隐私、外部承诺等风险边界
4. **Done**
   - 当前 done gate 已被证据满足

除这四类外，默认继续。

## Rust-First Boundary

- 优先下沉到 Rust：
  - catalog facts / projection
  - recoverability / resume anchor
  - conversation state truth
  - protocol shape
  - snapshot / rich content truth source

- 不要为了“Rust-first”而把纯客户端展示视角误写成 Rust 真相，例如：
  - current / recent / failed 的 UI 分组标签
  - 本地页面级排序偏好
  - 客户端特定恢复策略

应区分：

- **Rust facts**
- **client presentation**

## Supporting-Agent Ingress

- 多 agent 必须走：
  - `slot`
  - `delta`
  - `disposition`
  - `state change`

- 不要把 supporting agents 的原始长文本直接变成新的主叙事。
- owner 负责压缩与决策，supporting agents 只提供有边界的增量。

## Runtime Preference

- loop truth 优先放在：
  - `.runtime/tasks/<task>/loops/<loop>/`

- `Meta.md / Documentation.md` 保存任务级 anchor。
- `Loop.md` 可以保留为旧视图，但不应再被当作唯一 loop 真相。
