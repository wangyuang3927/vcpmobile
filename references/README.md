# Reference Snapshots

本目录存放外部参考项目的**裁剪副本**，用于：

- 给 `rust-vcpmobile` 的 spec / issue / agent 提供仓库内可访问参考
- 避免依赖本机绝对路径
- 避免让 Symphony 必须访问 workspace 外目录

注意：

- 这里不是完整镜像，而是为当前产品设计和实现裁剪过的参考快照
- 默认不保留 `.git`、构建产物、大依赖、无关资产
- 真正的“原始上游仓库”仍在你本机外部目录里

当前参考：

- `rib/`
- `hapi/`
- `vcpchat/`

如果未来不再需要，可整体删除本目录。
