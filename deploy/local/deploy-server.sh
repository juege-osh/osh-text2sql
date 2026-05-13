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

if [ ! -f "$BACKEND_ENV_FILE" ]; then
  echo "缺少环境文件：$BACKEND_ENV_FILE" >&2
  exit 1
fi

set -a
. "$BACKEND_ENV_FILE"
set +a

"$ROOT_DIR/package-release.sh"

JAR_FILE="$(find "$ROOT_DIR/backend/target" -maxdepth 1 -name 'osh-text2sql-backend-*.jar' | head -n 1)"
if [ -z "$JAR_FILE" ]; then
  echo "未找到构建产物 jar" >&2
  exit 1
fi

SSH_BASE=(ssh -i "$SSH_KEY_PATH" -p "$DEPLOY_PORT" "$DEPLOY_USER@$DEPLOY_HOST")
RSYNC_BASE=(rsync -az -e "ssh -i $SSH_KEY_PATH -p $DEPLOY_PORT")

"${SSH_BASE[@]}" "mkdir -p '$DEPLOY_ROOT/app' '$DEPLOY_ROOT/bin' '$DEPLOY_ROOT/shared' '$DEPLOY_ROOT/logs' '$DEPLOY_ROOT/run'"
"${RSYNC_BASE[@]}" "$JAR_FILE" "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_ROOT/app/osh-text2sql-backend.jar.next"
"${RSYNC_BASE[@]}" "$ROOT_DIR/deploy/server/" "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_ROOT/bin/"
"${RSYNC_BASE[@]}" "$BACKEND_ENV_FILE" "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_ROOT/shared/app.env"

"${SSH_BASE[@]}" "\
  python3 - <<'PY' && \
from pathlib import Path
env_path = Path('$DEPLOY_ROOT/shared/app.env')
lines = env_path.read_text().splitlines()
updated = []
found = False
for line in lines:
    if line.startswith('OSH_TEXT2SQL_SERVER_PORT='):
        updated.append('OSH_TEXT2SQL_SERVER_PORT=$REMOTE_APP_PORT')
        found = True
    else:
        updated.append(line)
if not found:
    updated.append('OSH_TEXT2SQL_SERVER_PORT=$REMOTE_APP_PORT')
env_path.write_text('\\n'.join(updated) + '\\n')
PY
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
