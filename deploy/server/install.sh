#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${OSH_TEXT2SQL_DEPLOY_ROOT:-/www/osh-text2sql}"
BIN_DIR="$ROOT_DIR/bin"
APP_DIR="$ROOT_DIR/app"
SHARED_DIR="$ROOT_DIR/shared"
LOG_DIR="$ROOT_DIR/logs"
RUN_DIR="$ROOT_DIR/run"

mkdir -p "$BIN_DIR" "$APP_DIR" "$SHARED_DIR" "$LOG_DIR" "$RUN_DIR"
chmod 755 "$BIN_DIR"/*.sh

if [ ! -f "$SHARED_DIR/app.env" ] && [ -f "$BIN_DIR/app.env.example" ]; then
  cp "$BIN_DIR/app.env.example" "$SHARED_DIR/app.env"
  chmod 600 "$SHARED_DIR/app.env"
fi

echo "部署目录已就绪：$ROOT_DIR"
