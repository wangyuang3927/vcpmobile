# rust-engine

vcpmobile 的 Rust 聊天引擎目录。

## 当前内容

- `crates/domain`：领域模型（Conversation / MessageNode / MessageVariant / MessagePart）
- `crates/protocol`：App ↔ Engine typed event 协议
- `crates/store`：Rust 文件持久化 store（最小真实实现）
- `crates/session`：会话引擎（创建会话 / 保存消息 / 产出 snapshot + generation events）
- `crates/bridge-http`：本地 HTTP + SSE 桥接

## 本地验证

```bash
cd rust-engine
cargo check
cargo run -p vcpmobile-bridge-http
```

然后访问：

- `GET http://127.0.0.1:4001/health`
- `GET http://127.0.0.1:4001/api/chat/demo`
- `GET http://127.0.0.1:4001/api/chat/conversations`
- `GET http://127.0.0.1:4001/api/chat/catalog`
- `POST http://127.0.0.1:4001/api/chat`
- `GET http://127.0.0.1:4001/api/chat/stream/<conversation_id>`

也可以通过环境变量覆盖：

```bash
HOST=127.0.0.1 PORT=4001 cargo run -p vcpmobile-bridge-http
```

## 当前定位

这已经不是纯 demo 架构：

- `POST /api/chat` 会从 Android 当前请求形状中读取最后一条 user message
- Rust `session + store` 会生成并持久化 conversation / nodes
- SSE 会返回 typed `conversation_snapshot / generation_started / generation_part_delta / generation_completed`

当前仍是最小真实实现，下一步应继续推进：

- SQLite store
- 真正的 VCPToolBox 请求编排
- Android 侧接入 `conversations + hydrate snapshot` 的恢复入口

## Catalog 语义边界

- `/api/chat/conversations`：保持旧的兼容列表视图，给现有 Android 恢复链路使用。
- `/api/chat/catalog`：Rust-first 的事实型 catalog facade，返回：
  - `conversation_id`
  - `title`
  - `summary`
  - `updated_at`
  - `generation_state`
  - `pinned`
  - `current_cursor`
  - `is_recoverable`
  - `node_count`

注意：

- Rust 提供事实型 catalog 投影与 recoverability
- `current_cursor` 是当前选中分支的叶子 `NodeId`，不是 variant identity，也不是客户端 UI 行号
- `current / recent / failed` 这类展示分组仍留给客户端决定
