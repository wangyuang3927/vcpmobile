# Release Gate

当前首发前的最小 release gate 已固化为：

```bash
scripts/verify_release_gate.sh
```

## Included checks

1. Rust compile gate
   - `cargo check`
   - `cargo test`
2. Rust bridge / resume smoke
   - `scripts/verify_rust_resume.sh`
3. Bridge endpoint smoke
   - `scripts/verify_bridge_endpoints.sh`
   - covers `GET /health` and `GET /api/chat/catalog`
4. Android deterministic validation
   - `scripts/verify_android_deterministic.sh`

## Environment

默认：

- `HOST=127.0.0.1`
- `PORT=4001`

可覆盖：

```bash
HOST=127.0.0.1 PORT=4100 scripts/verify_release_gate.sh
```

## Optional flags

跳过 Rust：

```bash
RUN_RUST_CHECKS=0 scripts/verify_release_gate.sh
```

跳过 Android：

```bash
RUN_ANDROID_CHECKS=0 scripts/verify_release_gate.sh
```

仅跑 Android 定向测试：

```bash
scripts/verify_release_gate.sh \
  com.vcp.mobile.data.network.OkHttpSseHubApiClientTest \
  com.vcp.mobile.ui.chat.ChatViewModelRecoveryTest
```

## Current posture

- 这是一条 **顺序 gate**，不是并行 gate。
- Android compile/test 并行仍可能触发 kapt stub race，因此 release 证据必须来自顺序脚本。
- `verify_rust_resume.sh` 与 `verify_bridge_endpoints.sh` 都自带临时 bridge 生命周期；release gate 不依赖外部常驻服务。
