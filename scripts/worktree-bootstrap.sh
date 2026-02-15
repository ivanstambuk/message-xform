#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[bootstrap] syncing ignored artifacts into this worktree..."
"$SCRIPT_DIR/artifacts-sync.sh" "$@"

echo "[bootstrap] verifying required artifact entries..."
"$SCRIPT_DIR/artifacts-verify.sh" "$@"

echo "[bootstrap] done."
