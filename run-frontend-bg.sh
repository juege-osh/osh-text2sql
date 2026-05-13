#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
LOG_DIR="$ROOT_DIR/.logs"
PID_FILE="$LOG_DIR/frontend.pid"
LOG_FILE="$LOG_DIR/frontend.log"
PORT="${OSH_TEXT2SQL_FRONTEND_PORT:-9101}"

mkdir -p "$LOG_DIR"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "frontend 已在运行，PID=$(cat "$PID_FILE")"
  exit 0
fi

if lsof -nP -iTCP:${PORT} -sTCP:LISTEN >/dev/null 2>&1; then
  echo "frontend 端口 ${PORT} 已被占用，请先释放后再启动"
  exit 1
fi

cd "$FRONTEND_DIR"
nohup npm run dev -- --host 0.0.0.0 --port ${PORT} --strictPort >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
echo "frontend 已启动，PID=$(cat "$PID_FILE")，日志：$LOG_FILE"
