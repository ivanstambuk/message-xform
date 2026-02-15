#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/artifacts-sync.sh [--manifest <path>] [--dry-run]

Sync ignored artifact files from a canonical repository/worktree into the
current repository/worktree using settings from artifacts/manifest.yaml.

Environment override:
  MESSAGE_XFORM_ARTIFACTS_CANONICAL_ROOT
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
MANIFEST="$REPO_ROOT/artifacts/manifest.yaml"
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest)
      MANIFEST="${2:-}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=true
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
  sed -nE "s/^[[:space:]]*${key}:[[:space:]]*(.+)$/\\1/p" "$MANIFEST" | head -n1 | sed -E 's/[[:space:]]+#.*$//' | tr -d '"'
}

yaml_required_entries() {
  awk '
    /^[[:space:]]*required_entries:[[:space:]]*$/ {in_required=1; next}
    in_required && /^[[:space:]]*-[[:space:]]*/ {
      sub(/^[[:space:]]*-[[:space:]]*/, "", $0)
      print $0
      next
    }
    in_required && /^[[:space:]]*[A-Za-z0-9_]+:[[:space:]]*/ {in_required=0}
  ' "$MANIFEST"
}

CANONICAL_ROOT="${MESSAGE_XFORM_ARTIFACTS_CANONICAL_ROOT:-$(yaml_scalar canonical_repo_root)}"
SOURCE_SUBDIR="$(yaml_scalar source_subdir)"
LOCAL_SUBDIR="$(yaml_scalar local_subdir)"

if [[ -z "$CANONICAL_ROOT" || -z "$SOURCE_SUBDIR" || -z "$LOCAL_SUBDIR" ]]; then
  echo "Manifest missing required keys: canonical_repo_root, source_subdir, local_subdir" >&2
  exit 1
fi

SOURCE_DIR="$CANONICAL_ROOT/$SOURCE_SUBDIR"
LOCAL_DIR="$REPO_ROOT/$LOCAL_SUBDIR"

if [[ ! -d "$SOURCE_DIR" ]]; then
  echo "Source artifacts directory not found: $SOURCE_DIR" >&2
  exit 1
fi

mkdir -p "$LOCAL_DIR"

SOURCE_REAL="$(readlink -f "$SOURCE_DIR")"
LOCAL_REAL="$(readlink -f "$LOCAL_DIR")"

if [[ "$SOURCE_REAL" == "$LOCAL_REAL" ]]; then
  echo "Artifacts already local (source == destination): $LOCAL_DIR"
else
  RSYNC_ARGS=(-a)
  if [[ "$DRY_RUN" == "true" ]]; then
    RSYNC_ARGS+=(-n -v)
    echo "Running in dry-run mode."
  fi
  rsync "${RSYNC_ARGS[@]}" "$SOURCE_DIR"/ "$LOCAL_DIR"/
  echo "Synced artifacts from $SOURCE_DIR to $LOCAL_DIR"
fi

MISSING=0
while IFS= read -r entry; do
  [[ -z "$entry" ]] && continue
  if [[ ! -e "$LOCAL_DIR/$entry" ]]; then
    echo "Missing required artifact entry: $LOCAL_DIR/$entry" >&2
    MISSING=1
  fi
done < <(yaml_required_entries)

if [[ "$MISSING" -ne 0 ]]; then
  exit 1
fi

echo "Artifact sync completed successfully."
