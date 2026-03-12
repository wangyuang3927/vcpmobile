# GitHub Delivery

本文件定义 `vcpmobile` 仓库内的最小 GitHub 交付判定与提交流程。

目标：

- 避免把 `gh auth status` 单独当作“GitHub 阻塞”的充分证据。
- 在 `git` / `gh` / 网络状态不完全一致时，优先用真实读写探针判断是否可交付。
- 给 agent 一条最短的 push / PR / Linear 回写路径。

## Core Rule

`gh auth status` 只是诊断信号，不是最终真相。

在宣布 GitHub 阻塞前，必须至少完成下面三项探针：

1. `gh repo view Andyduck-ops/vcpmobile --json name,viewerPermission`
2. `git ls-remote origin HEAD`
3. `git push --dry-run -u origin HEAD:<branch-name>`

判定规则：

- 如果 1、2、3 都成功，则 **GitHub 交付可用**，继续真实 push / PR，不要把 issue 留在 blocker 循环里。
- 如果 1 成功、2 成功、3 失败，则优先排查分支、远端、权限或保护规则，不要直接归因为 `gh auth`。
- 如果 1 失败但 2、3 成功，则 `gh` API 路径有问题，但 `git` 交付仍可用；优先完成 push，再单独处理 PR 创建。
- 如果 1、2、3 都失败，才可将 GitHub 视为真实 blocker。

## Required Probe Capture

宣布 GitHub 阻塞前，至少记录以下输出：

```bash
gh auth status -h github.com || true
gh repo view Andyduck-ops/vcpmobile --json name,viewerPermission || true
git remote -v
git ls-remote origin HEAD || true
git push --dry-run -u origin HEAD:<branch-name> || true
getent hosts github.com || true
```

不要只贴 `gh auth status`。

## Standard Delivery Flow

在 workspace 中：

```bash
git status --short
git branch --show-current
git push -u origin HEAD:<branch-name>
gh pr create \
  -R Andyduck-ops/vcpmobile \
  --base symphony-local-base \
  --head <branch-name> \
  --title "<issue>: <title>" \
  --body-file <pr-body-file>
```

## PR Creation Guidance

- 优先使用仓库内已有的 PR 草稿文件，例如：
  - `.runtime/tasks/<task>/PR.md`
  - `.runtime/tasks/<task>/artifacts/pr-body.md`
- PR 创建成功后，立即把 PR URL 写回 Linear。
- PR 创建成功后，issue 应进入 `Human Review`，而不是继续停在 `In Progress`。

## Blocker Boundary

只有以下情况才算真实 GitHub blocker：

- `gh repo view` 无法访问目标仓库，且 `git ls-remote` / `git push --dry-run` 也失败
- `origin` 指向错误仓库且无法修正
- 当前分支或工作区状态不允许安全 push
- GitHub 明确返回不可恢复的权限或策略错误

以下情况 **不够** 构成 blocker：

- 只有 `gh auth status` 报错
- 只有某一次 DNS 探针异常，但读写探针成功
- 旧的 blocker note 与当前环境不一致

## Linear Handoff Rule

如果本地实现和验证已经完成，但 GitHub blocker 尚未被最新探针证实，不要继续重复写 blocker 附件。

优先顺序应为：

1. 运行最小探针
2. 如果可用，直接 push / PR
3. 把 PR URL 回写到 Linear
4. 把 issue 移到 `Human Review`

不要在同一个 issue 上无限重复“GitHub delivery recheck”而不推进状态。
