#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/shared-ignored-init.sh [--manifest <path>]

Initialize the shared ignored-artifacts store and seed it from the canonical
repository paths listed in artifacts/manifest.yaml (bind_mount_paths).

This script does not perform any mounts; it only prepares shared data.
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
MANIFEST="$REPO_ROOT/artifacts/manifest.yaml"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest)
      MANIFEST="${2:-}"
      shift 2
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

CANONICAL_ROOT="${MESSAGE_XFORM_ARTIFACTS_CANONICAL_ROOT:-$(yaml_scalar canonical_repo_root)}"
SHARED_ROOT="${MESSAGE_XFORM_SHARED_ROOT:-$(yaml_scalar shared_root)}"

if [[ -z "$CANONICAL_ROOT" || -z "$SHARED_ROOT" ]]; then
  echo "Manifest missing required keys: canonical_repo_root, shared_root" >&2
  exit 1
fi

mkdir -p "$SHARED_ROOT"

MOUNT_PATHS="$(yaml_list bind_mount_paths)"
if [[ -z "$MOUNT_PATHS" ]]; then
  echo "Manifest missing list: bind_mount_paths" >&2
  exit 1
fi

while IFS= read -r rel; do
  [[ -z "$rel" ]] && continue
  src="$CANONICAL_ROOT/$rel"
  dst="$SHARED_ROOT/$rel"
  mkdir -p "$dst"
  if [[ -d "$src" ]]; then
    rsync -a "$src"/ "$dst"/
    echo "Seeded: $dst <= $src"
  else
    echo "Skip (source missing): $src"
  fi
done <<< "$MOUNT_PATHS"

echo "Shared ignored-artifacts initialization complete: $SHARED_ROOT"
