#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DEPLOY_HOST="${DEPLOY_HOST:-43.242.200.25}"
DEPLOY_USER="${DEPLOY_USER:-root}"
DEPLOY_PORT="${DEPLOY_PORT:-58753}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/www/osh-text2sql}"
SSH_KEY_PATH="${SSH_KEY_PATH:-$HOME/.ssh/osh_github_actions_deploy_key}"
BACKEND_ENV_FILE="${BACKEND_ENV_FILE:-$ROOT_DIR/backend/.env.local}"
REMOTE_APP_PORT="${REMOTE_APP_PORT:-19100}"
REMOTE_DASHSCOPE_ENABLED="${REMOTE_DASHSCOPE_ENABLED:-}"

if [ ! -f "$BACKEND_ENV_FILE" ]; then
  echo "缺少环境文件：$BACKEND_ENV_FILE" >&2
  exit 1
fi

set -a
. "$BACKEND_ENV_FILE"
set +a

if [ -z "$REMOTE_DASHSCOPE_ENABLED" ]; then
  if [ -n "${OSH_TEXT2SQL_DASHSCOPE_API_KEY:-}" ]; then
    REMOTE_DASHSCOPE_ENABLED="true"
  else
    REMOTE_DASHSCOPE_ENABLED="false"
  fi
fi

"$ROOT_DIR/package-release.sh"

JAR_FILE="$(find "$ROOT_DIR/backend/target" -maxdepth 1 -name 'osh-text2sql-backend-*.jar' | head -n 1)"
if [ -z "$JAR_FILE" ]; then
  echo "未找到构建产物 jar" >&2
  exit 1
fi

TEMP_ENV_FILE="$(mktemp)"
trap 'rm -f "$TEMP_ENV_FILE"' EXIT
cp "$BACKEND_ENV_FILE" "$TEMP_ENV_FILE"

python3 - <<PY
from pathlib import Path

env_path = Path("$TEMP_ENV_FILE")
values = {
    "OSH_TEXT2SQL_DEPLOY_ROOT": "$DEPLOY_ROOT",
    "OSH_TEXT2SQL_SERVER_PORT": "$REMOTE_APP_PORT",
    "OSH_TEXT2SQL_DASHSCOPE_ENABLED": "$REMOTE_DASHSCOPE_ENABLED",
}
lines = env_path.read_text().splitlines()
updated = []
seen = set()
for line in lines:
    replaced = False
    for key, value in values.items():
        if line.startswith(f"{key}="):
            updated.append(f"{key}={value}")
            seen.add(key)
            replaced = True
            break
    if not replaced:
        updated.append(line)
for key, value in values.items():
    if key not in seen:
        updated.append(f"{key}={value}")
env_path.write_text("\\n".join(updated) + "\\n")
PY

SSH_BASE=(ssh -i "$SSH_KEY_PATH" -p "$DEPLOY_PORT" "$DEPLOY_USER@$DEPLOY_HOST")
RSYNC_BASE=(rsync -az -e "ssh -i $SSH_KEY_PATH -p $DEPLOY_PORT")

"${SSH_BASE[@]}" "mkdir -p '$DEPLOY_ROOT/app' '$DEPLOY_ROOT/bin' '$DEPLOY_ROOT/shared' '$DEPLOY_ROOT/logs' '$DEPLOY_ROOT/run'"
"${RSYNC_BASE[@]}" "$JAR_FILE" "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_ROOT/app/osh-text2sql-backend.jar.next"
"${RSYNC_BASE[@]}" "$ROOT_DIR/deploy/server/" "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_ROOT/bin/"
"${RSYNC_BASE[@]}" "$TEMP_ENV_FILE" "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_ROOT/shared/app.env"

"${SSH_BASE[@]}" "\
  chmod 600 '$DEPLOY_ROOT/shared/app.env' && \
  chmod 755 '$DEPLOY_ROOT/bin/'*.sh && \
  '$DEPLOY_ROOT/bin/install.sh' && \
  mv '$DEPLOY_ROOT/app/osh-text2sql-backend.jar.next' '$DEPLOY_ROOT/app/osh-text2sql-backend.jar' && \
  '$DEPLOY_ROOT/bin/restart.sh'"

"${SSH_BASE[@]}" "\
  for i in \$(seq 1 30); do \
    if curl -fsS 'http://127.0.0.1:${REMOTE_APP_PORT}/api/health' >/dev/null; then \
      break; \
    fi; \
    sleep 2; \
  done; \
  '$DEPLOY_ROOT/bin/status.sh'"

echo "远端部署完成，尝试访问：http://$DEPLOY_HOST:$REMOTE_APP_PORT/"
