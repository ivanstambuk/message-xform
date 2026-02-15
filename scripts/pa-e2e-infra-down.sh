#!/usr/bin/env bash
# ============================================================================
# PingAccess E2E — Infrastructure Teardown
# ============================================================================
# Tears down Docker containers and network created by pa-e2e-infra-up.sh.
#
# Safety: only tears down if the marker file .e2e-infra-started exists,
# meaning pa-e2e-infra-up.sh actually started the containers.  This avoids
# destroying infrastructure that was started manually or by another process.
#
# Usage:  bash scripts/pa-e2e-infra-down.sh
#         bash scripts/pa-e2e-infra-down.sh --force   # ignore marker check
# ============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARKER="$PROJECT_ROOT/.e2e-infra-started"

PA_CONTAINER="pa-e2e-test"
ECHO_CONTAINER="pa-e2e-echo"
OIDC_CONTAINER="pa-e2e-oidc"
OIDC_BACKEND_CONTAINER="pa-e2e-oidc-backend"

CYAN='\033[0;36m'
GREEN='\033[0;32m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[PASS]${NC}  $*"; }

# ---------------------------------------------------------------------------
# Marker check — only tear down what we started
# ---------------------------------------------------------------------------
FORCE=false
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=true ;;
    esac
done

if [[ "$FORCE" != "true" && ! -f "$MARKER" ]]; then
    info "Marker file not found — infrastructure was not started by Gradle. Skipping teardown."
    info "(Use --force to tear down anyway)"
    exit 0
fi

# ---------------------------------------------------------------------------
# Tear down
# ---------------------------------------------------------------------------
info "Tearing down E2E Docker infrastructure..."
docker rm -f "$PA_CONTAINER" "$ECHO_CONTAINER" "$OIDC_CONTAINER" "$OIDC_BACKEND_CONTAINER" 2>/dev/null || true
docker network rm pa-e2e-net 2>/dev/null || true
rm -rf "$PROJECT_ROOT/.e2e-oidc-certs" 2>/dev/null || true
rm -f "$MARKER"
ok "E2E infrastructure stopped"
