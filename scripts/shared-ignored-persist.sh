#!/usr/bin/env bash
set -euo pipefail

START_MARKER="# >>> message-xform shared ignored artifacts >>>"
END_MARKER="# <<< message-xform shared ignored artifacts <<<"
FSTAB_PATH="/etc/fstab"

usage() {
  cat <<'USAGE'
Usage:
  scripts/shared-ignored-persist.sh <install|remove|status> [options]

Manage persistent bind mounts for ignored artifact paths via /etc/fstab.

Options:
  --manifest <path>   Path to artifacts manifest (default: artifacts/manifest.yaml)
  --all-worktrees     Apply to all worktrees in this repository
  --no-mount-now      (install only) do not mount immediately after updating /etc/fstab

Environment:
  MESSAGE_XFORM_SHARED_ROOT  Override shared_root from manifest
  SUDO_PASS                  Non-interactive sudo for automation
USAGE
}

ACTION="${1:-}"
if [[ -z "$ACTION" ]]; then
  usage >&2
  exit 2
fi
if [[ "$ACTION" == "-h" || "$ACTION" == "--help" ]]; then
  usage
  exit 0
fi
shift || true

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
MANIFEST="$REPO_ROOT/artifacts/manifest.yaml"
ALL_WORKTREES=false
MOUNT_NOW=true

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
    --no-mount-now)
      MOUNT_NOW=false
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

case "$ACTION" in
  install|remove|status)
    ;;
  *)
    echo "Unknown action: $ACTION" >&2
    usage >&2
    exit 2
    ;;
esac

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

escape_fstab_path() {
  local p="$1"
  p="${p//\\/\\\\}"
  p="${p// /\\040}"
  printf '%s' "$p"
}

strip_managed_block() {
  local input="$1"
  local output="$2"
  awk -v start="$START_MARKER" -v end="$END_MARKER" '
    index($0, start) {skip=1; next}
    index($0, end) {skip=0; next}
    !skip {print}
  ' "$input" > "$output"
}

read_fstab_to_file() {
  local out_file="$1"
  sudo_run cat "$FSTAB_PATH" > "$out_file"
}

backup_fstab() {
  local ts
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  sudo_run cp "$FSTAB_PATH" "$FSTAB_PATH.bak.message-xform.$ts"
}

write_fstab_from_file() {
  local src="$1"
  sudo_run install -m 644 "$src" "$FSTAB_PATH"
}

show_managed_block() {
  local fstab_file="$1"
  awk -v start="$START_MARKER" -v end="$END_MARKER" '
    index($0, start) {in_block=1; next}
    index($0, end) {in_block=0; next}
    in_block {print}
  ' "$fstab_file"
}

ensure_seeded() {
  local src="$1"
  local dst="$2"
  mkdir -p "$src" "$dst"
  if [[ -z "$(ls -A "$src" 2>/dev/null)" && -n "$(ls -A "$dst" 2>/dev/null)" ]]; then
    rsync -a "$dst"/ "$src"/
    echo "Seeded shared path before persistence: $src <= $dst"
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

tmp_fstab_raw="$(mktemp)"
tmp_fstab_nomarker="$(mktemp)"
tmp_fstab_new="$(mktemp)"
tmp_entries="$(mktemp)"
cleanup() {
  rm -f "$tmp_fstab_raw" "$tmp_fstab_nomarker" "$tmp_fstab_new" "$tmp_entries"
}
trap cleanup EXIT

if [[ "$ACTION" == "status" ]]; then
  read_fstab_to_file "$tmp_fstab_raw"
  if grep -Fq "$START_MARKER" "$tmp_fstab_raw"; then
    echo "Persistence: enabled"
    echo "Managed /etc/fstab entries:"
    show_managed_block "$tmp_fstab_raw"
  else
    echo "Persistence: disabled"
  fi
  if [[ "$ALL_WORKTREES" == "true" ]]; then
    "$SCRIPT_DIR/shared-ignored-status.sh" --manifest "$MANIFEST" --all-worktrees
  else
    "$SCRIPT_DIR/shared-ignored-status.sh" --manifest "$MANIFEST"
  fi
  exit 0
fi

if [[ "$ACTION" == "install" ]]; then
  : > "$tmp_entries"
  while IFS= read -r wt; do
    [[ -z "$wt" ]] && continue
    while IFS= read -r rel; do
      [[ -z "$rel" ]] && continue
      src="$SHARED_ROOT/$rel"
      dst="$wt/$rel"
      ensure_seeded "$src" "$dst"
      printf '%s %s none bind,nofail,x-systemd.automount 0 0\n' \
        "$(escape_fstab_path "$src")" \
        "$(escape_fstab_path "$dst")" >> "$tmp_entries"
    done <<< "$MOUNT_PATHS"
  done < <(worktree_roots)

  sort -u "$tmp_entries" -o "$tmp_entries"

  read_fstab_to_file "$tmp_fstab_raw"
  strip_managed_block "$tmp_fstab_raw" "$tmp_fstab_nomarker"

  {
    cat "$tmp_fstab_nomarker"
    echo
    echo "$START_MARKER"
    echo "# generated by scripts/shared-ignored-persist.sh"
    cat "$tmp_entries"
    echo "$END_MARKER"
    echo
  } > "$tmp_fstab_new"

  backup_fstab
  write_fstab_from_file "$tmp_fstab_new"
  echo "Updated $FSTAB_PATH with persistent shared ignored-artifact mounts."

  if [[ "$MOUNT_NOW" == "true" ]]; then
    if [[ "$ALL_WORKTREES" == "true" ]]; then
      "$SCRIPT_DIR/shared-ignored-mount.sh" --manifest "$MANIFEST" --all-worktrees
    else
      "$SCRIPT_DIR/shared-ignored-mount.sh" --manifest "$MANIFEST"
    fi
  fi
  exit 0
fi

read_fstab_to_file "$tmp_fstab_raw"
if grep -Fq "$START_MARKER" "$tmp_fstab_raw"; then
  strip_managed_block "$tmp_fstab_raw" "$tmp_fstab_nomarker"
  backup_fstab
  write_fstab_from_file "$tmp_fstab_nomarker"
  echo "Removed managed shared ignored-artifact block from $FSTAB_PATH."
else
  echo "No managed shared ignored-artifact block found in $FSTAB_PATH."
fi
