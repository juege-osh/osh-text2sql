#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$ROOT_DIR/.logs"

stop_one() {
  local name="$1"
  local pid_file="$2"
  if [ ! -f "$pid_file" ]; then
    echo "$name 未发现 pid 文件"
    return
  fi

  local pid
  pid="$(cat "$pid_file")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    echo "$name 已停止，PID=$pid"
  else
    echo "$name pid=$pid 已不存在"
  fi
  rm -f "$pid_file"
}

stop_one "backend" "$LOG_DIR/backend.pid"
stop_one "frontend" "$LOG_DIR/frontend.pid"
