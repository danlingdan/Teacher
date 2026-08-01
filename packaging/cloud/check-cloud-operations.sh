#!/usr/bin/env bash
set -euo pipefail

certificate_path="${SQLTEACHER_TLS_CERTIFICATE:-/etc/letsencrypt/live/api.sqlteacher.tech/cert.pem}"
backup_dir="${SQLTEACHER_CLOUD_BACKUP_DIR:-/opt/sqlteacher/backups}"
minimum_certificate_seconds="${SQLTEACHER_MIN_CERTIFICATE_SECONDS:-2592000}"
maximum_backup_age_seconds="${SQLTEACHER_MAX_BACKUP_AGE_SECONDS:-129600}"
qdrant_url="${SQLTEACHER_QDRANT_URL:-http://127.0.0.1:6333}"
qdrant_collection="${SQLTEACHER_QDRANT_COLLECTION:-sqlteacher_course_knowledge_v1}"
qdrant_snapshot_dir="${SQLTEACHER_QDRANT_SNAPSHOT_DIR:-/var/lib/qdrant/snapshots/$qdrant_collection}"
maximum_qdrant_snapshot_age_seconds="${SQLTEACHER_MAX_QDRANT_SNAPSHOT_AGE_SECONDS:-129600}"
embedding_url="${SQLTEACHER_EMBEDDING_URL:-http://127.0.0.1:11434}"
embedding_model="${SQLTEACHER_EMBEDDING_MODEL:-BAAI/bge-small-zh-v1.5}"
maximum_knowledge_index_backlog="${SQLTEACHER_MAX_KNOWLEDGE_INDEX_BACKLOG:-100}"

qdrant_request() {
    local url="$1"
    curl --config - <<EOF
fail
silent
show-error
retry = 10
retry-delay = 1
retry-connrefused
header = "api-key: ${QDRANT__SERVICE__API_KEY}"
url = "$url"
EOF
}

cloud_health="$(curl --fail --silent --show-error --retry 10 --retry-delay 1 --retry-connrefused \
    http://127.0.0.1:18080/health)"
python3 -c 'import json,sys
health=json.load(sys.stdin)
maximum=int(sys.argv[1])
assert health.get("status")=="ok"
assert health.get("knowledgeIndex")=="ready"
backlog=health.get("knowledgeIndexBacklog")
assert isinstance(backlog,int) and 0 <= backlog <= maximum' "$maximum_knowledge_index_backlog" <<<"$cloud_health"
curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    https://api.sqlteacher.tech/health >/dev/null
nginx -t -e stderr
systemctl is-active --quiet certbot.timer
systemctl is-active --quiet sqlteacher-backup.timer
systemctl is-active --quiet sqlteacher-qdrant.service
systemctl is-active --quiet sqlteacher-qdrant-backup.timer
systemctl is-active --quiet sqlteacher-embedding.service
qdrant_request "$qdrant_url/readyz" >/dev/null
qdrant_request "$qdrant_url/collections/$qdrant_collection" >/dev/null
embedding_tags="$(curl --fail --silent --show-error "$embedding_url/api/tags")"
python3 -c 'import json,sys
model=sys.argv[1]
names=[item.get("name","") for item in json.load(sys.stdin).get("models",[])]
assert any(name==model or name==model+":latest" or name.startswith(model+":") for name in names)' \
    "$embedding_model" <<<"$embedding_tags"
openssl x509 -checkend "$minimum_certificate_seconds" -noout -in "$certificate_path"

newest_backup="$({
    find "$backup_dir" -maxdepth 1 -type f -name 'cloud-*.db' -printf '%T@ %p\n' 2>/dev/null || true
} | sort -nr | head -n 1 | cut -d' ' -f2-)"

if [[ -z "$newest_backup" || ! -f "$newest_backup" ]]; then
    printf 'No SQLTeacher cloud backup found under %s\n' "$backup_dir" >&2
    exit 1
fi

backup_age_seconds="$(( $(date +%s) - $(stat -c %Y "$newest_backup") ))"
if (( backup_age_seconds > maximum_backup_age_seconds )); then
    printf 'Newest SQLTeacher cloud backup is %s seconds old; maximum is %s\n' \
        "$backup_age_seconds" "$maximum_backup_age_seconds" >&2
    exit 1
fi

sqlite3 "$newest_backup" 'pragma integrity_check;' | grep -qx ok

newest_qdrant_snapshot="$({
    find "$qdrant_snapshot_dir" -maxdepth 1 -type f -name '*.snapshot' -printf '%T@ %p\n' 2>/dev/null || true
} | sort -nr | head -n 1 | cut -d' ' -f2-)"

if [[ -z "$newest_qdrant_snapshot" || ! -f "$newest_qdrant_snapshot" ]]; then
    printf 'No Qdrant snapshot found under %s\n' "$qdrant_snapshot_dir" >&2
    exit 1
fi

qdrant_snapshot_age_seconds="$(( $(date +%s) - $(stat -c %Y "$newest_qdrant_snapshot") ))"
if (( qdrant_snapshot_age_seconds > maximum_qdrant_snapshot_age_seconds )); then
    printf 'Newest Qdrant snapshot is %s seconds old; maximum is %s\n' \
        "$qdrant_snapshot_age_seconds" "$maximum_qdrant_snapshot_age_seconds" >&2
    exit 1
fi

printf 'SQLTeacher operations check passed; backup=%s ageSeconds=%s qdrantSnapshot=%s qdrantSnapshotAgeSeconds=%s\n' \
    "$newest_backup" "$backup_age_seconds" "$newest_qdrant_snapshot" "$qdrant_snapshot_age_seconds"
