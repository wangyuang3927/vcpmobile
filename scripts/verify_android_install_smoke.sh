#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="${1:-$ROOT_DIR/android-compose/app/build/outputs/apk/release/app-release-unsigned.apk}"
PACKAGE_NAME="${PACKAGE_NAME:-com.vcp.mobile}"
ALLOW_NO_DEVICE="${ALLOW_NO_DEVICE:-0}"

if ! command -v adb >/dev/null 2>&1; then
  if [[ "$ALLOW_NO_DEVICE" == "1" ]]; then
    echo '{"status":"skipped","reason":"adb-not-found"}'
    exit 0
  fi
  echo '[verify-android-install] adb not found' >&2
  exit 1
fi

DEVICE_SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [[ -z "$DEVICE_SERIAL" ]]; then
  if [[ "$ALLOW_NO_DEVICE" == "1" ]]; then
    echo '{"status":"skipped","reason":"no-device"}'
    exit 0
  fi
  echo '[verify-android-install] no online adb device/emulator found' >&2
  exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "[verify-android-install] apk not found: $APK_PATH" >&2
  exit 1
fi

adb -s "$DEVICE_SERIAL" install -r "$APK_PATH" >/tmp/vcpmobile-install.log
adb -s "$DEVICE_SERIAL" shell cmd package resolve-activity --brief "$PACKAGE_NAME" >/tmp/vcpmobile-resolve.log

python3 - "$DEVICE_SERIAL" "$APK_PATH" "$PACKAGE_NAME" /tmp/vcpmobile-resolve.log <<'PY'
import json, sys
serial, apk_path, package_name, resolve_log = sys.argv[1:5]
resolved = open(resolve_log, encoding='utf-8').read().strip()
print(json.dumps({
    'device': serial,
    'apk_path': apk_path,
    'package': package_name,
    'resolved_activity': resolved,
    'status': 'android-install-smoke-ok',
}, ensure_ascii=False))
PY
