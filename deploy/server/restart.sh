#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${OSH_TEXT2SQL_DEPLOY_ROOT:-/www/osh-text2sql}"
"$ROOT_DIR/bin/stop.sh"
"$ROOT_DIR/bin/start.sh"
