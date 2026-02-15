#!/usr/bin/env bash
set -euo pipefail

BOARD=".agent/session/active-work.yaml"
CMD="${1:-}"
if [[ -z "$CMD" ]]; then
  echo "usage: $0 <acquire|check|heartbeat|release|list> [options]" >&2
  exit 2
fi
shift || true

AGENT=""
TASK_ID=""
KIND=""
LEASE_SECONDS="900"
NOTE=""

declare -a PATHS=()
declare -a RESOURCES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --board)
      BOARD="$2"; shift 2 ;;
    --agent)
      AGENT="$2"; shift 2 ;;
    --task-id)
      TASK_ID="$2"; shift 2 ;;
    --kind)
      KIND="$2"; shift 2 ;;
    --lease-seconds)
      LEASE_SECONDS="$2"; shift 2 ;;
    --path)
      PATHS+=("$2"); shift 2 ;;
    --resource)
      RESOURCES+=("$2"); shift 2 ;;
    --note)
      NOTE="$2"; shift 2 ;;
    *)
      echo "unknown arg: $1" >&2
      exit 2 ;;
  esac
done

case "$CMD" in
  acquire|check)
    [[ -n "$AGENT" && -n "$TASK_ID" && -n "$KIND" ]] || { echo "--agent, --task-id, --kind required" >&2; exit 2; }
    ;;
  heartbeat|release)
    [[ -n "$AGENT" && -n "$TASK_ID" ]] || { echo "--agent and --task-id required" >&2; exit 2; }
    ;;
  list)
    ;;
  *)
    echo "unknown command: $CMD" >&2
    exit 2
    ;;
esac

if ! [[ "$LEASE_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "--lease-seconds must be an integer" >&2
  exit 2
fi

PATHS_NL=""
if [[ ${#PATHS[@]} -gt 0 ]]; then
  PATHS_NL="$(printf '%s\n' "${PATHS[@]}")"
fi
RESOURCES_NL=""
if [[ ${#RESOURCES[@]} -gt 0 ]]; then
  RESOURCES_NL="$(printf '%s\n' "${RESOURCES[@]}")"
fi

AGENT_LOCK_BOARD="$BOARD" \
AGENT_LOCK_CMD="$CMD" \
AGENT_LOCK_AGENT="$AGENT" \
AGENT_LOCK_TASK_ID="$TASK_ID" \
AGENT_LOCK_KIND="$KIND" \
AGENT_LOCK_LEASE_SECONDS="$LEASE_SECONDS" \
AGENT_LOCK_NOTE="$NOTE" \
AGENT_LOCK_PATHS_NL="$PATHS_NL" \
AGENT_LOCK_RESOURCES_NL="$RESOURCES_NL" \
python3 - <<'PY'
import fnmatch
import os
import sys
from datetime import datetime, timedelta, timezone

import fcntl
import yaml

HEADER = (
    "# Active work only.\n"
    "# Remove an entry immediately when the work completes or is aborted.\n"
    "# No completed history or done status in this file.\n"
    "# This file is updated by scripts/agent-lock.sh.\n"
    "# This file is machine-oriented for LLM agent coordination, not a history log.\n\n"
)


board = os.environ["AGENT_LOCK_BOARD"]
cmd = os.environ["AGENT_LOCK_CMD"]
agent = os.environ.get("AGENT_LOCK_AGENT", "")
task_id = os.environ.get("AGENT_LOCK_TASK_ID", "")
kind = os.environ.get("AGENT_LOCK_KIND", "")
lease_seconds = int(os.environ.get("AGENT_LOCK_LEASE_SECONDS", "900"))
note = os.environ.get("AGENT_LOCK_NOTE", "")
paths = [x.strip() for x in os.environ.get("AGENT_LOCK_PATHS_NL", "").splitlines() if x.strip()]
resources = [x.strip() for x in os.environ.get("AGENT_LOCK_RESOURCES_NL", "").splitlines() if x.strip()]


def now_utc():
    return datetime.now(timezone.utc)


def iso(dt: datetime):
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_iso(s: str):
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except Exception:
        return None


def ensure_schema(obj):
    if not isinstance(obj, dict):
        obj = {}
    version = obj.get("version") if isinstance(obj.get("version"), int) else 1
    defaults = obj.get("defaults") if isinstance(obj.get("defaults"), dict) else {}
    if not isinstance(defaults.get("lease_seconds"), int):
        defaults["lease_seconds"] = 900
    active_work = obj.get("active_work") if isinstance(obj.get("active_work"), list) else []
    return {
        "version": version,
        "defaults": defaults,
        "active_work": active_work,
    }


def norm_path(p: str):
    p = p.strip()
    if p.startswith("./"):
        p = p[2:]
    return p


def path_overlap(a: str, b: str):
    a = norm_path(a)
    b = norm_path(b)
    if not a or not b:
        return False
    if a == b:
        return True
    if a.startswith(b.rstrip("/") + "/") or b.startswith(a.rstrip("/") + "/"):
        return True
    if fnmatch.fnmatch(a, b) or fnmatch.fnmatch(b, a):
        return True
    return False


def entry_conflict(existing, wanted_paths, wanted_resources):
    ex_locks = existing.get("locks") or {}
    ex_paths = [norm_path(p) for p in (ex_locks.get("paths") or []) if isinstance(p, str)]
    ex_resources = [r for r in (ex_locks.get("resources") or []) if isinstance(r, str)]

    for r in wanted_resources:
        if r in ex_resources:
            return True, f"resource lock conflict: {r}"

    for p1 in wanted_paths:
        for p2 in ex_paths:
            if path_overlap(p1, p2):
                return True, f"path lock conflict: {p1} <-> {p2}"

    return False, ""


def prune_expired(entries, now):
    kept = []
    pruned = 0
    for e in entries:
        lease_until = e.get("lease_until")
        if not isinstance(lease_until, str):
            pruned += 1
            continue
        dt = parse_iso(lease_until)
        if dt is None or dt <= now:
            pruned += 1
            continue
        kept.append(e)
    return kept, pruned


def dump_with_header(fp, data):
    fp.seek(0)
    fp.truncate()
    fp.write(HEADER)
    yaml_text = yaml.safe_dump(data, sort_keys=False, default_flow_style=False)
    fp.write(yaml_text)
    fp.flush()
    os.fsync(fp.fileno())


def missing(msg):
    print(msg, file=sys.stderr)
    sys.exit(2)


os.makedirs(os.path.dirname(board), exist_ok=True)
fd = os.open(board, os.O_RDWR | os.O_CREAT, 0o664)
with os.fdopen(fd, "r+", encoding="utf-8") as f:
    fcntl.flock(f, fcntl.LOCK_EX)
    raw = f.read()
    parsed = yaml.safe_load(raw) if raw.strip() else {}
    state = ensure_schema(parsed)

    now = now_utc()
    state["active_work"], _ = prune_expired(state.get("active_work", []), now)

    if cmd == "list":
        dump_with_header(f, state)
        print(yaml.safe_dump(state, sort_keys=False, default_flow_style=False), end="")
        sys.exit(0)

    if cmd in {"acquire", "check"}:
        wanted_paths = [norm_path(p) for p in paths]
        wanted_resources = resources[:]

        if kind not in {"edit", "workflow"}:
            missing("--kind must be edit or workflow")

        others = [
            e
            for e in state["active_work"]
            if not (e.get("agent") == agent and e.get("task_id") == task_id)
        ]

        # Special retro lock behavior
        if kind == "workflow":
            for e in others:
                ex_resources = ((e.get("locks") or {}).get("resources") or [])
                if "workflow:retro" in ex_resources:
                    print(
                        f"LOCK_CONFLICT: workflow:retro held by {e.get('agent')}:{e.get('task_id')}",
                        file=sys.stderr,
                    )
                    dump_with_header(f, state)
                    sys.exit(3)

        if "workflow:retro" in wanted_resources and others:
            holder = others[0]
            print(
                f"LOCK_CONFLICT: cannot acquire workflow:retro while active lock exists ({holder.get('agent')}:{holder.get('task_id')})",
                file=sys.stderr,
            )
            dump_with_header(f, state)
            sys.exit(3)

        for e in others:
            conflict, reason = entry_conflict(e, wanted_paths, wanted_resources)
            if conflict:
                print(
                    f"LOCK_CONFLICT: {reason}; holder={e.get('agent')}:{e.get('task_id')}",
                    file=sys.stderr,
                )
                dump_with_header(f, state)
                sys.exit(3)

        if cmd == "check":
            dump_with_header(f, state)
            print("LOCK_OK")
            sys.exit(0)

        lease_until = now + timedelta(seconds=lease_seconds)
        state["active_work"] = [
            e
            for e in state["active_work"]
            if not (e.get("agent") == agent and e.get("task_id") == task_id)
        ]
        entry = {
            "agent": agent,
            "task_id": task_id,
            "kind": kind,
            "locks": {
                "paths": wanted_paths,
                "resources": wanted_resources,
            },
            "started_at": iso(now),
            "updated_at": iso(now),
            "lease_until": iso(lease_until),
        }
        if note:
            entry["note"] = note
        state["active_work"].append(entry)
        dump_with_header(f, state)
        print("LOCK_ACQUIRED")
        sys.exit(0)

    if cmd == "heartbeat":
        found = False
        lease_until = now + timedelta(seconds=lease_seconds)
        for e in state["active_work"]:
            if e.get("agent") == agent and e.get("task_id") == task_id:
                e["updated_at"] = iso(now)
                e["lease_until"] = iso(lease_until)
                found = True
                break
        if not found:
            dump_with_header(f, state)
            print(f"LOCK_NOT_FOUND: {agent}:{task_id}", file=sys.stderr)
            sys.exit(4)
        dump_with_header(f, state)
        print("LOCK_HEARTBEAT_OK")
        sys.exit(0)

    if cmd == "release":
        before = len(state["active_work"])
        state["active_work"] = [
            e
            for e in state["active_work"]
            if not (e.get("agent") == agent and e.get("task_id") == task_id)
        ]
        dump_with_header(f, state)
        if len(state["active_work"]) < before:
            print("LOCK_RELEASED")
        else:
            print("LOCK_NOT_FOUND")
        sys.exit(0)

    dump_with_header(f, state)
    print(f"unknown command: {cmd}", file=sys.stderr)
    sys.exit(2)
PY
