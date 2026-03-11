#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$ROOT_DIR/rust-engine"
PORT="${PORT:-4001}"
HOST="${HOST:-127.0.0.1}"
STORE_PATH="${VCPMOBILE_STORE_PATH:-$RUST_DIR/data/verify-endpoints-store.json}"

cleanup() {
  if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

cd "$RUST_DIR"
rm -f "$STORE_PATH"
HOST="$HOST" PORT="$PORT" VCPMOBILE_STORE_PATH="$STORE_PATH" \
  cargo run -p vcpmobile-bridge-http >/tmp/vcpmobile-bridge-endpoints.log 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 40); do
  if curl -fsS "http://$HOST:$PORT/health" >/dev/null; then
    break
  fi
  sleep 0.25
done

curl -fsS "http://$HOST:$PORT/health" >/dev/null
CATALOG_JSON="$(curl -fsS "http://$HOST:$PORT/api/chat/catalog")"

python3 - "$CATALOG_JSON" <<'PY'
import json, sys
catalog = json.loads(sys.argv[1])
assert isinstance(catalog, list), f"catalog should be a list, got {type(catalog)!r}"
print(json.dumps({"catalog_entries": len(catalog), "status": "bridge-endpoints-ok"}, ensure_ascii=False))
PY
