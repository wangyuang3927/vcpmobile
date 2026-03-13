#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android-compose"
APP_DIR="$ANDROID_DIR/app"
GRADLE_BIN="${GRADLE_BIN:-./gradlew}"
APK_OUT="$APP_DIR/build/outputs/apk/release"
VERSION_CODE="${VCP_RELEASE_VERSION_CODE:-}"
VERSION_NAME="${VCP_RELEASE_VERSION_NAME:-}"

cleanup_release_artifacts() {
  rm -rf \
    "$APP_DIR/build/tmp/kotlin-classes/release" \
    "$APP_DIR/build/tmp/kapt3" \
    "$APP_DIR/build/tmp/kapt3IncrementalData" \
    "$APP_DIR/build/generated/source/kapt" \
    "$APP_DIR/build/generated/source/kaptKotlin" \
    "$APP_DIR/build/intermediates/javac/release"
}

run_gradle() {
  (cd "$ANDROID_DIR" && "$GRADLE_BIN" "$@" --no-daemon)
}

echo '[verify-android-release] cleaning transient release/kapt outputs'
cleanup_release_artifacts

args=(:app:assembleRelease)
if [[ -n "$VERSION_CODE" ]]; then
  args+=(-PVCP_RELEASE_VERSION_CODE="$VERSION_CODE")
fi
if [[ -n "$VERSION_NAME" ]]; then
  args+=(-PVCP_RELEASE_VERSION_NAME="$VERSION_NAME")
fi

echo '[verify-android-release] assembling release APK'
run_gradle "${args[@]}"

APK_PATH="$(find "$APK_OUT" -maxdepth 1 -type f -name '*.apk' | head -n 1)"
METADATA_PATH="$APK_OUT/output-metadata.json"
if [[ -z "$APK_PATH" ]]; then
  echo '[verify-android-release] no APK found under app/build/outputs/apk/release' >&2
  exit 1
fi
if [[ ! -f "$METADATA_PATH" ]]; then
  echo '[verify-android-release] missing output-metadata.json' >&2
  exit 1
fi

SIGNED_STATE='unsigned-or-default-signed'
APKSIGNER_BIN=''
if command -v apksigner >/dev/null 2>&1; then
  APKSIGNER_BIN="$(command -v apksigner)"
elif [[ -n "${ANDROID_HOME:-}" ]] && [[ -d "$ANDROID_HOME/build-tools" ]]; then
  APKSIGNER_BIN="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort | tail -n 1)"
elif [[ -d "$HOME/Android/Sdk/build-tools" ]]; then
  APKSIGNER_BIN="$(find "$HOME/Android/Sdk/build-tools" -type f -name apksigner | sort | tail -n 1)"
fi
if [[ -n "$APKSIGNER_BIN" ]]; then
  VERIFY_LOG="$($APKSIGNER_BIN verify --print-certs "$APK_PATH" 2>&1 || true)"
  if grep -qi 'DOES NOT VERIFY' <<<"$VERIFY_LOG"; then
    echo "$VERIFY_LOG" >&2
    exit 1
  fi
  if grep -qi 'Signer #' <<<"$VERIFY_LOG"; then
    SIGNED_STATE='signed'
  fi
fi

python3 - "$APK_PATH" "$METADATA_PATH" "$SIGNED_STATE" <<'PY'
import json, os, sys
apk_path, metadata_path, signed_state = sys.argv[1], sys.argv[2], sys.argv[3]
metadata = json.load(open(metadata_path, encoding='utf-8'))
element = metadata['elements'][0]
print(json.dumps({
    'apk_path': apk_path,
    'apk_size': os.path.getsize(apk_path),
    'version_code': element.get('versionCode'),
    'version_name': element.get('versionName'),
    'signing': signed_state,
    'status': 'android-release-smoke-ok',
}, ensure_ascii=False))
PY
