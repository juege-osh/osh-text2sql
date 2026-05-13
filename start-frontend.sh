#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${OSH_TEXT2SQL_FRONTEND_PORT:-9101}"
PROJECT_DIR="$ROOT_DIR/frontend"

if lsof -nP -iTCP:${PORT} -sTCP:LISTEN >/dev/null 2>&1; then
  echo "frontend 已在 ${PORT} 端口运行，直接访问 http://127.0.0.1:${PORT}"
  exit 0
fi

cd "$PROJECT_DIR"
npm run dev -- --host 0.0.0.0 --port ${PORT} --strictPort
