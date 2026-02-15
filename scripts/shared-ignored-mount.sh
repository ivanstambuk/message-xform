#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/shared-ignored-mount.sh [--manifest <path>] [--all-worktrees]

Bind-mount shared ignored-artifact paths from shared_root into this worktree
or into all worktrees of the current repository.

Environment overrides:
  MESSAGE_XFORM_SHARED_ROOT
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

yaml_scalar() {
  local key="$1"
  sed -nE "s/^[[:space:]]*${key}:[[:space:]]*(.+)$/\\1/p" "$MANIFEST" \
    | head -n1 \
    | sed -E 's/[[:space:]]+#.*$//' \
    | tr -d '"'
}

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
    sudo -n "$@"
  else
    sudo "$@"
  fi
}

SHARED_ROOT="${MESSAGE_XFORM_SHARED_ROOT:-$(yaml_scalar shared_root)}"
if [[ -z "$SHARED_ROOT" ]]; then
  echo "Manifest missing required key: shared_root" >&2
  exit 1
fi

MOUNT_PATHS="$(yaml_list bind_mount_paths)"
if [[ -z "$MOUNT_PATHS" ]]; then
  echo "Manifest missing list: bind_mount_paths" >&2
  exit 1
fi

worktree_roots() {
  if [[ "$ALL_WORKTREES" == "true" ]]; then
    git -C "$REPO_ROOT" worktree list --porcelain | awk '/^worktree /{print $2}'
  else
    printf '%s\n' "$REPO_ROOT"
  fi
}

ensure_seeded_before_mount() {
  local src="$1"
  local dst="$2"
  mkdir -p "$src" "$dst"
  if [[ -z "$(ls -A "$src" 2>/dev/null)" && -n "$(ls -A "$dst" 2>/dev/null)" ]]; then
    rsync -a "$dst"/ "$src"/
    echo "Seeded shared path before mount: $src <= $dst"
  fi
}

while IFS= read -r wt; do
  [[ -z "$wt" ]] && continue
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    src="$SHARED_ROOT/$rel"
    dst="$wt/$rel"
    ensure_seeded_before_mount "$src" "$dst"
    if mountpoint -q "$dst"; then
      echo "Already mounted: $dst"
      continue
    fi
    sudo_run mount --bind "$src" "$dst"
    echo "Mounted: $src -> $dst"
  done <<< "$MOUNT_PATHS"
done < <(worktree_roots)

echo "Bind-mount operation complete."
