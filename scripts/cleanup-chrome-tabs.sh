#!/bin/bash
#
# Close all Chrome tabs in the ag-cdp debugging instance, keeping one blank tab.
#
# This prevents SSE connection exhaustion when browser_subagent creates
# many tabs during development/testing.
#
# Usage: ./scripts/cleanup-chrome-tabs.sh
#

set -e

DEBUG_PORT=9222
CDP_URL="http://localhost:$DEBUG_PORT"

# Check if Chrome is accessible
if ! curl -s --max-time 2 "$CDP_URL/json/version" > /dev/null 2>&1; then
    echo "❌ Chrome not accessible on port $DEBUG_PORT"
    echo "   Run ./scripts/start-chrome.sh first"
    exit 1
fi

# Get list of all pages
PAGES=$(curl -s "$CDP_URL/json/list" 2>/dev/null)
PAGE_COUNT=$(echo "$PAGES" | grep -c '"type": "page"' || echo 0)

if [ "$PAGE_COUNT" -le 1 ]; then
    echo "✅ Only $PAGE_COUNT tab(s) open, no cleanup needed"
    exit 0
fi

echo "🧹 Cleaning up $PAGE_COUNT Chrome tabs..."

# Create a fresh about:blank tab first (so we always have at least one)
NEW_TAB=$(curl -s -X PUT "$CDP_URL/json/new?about:blank" 2>/dev/null)
NEW_TAB_ID=$(echo "$NEW_TAB" | grep -o '"id": "[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$NEW_TAB_ID" ]; then
    echo "❌ Failed to create new blank tab"
    exit 1
fi

echo "   Created new blank tab: $NEW_TAB_ID"

# Close all other pages
echo "$PAGES" | grep '"id":' | while read -r line; do
    PAGE_ID=$(echo "$line" | grep -o '"id": "[^"]*"' | cut -d'"' -f4)
    if [ -n "$PAGE_ID" ] && [ "$PAGE_ID" != "$NEW_TAB_ID" ]; then
        if curl -s "$CDP_URL/json/close/$PAGE_ID" > /dev/null 2>&1; then
            echo "   Closed: $PAGE_ID"
        fi
    fi
done

# Verify cleanup
sleep 0.5
REMAINING=$(curl -s "$CDP_URL/json/list" 2>/dev/null | grep -c '"type": "page"' || echo 0)
echo "✅ Chrome tabs: $PAGE_COUNT → $REMAINING"
