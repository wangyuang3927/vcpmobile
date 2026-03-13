# Android Install Smoke

在 release APK 已产出后，可执行：

```bash
scripts/verify_android_install_smoke.sh
```

默认安装：

- `android-compose/app/build/outputs/apk/release/app-release-unsigned.apk`
- package: `com.vcp.mobile`

## Optional params

指定 APK：

```bash
scripts/verify_android_install_smoke.sh /path/to/app-release.apk
```

指定 package：

```bash
PACKAGE_NAME=com.vcp.mobile scripts/verify_android_install_smoke.sh
```

无设备时允许跳过：

```bash
ALLOW_NO_DEVICE=1 scripts/verify_android_install_smoke.sh
```

## Current posture

- 本机若无 `adb` 或无 device/emulator，可用 `ALLOW_NO_DEVICE=1` 做非阻塞探测。
- 真正发布前，仍应在至少一个 emulator 或真机上执行一次不跳过的 install smoke。
