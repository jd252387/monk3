#!/bin/bash
set -euo pipefail

ETCD_URL="http://etcd:2379"

log() { echo "[seed] $*"; }

log "Waiting for etcd..."
until curl -sf "${ETCD_URL}/health" > /dev/null 2>&1; do sleep 2; done
log "etcd is ready."

# etcd's v3 gRPC gateway expects base64-encoded keys and values. `tr -d '\n'` strips any
# line-wrapping that busybox base64 may add so the JSON payload stays on one line.
b64() { base64 | tr -d '\n'; }

put() {
  local key="$1" file="$2"
  local k v
  k=$(printf '%s' "$key" | b64)
  v=$(b64 < "$file")
  curl -sf "${ETCD_URL}/v3/kv/put" \
    -H 'Content-Type: application/json' \
    -d "{\"key\":\"${k}\",\"value\":\"${v}\"}" > /dev/null
  log "put ${key} <- ${file}"
}

echo "=== Seeding monk configuration into etcd ==="
put "/monk/catalog"         "/config/catalog-etcd.json"
put "/monk/mappings/sample" "/config/mappings/sample.mapping.json"
put "/monk/backends"        "/config/backends-docker.json"

echo ""
echo "=== Verifying seeded keys under /monk ==="
START=$(printf '%s' "/monk" | b64)
END=$(printf '%s' "/monk4" | b64)   # prefix range end: last byte of "/monk" incremented
curl -sf "${ETCD_URL}/v3/kv/range" \
  -H 'Content-Type: application/json' \
  -d "{\"key\":\"${START}\",\"range_end\":\"${END}\"}" \
  | jq -r '.kvs[]?.key' | while read -r key; do printf '%s\n' "$key" | base64 -d; echo; done

echo ""
log "Seeding complete."
