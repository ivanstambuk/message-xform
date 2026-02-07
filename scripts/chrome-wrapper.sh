#!/bin/bash
#
# Chrome wrapper for Antigravity browser_subagent on native Linux.
#

# Log invocation for debugging
echo "$(date): chrome-wrapper called with args: $@" >> /tmp/chrome-wrapper.log

# Ensure DISPLAY is set for Xvfb
export DISPLAY="${DISPLAY:-:99}"

exec /home/ivan/.cache/ms-playwright/chromium-1208/chrome-linux64/chrome \
    --no-sandbox \
    --no-first-run \
    --no-default-browser-check \
    --disable-search-engine-choice-screen \
    --disable-gpu \
    --enable-features=DownloadService \
    --disable-download-restriction \
    --download-default-directory=/home/ivan/Downloads \
    "$@"
