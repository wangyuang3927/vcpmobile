# Android Deterministic Validation

## Why

`android-compose` 当前在 **并行** 执行 `:app:compileDebugKotlin` 与 `:app:testDebugUnitTest` 时，会偶发 Kotlin/kapt stub 删除竞争，典型症状是：

- `FileNotFoundException ... build/tmp/kotlin-classes/debug/...class`
- `compileDebugKotlin FAILED`

因此首发阶段把 Android 验证策略固定为：

1. 先清理易竞争的临时产物
2. 顺序执行 `:app:compileDebugKotlin`
3. 再执行 `:app:testDebugUnitTest`
4. 禁止把 compile/test 并行跑当成 release gate 证据

## Script

```bash
scripts/verify_android_deterministic.sh
```

Targeted tests:

```bash
scripts/verify_android_deterministic.sh \
  com.vcp.mobile.data.network.OkHttpSseHubApiClientTest \
  com.vcp.mobile.ui.chat.ChatViewModelRecoveryTest
```

## What it cleans

- `app/build/tmp/kotlin-classes/debug`
- `app/build/tmp/kapt3`
- `app/build/tmp/kapt3IncrementalData`
- `app/build/generated/source/kapt`
- `app/build/generated/source/kaptKotlin`
- `app/build/intermediates/javac/debug`

## Launch posture

- 这是 **D2.1** 的当前最小确定性流程。
- 如果未来升级 AGP/Kotlin 后 race 消失，可以收紧清理范围；但在此之前，release gate 必须使用该顺序脚本或等价流程。
