#!/bin/bash
# cleanup-antigravity.sh
# Detects and optionally cleans up orphaned Antigravity processes.

REPORT_ONLY=${1:-"true"}

echo "Checking for orphaned Antigravity processes..."

# 1. Find PIDs where the parent is 1 (orphaned) AND path contains .antigravity-server
ORPHANS=$(ps -ef | grep ".antigravity-server" | awk '$3 == 1 {print $2}')

if [ -z "$ORPHANS" ]; then
    echo "✅ No orphaned .antigravity-server processes found."
else
    echo "⚠️ Found orphaned .antigravity-server processes: $ORPHANS"
    if [ "$REPORT_ONLY" = "false" ]; then
        echo "Killing orphans..."
        kill -15 $ORPHANS
        sleep 2
        kill -9 $ORPHANS 2>/dev/null
        echo "Cleanup complete."
    else
        echo "Run with 'false' argument to kill them."
    fi
fi

# 2. Check for pyrefly ghosts (older than 1 hour)
# We use pkill -c to just count them if reporting only
GHOST_COUNT=$(pgrep -f "pyrefly" | wc -l)
if [ "$GHOST_COUNT" -gt 0 ]; then
    # Use find-style check for age if possible, or just report existence
    # For now, let's just report total count as the user suggested pkill --older
    echo "ℹ️ Found $GHOST_COUNT pyrefly processes."
    if [ "$REPORT_ONLY" = "false" ]; then
        echo "Cleaning up pyrefly processes older than 1 hour..."
        pkill -f "pyrefly" --older 3600
    fi
else
    echo "✅ No pyrefly processes found."
fi
