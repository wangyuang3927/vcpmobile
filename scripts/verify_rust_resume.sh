#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$ROOT_DIR/rust-engine"
PORT="${PORT:-4001}"
HOST="${HOST:-127.0.0.1}"
STORE_PATH="${VCPMOBILE_STORE_PATH:-$RUST_DIR/data/verify-store.json}"

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
  cargo run -p vcpmobile-bridge-http >/tmp/vcpmobile-rust-bridge.log 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 40); do
  if curl -fsS "http://$HOST:$PORT/health" >/dev/null; then
    break
  fi
  sleep 0.25
done

if ! curl -fsS "http://$HOST:$PORT/health" >/dev/null; then
  echo "bridge failed to start; see /tmp/vcpmobile-rust-bridge.log" >&2
  exit 1
fi

FIRST_RESPONSE="$(mktemp)"
SECOND_RESPONSE="$(mktemp)"
trap 'rm -f "$FIRST_RESPONSE" "$SECOND_RESPONSE"; cleanup' EXIT

curl -fsS -N \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"first from verify script"}]}' \
  "http://$HOST:$PORT/api/chat" >"$FIRST_RESPONSE"

CONVERSATION_ID="$(
  python3 - "$FIRST_RESPONSE" <<'PY'
import json, sys
path = sys.argv[1]
for line in open(path, encoding="utf-8"):
    if not line.startswith("data: "):
        continue
    payload = json.loads(line[6:])
    cid = payload.get("conversation_id")
    if cid:
        print(cid)
        break
PY
)"

if [[ -z "$CONVERSATION_ID" ]]; then
  echo "failed to extract conversation_id from first SSE response" >&2
  exit 1
fi

curl -fsS -N \
  -H 'Content-Type: application/json' \
  -d "{\"conversation_id\":\"$CONVERSATION_ID\",\"messages\":[{\"role\":\"user\",\"content\":\"second from verify script\"}]}" \
  "http://$HOST:$PORT/api/chat" >"$SECOND_RESPONSE"

SNAPSHOT_JSON="$(curl -fsS -N "http://$HOST:$PORT/api/chat/stream/$CONVERSATION_ID" | sed -n 's/^data: //p' | head -n 1)"

python3 - "$FIRST_RESPONSE" "$SECOND_RESPONSE" "$SNAPSHOT_JSON" <<'PY'
import json, sys

first_path, second_path, snapshot_raw = sys.argv[1], sys.argv[2], sys.argv[3]

def read_events(path):
    events = []
    for line in open(path, encoding="utf-8"):
        if line.startswith("data: "):
            events.append(json.loads(line[6:]))
    return events

first = read_events(first_path)
second = read_events(second_path)
snapshot = json.loads(snapshot_raw)

cid1 = next(event["conversation_id"] for event in first if event.get("conversation_id"))
cid2 = next(event["conversation_id"] for event in second if event.get("conversation_id"))
assert cid1 == cid2, f"conversation changed: {cid1} != {cid2}"

nodes = snapshot["payload"]["data"]["nodes"]
assert len(nodes) >= 4, f"expected >=4 nodes after two turns, got {len(nodes)}"
print(json.dumps({
    "conversation_id": cid1,
    "turn_nodes": len(nodes),
    "status": "resume-ok"
}, ensure_ascii=False))
PY
