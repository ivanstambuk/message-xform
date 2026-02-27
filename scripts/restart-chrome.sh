#!/bin/bash
#
# Restart Chrome with remote debugging for browser_subagent
#
# Auto-detects environment:
#   - WSL: Uses Windows Chrome via PowerShell
#   - Native Linux: Uses Playwright Chromium in headless mode
#
# Use this when:
# - browser_subagent gets stuck loading a page
# - Chrome stops responding to CDP commands
# - "page not found" or "target closed" errors from browser tools
#
# NOTE: This only kills the Chrome instance with the 'ag-cdp' user-data-dir,
#       preserving your regular Chrome browser windows.
#
# Usage: ./scripts/restart-chrome.sh
#

set -e

DEBUG_PORT=9222

# Detect environment
if [ -d "/mnt/c/Windows" ]; then
    # ── WSL Environment ──────────────────────────────────────────────
    POWERSHELL="/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"
    CHROME_PATH="C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
    USER_DATA_DIR="C:\\Temp\\ag-cdp"

    echo "🛑 Stopping Chrome debugging instance (ag-cdp profile only)..."
    cd /mnt/c && $POWERSHELL -Command "
        Get-Process chrome -ErrorAction SilentlyContinue | ForEach-Object {
            try {
                \$wmi = Get-CimInstance Win32_Process -Filter \"ProcessId = \$(\$_.Id)\" -ErrorAction SilentlyContinue
                if (\$wmi.CommandLine -match 'ag-cdp') {
                    Stop-Process -Id \$_.Id -Force -ErrorAction SilentlyContinue
                    Write-Host \"  Killed PID \$(\$_.Id)\"
                }
            } catch {}
        }
    " 2>/dev/null || true

    sleep 2

    echo "🚀 Starting Chrome with remote debugging on port $DEBUG_PORT..."
    cd /mnt/c && $POWERSHELL -Command "Start-Process -FilePath '$CHROME_PATH' -ArgumentList '--remote-debugging-port=$DEBUG_PORT','--user-data-dir=$USER_DATA_DIR','--disable-search-engine-choice-screen','--no-first-run','--no-default-browser-check','--remote-allow-origins=*','--disable-popup-blocking','--disable-session-crashed-bubble','about:blank'"

    sleep 3
else
    # ── Native Linux Environment ─────────────────────────────────────
    # Find Playwright Chromium (preferred) or system chrome
    CHROME_BIN=""
    for candidate in \
        "$HOME/.cache/ms-playwright"/chromium-*/chrome-linux64/chrome \
        /usr/bin/google-chrome-stable \
        /usr/bin/google-chrome \
        /usr/bin/chromium-browser \
        /usr/bin/chromium; do
        if [ -x "$candidate" ]; then
            CHROME_BIN="$candidate"
            break
        fi
    done

    if [ -z "$CHROME_BIN" ]; then
        echo "❌ No Chrome/Chromium binary found. Install Playwright browsers: npx playwright install chromium"
        exit 1
    fi

    echo "🛑 Stopping Chrome debugging instance (ag-cdp profile only)..."
    # Only kill Chrome processes using the ag-cdp user-data-dir
    pkill -f "user-data-dir=/tmp/ag-cdp" 2>/dev/null || true
    sleep 1

    # Clear stale singleton lock (prevents "already running" errors after crash)
    rm -f /tmp/ag-cdp/SingletonLock 2>/dev/null || true

    # Prefer systemd service if available (handles auto-restart on crash + boot)
    if systemctl --user is-enabled chrome-cdp.service >/dev/null 2>&1; then
        echo "🔄 Restarting via systemd (chrome-cdp.service)..."
        systemctl --user restart chrome-cdp.service
        sleep 3
    else
        echo "🚀 Starting Chrome ($CHROME_BIN) with remote debugging on port $DEBUG_PORT..."
        nohup "$CHROME_BIN" \
            --headless=new \
            --remote-debugging-port=$DEBUG_PORT \
            --user-data-dir=/tmp/ag-cdp \
            --no-first-run \
            --no-default-browser-check \
            --remote-allow-origins=* \
            --disable-popup-blocking \
            --disable-session-crashed-bubble \
            --disable-gpu \
            --no-sandbox \
            about:blank \
            > /tmp/chrome-cdp.log 2>&1 &
        sleep 5
    fi
fi

# Verify Chrome is accessible
if curl -s --max-time 5 http://localhost:$DEBUG_PORT/json/version > /dev/null 2>&1; then
    echo "✅ Chrome is running with remote debugging on port $DEBUG_PORT"
    curl -s http://localhost:$DEBUG_PORT/json/version | grep -E '"Browser"|"Protocol-Version"' || true
elif ss -tlnp 2>/dev/null | grep -q ":$DEBUG_PORT"; then
    # Port is bound — headless Chromium sometimes doesn't expose the HTTP API
    # but browser_subagent connects via WebSocket which works fine
    echo "✅ Chrome is listening on port $DEBUG_PORT (WebSocket mode)"
    grep -o 'DevTools listening on ws://[^ ]*' /tmp/chrome-cdp.log 2>/dev/null || true
else
    echo "❌ Chrome is not running. Check log: /tmp/chrome-cdp.log"
    tail -5 /tmp/chrome-cdp.log 2>/dev/null || true
    exit 1
fi
