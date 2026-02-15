#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/shared-ignored-umount.sh [--manifest <path>] [--all-worktrees]

Unmount bind-mounted shared ignored-artifact paths from this worktree
or from all worktrees of the current repository.

Environment:
  SUDO_PASS (non-interactive sudo for automation)
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
MANIFEST="$REPO_ROOT/artifacts/manifest.yaml"
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

if [[ ! -f "$MANIFEST" ]]; then
  echo "Manifest not found: $MANIFEST" >&2
  exit 1
fi

yaml_list() {
  local key="$1"
  awk -v key="$key" '
    $0 ~ "^[[:space:]]*" key ":[[:space:]]*$" {in_list=1; next}
    in_list && /^[[:space:]]*-[[:space:]]*/ {
      sub(/^[[:space:]]*-[[:space:]]*/, "", $0)
      print $0
      next
    }
    in_list && /^[[:space:]]*[A-Za-z0-9_]+:[[:space:]]*/ {in_list=0}
  ' "$MANIFEST"
}

sudo_run() {
  if sudo -n true >/dev/null 2>&1; then
    sudo "$@"
  elif [[ -n "${SUDO_PASS:-}" ]]; then
    printf '%s\n' "$SUDO_PASS" | sudo -S -p '' "$@"
  else
    sudo "$@"
  fi
}

worktree_roots() {
  if [[ "$ALL_WORKTREES" == "true" ]]; then
    git -C "$REPO_ROOT" worktree list --porcelain | awk '/^worktree /{print $2}'
  else
    printf '%s\n' "$REPO_ROOT"
  fi
}

MOUNT_PATHS="$(yaml_list bind_mount_paths)"
if [[ -z "$MOUNT_PATHS" ]]; then
  echo "Manifest missing list: bind_mount_paths" >&2
  exit 1
fi

while IFS= read -r wt; do
  [[ -z "$wt" ]] && continue
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    dst="$wt/$rel"
    if mountpoint -q "$dst"; then
      sudo_run umount "$dst"
      echo "Unmounted: $dst"
    else
      echo "Not mounted: $dst"
    fi
  done <<< "$MOUNT_PATHS"
done < <(worktree_roots)

echo "Unmount operation complete."
