#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    printf 'Usage: %s /absolute/path/to/cloud-backup.db\n' "$0" >&2
    exit 2
fi

backup_file="$(realpath "$1")"
target_db="${SQLTEACHER_CLOUD_DB:-/opt/sqlteacher/data/cloud.db}"
data_dir="$(dirname "$target_db")"

if [[ ! -f "$backup_file" || "$backup_file" != /opt/sqlteacher/backups/* ]]; then
    printf 'Backup must be an existing file under /opt/sqlteacher/backups\n' >&2
    exit 2
fi

sqlite3 "$backup_file" "pragma integrity_check;" | grep -qx ok
systemctl stop sqlteacher-cloud
install -d -o sqlteacher -g sqlteacher -m 0750 "$data_dir"
if [[ -f "$target_db" ]]; then
    cp --preserve=mode,ownership,timestamps "$target_db" "$target_db.before-restore"
fi
install -o sqlteacher -g sqlteacher -m 0640 "$backup_file" "$target_db"
systemctl start sqlteacher-cloud
curl --fail --silent http://127.0.0.1:18080/health >/dev/null
