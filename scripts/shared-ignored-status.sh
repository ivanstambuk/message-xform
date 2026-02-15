#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/shared-ignored-status.sh [--manifest <path>] [--all-worktrees]

Show mount status for shared ignored-artifact bind paths in this worktree
or all worktrees of the current repository.
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
  echo "Worktree: $wt"
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    dst="$wt/$rel"
    if mountpoint -q "$dst"; then
      src="$(findmnt -T "$dst" -n -o SOURCE)"
      echo "  mounted  $dst <= $src"
    else
      echo "  unmounted $dst"
    fi
  done <<< "$MOUNT_PATHS"
done < <(worktree_roots)
