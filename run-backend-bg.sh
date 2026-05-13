#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
LOG_DIR="$ROOT_DIR/.logs"
PID_FILE="$LOG_DIR/backend.pid"
LOG_FILE="$LOG_DIR/backend.log"
ENV_FILE="$BACKEND_DIR/.env.local"

mkdir -p "$LOG_DIR"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "backend 已在运行，PID=$(cat "$PID_FILE")"
  exit 0
fi

if [ -f "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
fi

cd "$BACKEND_DIR"
nohup ./mvnw spring-boot:run >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
echo "backend 已启动，PID=$(cat "$PID_FILE")，日志：$LOG_FILE"
