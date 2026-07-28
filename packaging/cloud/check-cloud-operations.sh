#!/usr/bin/env bash
set -euo pipefail

certificate_path="${SQLTEACHER_TLS_CERTIFICATE:-/etc/letsencrypt/live/api.sqlteacher.tech/cert.pem}"
backup_dir="${SQLTEACHER_CLOUD_BACKUP_DIR:-/opt/sqlteacher/backups}"
minimum_certificate_seconds="${SQLTEACHER_MIN_CERTIFICATE_SECONDS:-2592000}"
maximum_backup_age_seconds="${SQLTEACHER_MAX_BACKUP_AGE_SECONDS:-129600}"

curl --fail --silent --show-error http://127.0.0.1:18080/health >/dev/null
curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    https://api.sqlteacher.tech/health >/dev/null
nginx -t -e stderr
systemctl is-active --quiet certbot.timer
systemctl is-active --quiet sqlteacher-backup.timer
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
printf 'SQLTeacher operations check passed; backup=%s ageSeconds=%s\n' \
    "$newest_backup" "$backup_age_seconds"
