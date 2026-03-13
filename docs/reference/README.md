# External Reference Index

本目录用于承接 `rust-vcpmobile` 的外部产品与协议参考。

目的：

- 不让 issue / spec 依赖你本机的绝对路径
- 不让 agent 在执行时必须访问仓库外目录
- 把 `rib / hapi / vcpchat / vcptoolbox` 的参考价值收敛成仓库内可消费文档

使用规则：

- 需求、spec、issue 中引用外部基线时，优先引用本目录
- 不要只写类似 `/home/eric/.../rib` 这种本机路径
- 外部仓库可以作为人工深挖来源，但当前仓库的 agent 默认应以本目录为第一入口

当前文档：

- `external-baselines.md`

当前代码快照入口：

- `references/README.md`
- `references/rib/README.md`
- `references/hapi/README.md`
- `references/vcpchat/README.md`

后续如果需要更细，可以继续拆成：

- `rib.md`
- `hapi.md`
- `vcpchat.md`
- `vcptoolbox.md`

但在没有明确压力前，先保持一个统一入口，避免再长出第二套文档体系。
