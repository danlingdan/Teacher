#!/usr/bin/env bash
set -euo pipefail

source_db="${SQLTEACHER_CLOUD_DB:-/opt/sqlteacher/data/cloud.db}"
backup_dir="${SQLTEACHER_CLOUD_BACKUP_DIR:-/opt/sqlteacher/backups}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
destination="$backup_dir/cloud-$timestamp.db"

install -d -o sqlteacher -g sqlteacher -m 0750 "$backup_dir"
sudo -u sqlteacher sqlite3 "$source_db" ".backup '$destination'"
sudo -u sqlteacher sqlite3 "$destination" "pragma integrity_check;" | grep -qx ok
chmod 0640 "$destination"
find "$backup_dir" -maxdepth 1 -type f -name 'cloud-*.db' -mtime +30 -delete
printf '%s\n' "$destination"
