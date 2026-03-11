# Harness Bootstrap Runtime（vcpmobile）

本项目已通过 `harness-bootstrap` 编译出本地运行面：

- Hook 注册：`.codex/config.toml`
- Hook 脚本：`.harness/hooks/*.py`
- 工作流：`.harness/workflow.md`
- 意图映射：`PRD/intent/registry.csv`

## 当前机械化门禁

1. `subagent_stop` 执行 Quality Gate：
   - `cd hub && bun test`
   - `cd hub && bun run typecheck`
   - `cd android-compose && ./gradlew :app:compileDebugKotlin --no-daemon`
2. Debug 失败预算：连续 > 3 次自动阻断
3. Intent Trace Gate：改动文件必须匹配 `registry.csv` 的 active intent `files`

## 使用建议

- 子代理 prompt 显式写：`stage: plan|implement|check|debug|finish`
- 新增目录改动前，先在 `registry.csv` 补充对应 intent
- 如需调高/调低门禁强度，优先修改 `.harness/workflow.md` 与 `quality_gate.py`
