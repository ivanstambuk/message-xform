#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/worktree-bootstrap.sh [--manifest <path>] [--all-worktrees]

Prepare shared ignored artifacts for one worktree (default) or all worktrees:
1) seed shared store
2) bind-mount shared paths
3) verify required binary entries
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
MANIFEST=""
ALL_WORKTREES=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest)
      MANIFEST="${2:-}"
      shift 2
      ;;
    --all-worktrees)
      ALL_WORKTREES=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

manifest_args=()
if [[ -n "$MANIFEST" ]]; then
  manifest_args=(--manifest "$MANIFEST")
fi

echo "[bootstrap] preparing shared ignored-artifact store..."
"$SCRIPT_DIR/shared-ignored-init.sh" "${manifest_args[@]}"

echo "[bootstrap] bind-mounting shared ignored-artifact paths..."
if [[ "$ALL_WORKTREES" == "true" ]]; then
  "$SCRIPT_DIR/shared-ignored-mount.sh" "${manifest_args[@]}" --all-worktrees
else
  "$SCRIPT_DIR/shared-ignored-mount.sh" "${manifest_args[@]}"
fi

echo "[bootstrap] verifying required binary entries..."
if [[ "$ALL_WORKTREES" == "true" ]]; then
  git -C "$REPO_ROOT" worktree list --porcelain | awk '/^worktree /{print $2}' | while IFS= read -r wt; do
    [[ -z "$wt" ]] && continue
    echo "[bootstrap] verify in $wt"
    "$wt/scripts/artifacts-verify.sh"
  done
else
  "$SCRIPT_DIR/artifacts-verify.sh" "${manifest_args[@]}"
fi

echo "[bootstrap] done."
