#!/usr/bin/env bash
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
app_jar="$(find "$APP_HOME/app" -maxdepth 1 -type f -name 'Teacher-*.jar' -print -quit)"
if [[ -z "$app_jar" ]]; then
  printf 'SQLTeacher application JAR was not found under %s/app\n' "$APP_HOME" >&2
  exit 1
fi
exec /usr/bin/java -cp "$app_jar:$APP_HOME/app/lib/*" \
  com.sqlteacher.server.SqlTeacherCloudServer
