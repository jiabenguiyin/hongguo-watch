#!/usr/bin/env bash
# 冒烟：健康检查 +（可选）取流。用法：
#   BASE=http://127.0.0.1:8787 TOKEN=secret EPISODE_ID=123 bash proxy/scripts/smoke-test.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8787}"
BASE="${BASE%/}"
TOKEN="${TOKEN:-${HONGGUO_PLAYBACK_PROXY_TOKEN:-}}"
EPISODE_ID="${EPISODE_ID:-}"

echo "== GET $BASE/health =="
curl -fsS "$BASE/health" | tee /tmp/hongguo-proxy-health.json
echo

AUTH=()
if [[ -n "$TOKEN" ]]; then
  AUTH=(-H "Authorization: Bearer $TOKEN")
fi

if [[ -n "$EPISODE_ID" ]]; then
  echo "== GET $BASE/v1/stream?episode_id=$EPISODE_ID =="
  curl -fsS "${AUTH[@]}" \
    "$BASE/v1/stream?episode_id=${EPISODE_ID}&quality=auto" \
    | tee /tmp/hongguo-proxy-stream.json
  echo
else
  echo "(跳过 /v1/stream：未设 EPISODE_ID)"
fi

echo "OK"
