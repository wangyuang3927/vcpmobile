# Anchored Loop Local Usage

本项目使用全局 `anchored-loop` skill，并已从“轻脚手架”升级为“真实 loop runtime + 兼容回灌脚本”双层结构。

## 对齐新版 skill 的新增点

- runtime re-entry 现在显式保留 `current round`
- supporting-agent ingress 明确保留：
  - `slot`
  - `delta`
  - `disposition`
  - `state change`

## 当前推荐结构

### 0. 真实 loop runtime（推荐主路径）

- `scripts/loop_runner.py`

用途：

- 在 `.runtime/tasks/<task-slug>/loops/<loop-slug>/` 下维护真实 loop session
- 提供 `init / prompt / template / apply / status / close / run / reconcile / export-*`
- 让 loop 真正拥有：
  - durable session
  - best-so-far
  - per-round artifacts
  - prompt regeneration
  - stop / continue posture

典型目录：

```text
.runtime/tasks/<task-slug>/loops/<loop-slug>/
  session.json
  best.json
  prompt.txt
  rounds/
    001.json
```

推荐命令：

```bash
python3 scripts/loop_runner.py init <task-slug> <loop-slug> \
  --objective "loop 目标" \
  --why-opened "为什么开 loop" \
  --reason-to-continue "为什么还值得继续" \
  --reason-to-stop "什么情况下该停" \
  --next-external-action "loop 服务的外部动作"

python3 scripts/loop_runner.py prompt <task-slug> <loop-slug>

python3 scripts/loop_runner.py template --from-loop-task <task-slug> --from-loop-slug <loop-slug>

python3 scripts/loop_runner.py apply <task-slug> <loop-slug> /tmp/round-card.json

python3 scripts/loop_runner.py status <task-slug> <loop-slug>

python3 scripts/loop_runner.py export-best-to-meta <task-slug> <loop-slug>
python3 scripts/loop_runner.py export-best-to-documentation <task-slug> <loop-slug>

python3 scripts/loop_runner.py reconcile <task-slug> [<loop-slug>]
python3 scripts/loop_runner.py feedback-ingest <task-slug> <loop-slug> \
  --pass-slug disposition-round-2 \
  --output /tmp/feedback-round.json

# 或直接入账为下一轮
python3 scripts/loop_runner.py feedback-ingest <task-slug> <loop-slug> \
  --pass-slug disposition-round-2 \
  --apply
```

### 1. runtime 回灌脚本（兼容层）

- `scripts/anchored_loop_sync.sh`
- `scripts/anchored_loop_sync.py`

用途：兼容旧工作流，回灌 `Meta.md / Implement.md / Documentation.md / Loop.md`

注意：

- 它不是新的 loop truth
- 它不负责保存 per-round session
- 新任务优先使用 `scripts/loop_runner.py`

### 2. feedback ingest（主 runtime 的 admission 闭环）

- `scripts/loop_runner.py feedback-ingest`

用途：
- 读取 visible feedback bundle
- 将 `slot / delta / disposition` 编译为 loop-ready round card
- 可直接 `--apply` 入账到当前 loop

注意：
- 它负责编译显式 disposition，不做隐藏综合
- supporting-agent 结果现在不再只是模板文件，而能回灌成 durable state change

### 3. feedback pass scaffold（兼容层）

- `scripts/feedback_pass_local.py`

用途：生成本项目的 supporting-agent 可见反馈骨架，不做隐藏综合。

### 4. 旧本地 loop runner（legacy helper）

- `scripts/anchored_loop_run.py`

用途：给旧 anchored-loop markdown 工作流提供命令面。

当前状态：

- 可继续用于已有 markdown-only loop
- 但它不是新的主 runtime
- 它更像“task root markdown patch helper”，而不是独立 loop session 运行器

示例：

```bash
python3 scripts/anchored_loop_run.py status .runtime/tasks/rikkahub-rust-redesign

python3 scripts/anchored_loop_run.py next-round .runtime/tasks/rikkahub-rust-redesign \
  --next-action "下一轮外部动作" \
  --resume-action "恢复入口" \
  --loop-next-action "Loop next external action"

python3 scripts/anchored_loop_run.py checkpoint .runtime/tasks/rikkahub-rust-redesign \
  --delta "本轮真实增益" \
  --implement-status "当前实施状态" \
  --doc-status "当前文档状态" \
  --card-loop "21" \
  --card-invariant "本轮不变量" \
  --card-next-move "下一刀"

python3 scripts/anchored_loop_run.py feedback-open .runtime/tasks/rikkahub-rust-redesign \
  --slot "目录长期容器形态" \
  --slot "大规模会话搜索/排序" \
  --role "ui-structure" \
  --role "data-shape"

python3 scripts/anchored_loop_run.py feedback-status .runtime/tasks/rikkahub-rust-redesign --round 21

python3 scripts/anchored_loop_run.py block .runtime/tasks/rikkahub-rust-redesign \
  --blocker-type "external" \
  --blocker-reason "等待上游接口" \
  --unblock-condition "接口文档到位" \
  --resume-action "收到文档后恢复实现"

python3 scripts/anchored_loop_run.py close-round .runtime/tasks/rikkahub-rust-redesign \
  --decision continue \
  --next-action "下一轮动作" \
  --resume-action "恢复入口"

python3 scripts/anchored_loop_run.py best-card .runtime/tasks/rikkahub-rust-redesign \
  --card-loop "22" \
  --card-invariant "新的不变量" \
  --card-next-move "下一刀"
```

示例：

```bash
python3 scripts/feedback_pass_local.py   --output-dir .runtime/tasks/rikkahub-rust-redesign/feedback-pass/round-09   --slot "snapshot multi-node 承载"   --slot "auto-scroll trigger"   --role "detail-shape"   --role "scroll-risk"
```

生成内容：
- `open-slots.json`
- `feedback-delta.*.json`
- `feedback-disposition.json`

## 原则

- `loop_runner.py` 是 loop runtime
- task 根目录的 `Meta.md / Documentation.md` 仍是任务级真相
- loop state 从属于 task，不与 task 竞争
- supporting-agent 入口仍保持 `slot -> delta -> disposition -> state change` 可见
- 自动化只负责 re-entry，不替代 judgment

换句话说：

- 不让 transcript 膨胀为上下文雪球
- 不让 helper 变成 shadow runtime
- 不让 driver 变成第二个 owner
