#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${OSH_TEXT2SQL_DEPLOY_ROOT:-/www/osh-text2sql}"
ENV_FILE="$ROOT_DIR/shared/app.env"
PID_FILE="$ROOT_DIR/run/osh-text2sql.pid"
APP_PORT=19100

if [ -f "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
  APP_PORT="${OSH_TEXT2SQL_SERVER_PORT:-19100}"
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "进程状态：RUNNING PID=$(cat "$PID_FILE")"
else
  echo "进程状态：STOPPED"
fi

curl -fsS "http://127.0.0.1:${APP_PORT}/api/health" || true
