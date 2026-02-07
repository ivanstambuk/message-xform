#!/bin/bash
#
# Start headless Chromium with remote debugging for browser_subagent (native Linux)
#
# Use this when:
# - browser_subagent gets "connection reset" or "timed out" errors
# - No Chrome/Chromium is running with CDP on port 9222
# - After a server reboot
#
# Prerequisites:
#   npx playwright install --with-deps chromium
#
# Usage: ./scripts/start-chrome.sh
#

set -e

DEBUG_PORT=9222
USER_DATA_DIR="/tmp/ag-cdp"

# Find Playwright's Chromium binary
CHROMIUM=$(find ~/.cache/ms-playwright -name "chrome" -path "*/chrome-linux*/chrome" -type f 2>/dev/null | head -1)

if [ -z "$CHROMIUM" ]; then
    # Try headless shell as fallback
    CHROMIUM=$(find ~/.cache/ms-playwright -name "headless_shell" -type f 2>/dev/null | head -1)
fi

if [ -z "$CHROMIUM" ]; then
    echo "❌ No Playwright Chromium found. Install with:"
    echo "   npx playwright install --with-deps chromium"
    exit 1
fi

echo "📍 Found Chromium: $CHROMIUM"

# Check if already running on the debug port
if curl -s --max-time 2 http://localhost:$DEBUG_PORT/json/version > /dev/null 2>&1; then
    echo "✅ Chrome CDP already running on port $DEBUG_PORT"
    curl -s http://localhost:$DEBUG_PORT/json/version | grep -E '"Browser"|"Protocol-Version"' || true
    exit 0
fi

# Kill any stale instances using our user-data-dir
pkill -f "ag-cdp" 2>/dev/null || true
sleep 1

echo "🚀 Starting Chromium with remote debugging on port $DEBUG_PORT..."

# Determine display — use Xvfb if available, otherwise headless
if [ -n "$DISPLAY" ] || xdpyinfo -display :99 > /dev/null 2>&1; then
    DISPLAY_TO_USE="${DISPLAY:-:99}"
    echo "   Using display: $DISPLAY_TO_USE"
    DISPLAY=$DISPLAY_TO_USE "$CHROMIUM" \
        --remote-debugging-port=$DEBUG_PORT \
        --user-data-dir=$USER_DATA_DIR \
        --no-first-run \
        --no-default-browser-check \
        --remote-allow-origins=* \
        --disable-popup-blocking \
        --disable-session-crashed-bubble \
        --disable-gpu \
        --no-sandbox \
        about:blank &
else
    echo "   No display found — running headless"
    "$CHROMIUM" \
        --headless=new \
        --remote-debugging-port=$DEBUG_PORT \
        --user-data-dir=$USER_DATA_DIR \
        --no-first-run \
        --no-default-browser-check \
        --remote-allow-origins=* \
        --disable-popup-blocking \
        --disable-session-crashed-bubble \
        --disable-gpu \
        --no-sandbox \
        about:blank &
fi

# Wait for startup
sleep 3

# Verify
if curl -s --max-time 5 http://localhost:$DEBUG_PORT/json/version > /dev/null 2>&1; then
    echo "✅ Chromium is running with remote debugging on port $DEBUG_PORT"
    curl -s http://localhost:$DEBUG_PORT/json/version | grep -E '"Browser"|"Protocol-Version"' || true
else
    echo "❌ Chromium may not have started correctly. Check logs above."
    exit 1
fi
