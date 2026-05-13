#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_DIR="$ROOT_DIR/backend"

cd "$FRONTEND_DIR"
if [ "${SKIP_NPM_CI:-0}" != "1" ]; then
  npm ci
fi
npm run build

cd "$BACKEND_DIR"
./mvnw clean package -DskipTests "$@"

echo "打包完成：$BACKEND_DIR/target"
