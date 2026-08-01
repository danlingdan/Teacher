#!/usr/bin/env bash
set -euo pipefail

qdrant_url="${SQLTEACHER_QDRANT_URL:-http://127.0.0.1:6333}"
collection="${SQLTEACHER_QDRANT_COLLECTION:-sqlteacher_course_knowledge_v1}"
snapshot_dir="${SQLTEACHER_QDRANT_SNAPSHOT_DIR:-/var/lib/qdrant/snapshots/$collection}"

qdrant_request() {
    local method="$1"
    local url="$2"
    curl --config - <<EOF
fail
silent
show-error
request = "$method"
header = "api-key: ${QDRANT__SERVICE__API_KEY}"
url = "$url"
EOF
}

qdrant_request GET "$qdrant_url/readyz" >/dev/null
qdrant_request GET "$qdrant_url/collections/$collection" >/dev/null
response="$(qdrant_request POST "$qdrant_url/collections/$collection/snapshots")"
snapshot_name="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["name"])' <<<"$response")"
test -n "$snapshot_name"
test -f "$snapshot_dir/$snapshot_name"
find "$snapshot_dir" -maxdepth 1 -type f -name '*.snapshot' -mtime +30 -delete
printf 'Qdrant snapshot created: %s\n' "$snapshot_name"
