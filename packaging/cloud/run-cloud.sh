#!/usr/bin/env bash
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
exec /usr/bin/java -cp "$APP_HOME/app/Teacher-1.1.0.jar:$APP_HOME/app/lib/*" \
  com.sqlteacher.server.SqlTeacherCloudServer
