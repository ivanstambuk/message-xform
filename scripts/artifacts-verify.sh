#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/artifacts-verify.sh [--manifest <path>]

Verify that required ignored artifact files are present in the current
repository/worktree using settings from artifacts/manifest.yaml.
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

LOCAL_SUBDIR="$(yaml_scalar local_subdir)"
if [[ -z "$LOCAL_SUBDIR" ]]; then
  echo "Manifest missing required key: local_subdir" >&2
  exit 1
fi

LOCAL_DIR="$REPO_ROOT/$LOCAL_SUBDIR"
if [[ ! -d "$LOCAL_DIR" ]]; then
  echo "Local artifacts directory not found: $LOCAL_DIR" >&2
  exit 1
fi

MISSING=0
COUNT=0
while IFS= read -r entry; do
  [[ -z "$entry" ]] && continue
  COUNT=$((COUNT + 1))
  if [[ ! -e "$LOCAL_DIR/$entry" ]]; then
    echo "Missing required artifact entry: $LOCAL_DIR/$entry" >&2
    MISSING=1
  fi
done < <(yaml_required_entries)

if [[ "$MISSING" -ne 0 ]]; then
  exit 1
fi

echo "Artifacts verified: $COUNT required entries are present under $LOCAL_DIR"
