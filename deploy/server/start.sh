#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${OSH_TEXT2SQL_DEPLOY_ROOT:-/www/osh-text2sql}"
APP_DIR="$ROOT_DIR/app"
SHARED_DIR="$ROOT_DIR/shared"
LOG_DIR="$ROOT_DIR/logs"
RUN_DIR="$ROOT_DIR/run"
ENV_FILE="$SHARED_DIR/app.env"
PID_FILE="$RUN_DIR/osh-text2sql.pid"
LOG_FILE="$LOG_DIR/backend.log"
JAR_FILE="$APP_DIR/osh-text2sql-backend.jar"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
JAVA_BIN="$JAVA_HOME/bin/java"

mkdir -p "$APP_DIR" "$SHARED_DIR" "$LOG_DIR" "$RUN_DIR"

if [ ! -x "$JAVA_BIN" ]; then
  echo "未找到可用的 Java 17: $JAVA_BIN" >&2
  exit 1
fi

if [ ! -f "$JAR_FILE" ]; then
  echo "未找到应用 jar: $JAR_FILE" >&2
  exit 1
fi

if [ -f "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "osh-text2sql 已在运行，PID=$(cat "$PID_FILE")"
  exit 0
fi

nohup "$JAVA_BIN" -jar "$JAR_FILE" >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
echo "osh-text2sql 已启动，PID=$(cat "$PID_FILE")"
