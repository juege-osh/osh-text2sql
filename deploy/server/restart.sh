#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="${OSH_TEXT2SQL_DEPLOY_ROOT:-$DEFAULT_ROOT}"
"$ROOT_DIR/bin/stop.sh"
"$ROOT_DIR/bin/start.sh"
