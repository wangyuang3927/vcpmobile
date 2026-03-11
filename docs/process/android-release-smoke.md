# Android Release Smoke

最小 release APK 验证：

```bash
scripts/verify_android_release_smoke.sh
```

## What it does

1. 清理 release/kapt 临时产物
2. 顺序执行 `:app:assembleRelease`
3. 检查 `app/build/outputs/apk/release/*.apk` 是否产出
4. 若 PATH 或 Android SDK build-tools 中存在 `apksigner`，额外检查 APK 是否可验证

## Signing inputs

目前支持两种方式：

### 1. `android-compose/keystore.properties`

```properties
storeFile=/abs/path/to/keystore.jks
storePassword=***
keyAlias=***
keyPassword=***
```

### 2. Gradle properties / command-line properties

- `VCP_RELEASE_STORE_FILE`
- `VCP_RELEASE_STORE_PASSWORD`
- `VCP_RELEASE_KEY_ALIAS`
- `VCP_RELEASE_KEY_PASSWORD`

例如：

```bash
cd android-compose
./gradlew :app:assembleRelease \
  -PVCP_RELEASE_STORE_FILE=/abs/path/to/keystore.jks \
  -PVCP_RELEASE_STORE_PASSWORD=*** \
  -PVCP_RELEASE_KEY_ALIAS=*** \
  -PVCP_RELEASE_KEY_PASSWORD=***
```

## Current posture

- 若未提供签名材料，脚本仍会验证 **release APK assemble smoke**。
- 若提供签名材料，`release` buildType 会自动挂载 release signingConfig。
- 这一步是 D2.3 的最小闭环，不等于最终发布流程。最终发布仍需要版本号、签名归档与设备安装 smoke。
