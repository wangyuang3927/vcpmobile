# Android Versioning

`android-compose/app/build.gradle.kts` 当前支持通过 Gradle property 覆盖：

- `VCP_RELEASE_VERSION_CODE`
- `VCP_RELEASE_VERSION_NAME`

示例：

```bash
cd android-compose
./gradlew :app:assembleRelease \
  -PVCP_RELEASE_VERSION_CODE=42 \
  -PVCP_RELEASE_VERSION_NAME=0.4.2
```

等价 smoke：

```bash
VCP_RELEASE_VERSION_CODE=42 \
VCP_RELEASE_VERSION_NAME=0.4.2 \
  scripts/verify_android_release_smoke.sh
```
