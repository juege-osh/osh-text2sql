#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$ROOT_DIR/backend"
./mvnw test

cd "$ROOT_DIR/frontend"
npm ci
npm run build

echo "后端测试与前端构建均已通过"
