#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${OSH_TEXT2SQL_DEPLOY_ROOT:-/www/osh-text2sql}"
PID_FILE="$ROOT_DIR/run/osh-text2sql.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "未发现 pid 文件，无需停止"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  for _ in $(seq 1 20); do
    if ! kill -0 "$PID" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if kill -0 "$PID" 2>/dev/null; then
    kill -9 "$PID"
  fi
  echo "osh-text2sql 已停止，PID=$PID"
else
  echo "pid=$PID 对应进程已不存在"
fi

rm -f "$PID_FILE"
