#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$ROOT_DIR/rust-engine"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-4001}"
RUN_RUST_CHECKS="${RUN_RUST_CHECKS:-1}"
RUN_ANDROID_CHECKS="${RUN_ANDROID_CHECKS:-1}"
ANDROID_TESTS=("$@")

log() {
  printf '[release-gate] %s\n' "$*"
}

run_step() {
  local label="$1"
  shift
  log "START $label"
  "$@"
  log "PASS  $label"
}

if [[ "$RUN_RUST_CHECKS" == "1" ]]; then
  run_step 'rust cargo check' bash -lc "cd '$RUST_DIR' && cargo check"
  run_step 'rust cargo test' bash -lc "cd '$RUST_DIR' && cargo test"
fi

run_step 'rust resume + bridge smoke' env HOST="$HOST" PORT="$PORT" "$ROOT_DIR/scripts/verify_rust_resume.sh"
run_step 'bridge endpoint smoke' env HOST="$HOST" PORT="$PORT" "$ROOT_DIR/scripts/verify_bridge_endpoints.sh"

if [[ "$RUN_ANDROID_CHECKS" == "1" ]]; then
  if [[ ${#ANDROID_TESTS[@]} -eq 0 || -z "${ANDROID_TESTS[0]}" ]]; then
    run_step 'android deterministic validation' "$ROOT_DIR/scripts/verify_android_deterministic.sh"
  else
    run_step 'android deterministic validation (targeted)' "$ROOT_DIR/scripts/verify_android_deterministic.sh" "${ANDROID_TESTS[@]}"
  fi
fi

log 'release gate passed'
