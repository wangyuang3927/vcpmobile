#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android-compose"
APP_DIR="$ANDROID_DIR/app"
GRADLE_BIN="${GRADLE_BIN:-./gradlew}"
UNIT_TESTS=("$@")

cleanup_kapt_race_artifacts() {
  rm -rf \
    "$APP_DIR/build/tmp/kotlin-classes/debug" \
    "$APP_DIR/build/tmp/kapt3" \
    "$APP_DIR/build/tmp/kapt3IncrementalData" \
    "$APP_DIR/build/generated/source/kapt" \
    "$APP_DIR/build/generated/source/kaptKotlin" \
    "$APP_DIR/build/intermediates/javac/debug"
}

run_gradle() {
  (cd "$ANDROID_DIR" && "$GRADLE_BIN" "$@" --no-daemon)
}

compile_task=':app:compileDebugKotlin'
test_task=':app:testDebugUnitTest'

echo '[verify-android] cleaning transient kapt/kotlin outputs to avoid stub races'
cleanup_kapt_race_artifacts

echo "[verify-android] running $compile_task"
run_gradle "$compile_task"

if [[ ${#UNIT_TESTS[@]} -eq 0 ]]; then
  echo "[verify-android] running $test_task"
  run_gradle "$test_task"
else
  args=("$test_task")
  for test_name in "${UNIT_TESTS[@]}"; do
    args+=(--tests "$test_name")
  done
  echo "[verify-android] running targeted tests: ${UNIT_TESTS[*]}"
  run_gradle "${args[@]}"
fi

echo '[verify-android] deterministic Android validation passed'
