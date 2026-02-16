#!/usr/bin/env bash
# ==============================================================================
# configure-am-post.sh — Post-configuration setup for PingAM
# ==============================================================================
# Run AFTER configure-am.sh has completed initial setup.
# This script handles:
#   1. Create test users in PingDirectory
#   2. Enable ZeroPageLogin (header-based auth)
#   3. Optionally create a sub-realm (for multi-tenant scenarios)
#   4. Import custom journeys (if provided)
#   5. Verify user authentication works
#
# Usage:
#   ./scripts/configure-am-post.sh
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLATFORM_DIR="$(dirname "$SCRIPT_DIR")"

# ── Load environment ─────────────────────────────────────────────────────────
if [[ -f "${PLATFORM_DIR}/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "${PLATFORM_DIR}/.env"
    set +a
else
    echo "❌ No .env found."
    exit 1
fi

# ── Configuration ─────────────────────────────────────────────────────────────
AM_CONTAINER="${AM_CONTAINER:-platform-pingam}"
PD_CONTAINER="${PD_CONTAINER:-platform-pingdirectory}"
AM_HTTP_PORT="${AM_HTTP_PORT:-8080}"
AM_DEPLOYMENT_URI="${PINGAM_DEPLOYMENT_URI:-am}"
AM_ADMIN_PWD="${PINGAM_ADMIN_PASSWORD:-Password1}"
PD_PASSWORD="${PD_ROOT_USER_PWD:-2FederateM0re}"
PD_DN="${PD_ROOT_USER_DN:-cn=administrator}"

# ── Helper functions ──────────────────────────────────────────────────────────

log()  { echo "$(date +%H:%M:%S) ── $*"; }
ok()   { echo "$(date +%H:%M:%S) ✓  $*"; }
fail() { echo "$(date +%H:%M:%S) ❌ $*" >&2; exit 1; }

get_admin_token() {
    local token
    token=$(curl -sf \
        --resolve "${HOSTNAME_AM}:${AM_HTTP_PORT}:127.0.0.1" \
        -X POST "http://${HOSTNAME_AM}:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}/json/authenticate?authIndexType=service&authIndexValue=ldapService" \
        -H "Content-Type: application/json" \
        -H "X-OpenAM-Username: amAdmin" \
        -H "X-OpenAM-Password: ${AM_ADMIN_PWD}" \
        -H "Accept-API-Version: resource=2.0,protocol=1.0" 2>/dev/null \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['tokenId'])" 2>/dev/null)
    echo "$token"
}

am_put() {
    local path="$1" payload="$2" token="$3"
    curl -sf \
        --resolve "${HOSTNAME_AM}:${AM_HTTP_PORT}:127.0.0.1" \
        -X PUT "http://${HOSTNAME_AM}:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}/json${path}" \
        -H "iPlanetDirectoryPro: ${token}" \
        -H "Content-Type: application/json" \
        -H "Accept-API-Version: resource=1.0,protocol=2.1" \
        -d "$payload" 2>/dev/null
}

# ── Step 1: Create test users ────────────────────────────────────────────────
log "Step 1/4 — Creating test users in PingDirectory"

USERS_LDIF="${PLATFORM_DIR}/config/test-users.ldif"
if [[ ! -f "$USERS_LDIF" ]]; then
    fail "Test users LDIF not found: ${USERS_LDIF}"
fi

# Check if users already exist
USER_COUNT=$(docker exec "$PD_CONTAINER" /opt/out/instance/bin/ldapsearch \
    --hostname localhost --port 1636 --useSsl --trustAll \
    --bindDN "$PD_DN" --bindPassword "$PD_PASSWORD" \
    --baseDN "ou=People,${PD_BASE_DN}" \
    --searchScope one "(objectClass=inetOrgPerson)" dn 2>/dev/null \
    | grep -c "^dn:" || echo "0")

if [[ "$USER_COUNT" -ge 10 ]]; then
    ok "Test users already exist (${USER_COUNT} found). Skipping."
else
    docker cp "$USERS_LDIF" "${PD_CONTAINER}:/tmp/test-users.ldif"
    RESULT=$(docker exec "$PD_CONTAINER" /opt/out/instance/bin/ldapmodify \
        --hostname localhost --port 1636 --useSsl --trustAll \
        --bindDN "$PD_DN" --bindPassword "$PD_PASSWORD" \
        --filename /tmp/test-users.ldif 2>&1)
    
    SUCCESS_COUNT=$(echo "$RESULT" | grep -c "Result Code:  0" || echo "0")
    ok "Created ${SUCCESS_COUNT} test users"
fi

# ── Step 2: Get admin token ──────────────────────────────────────────────────
log "Step 2/4 — Authenticating as amAdmin"
TOKEN=$(get_admin_token)
if [[ -z "$TOKEN" ]]; then
    fail "Could not get admin token. Is AM configured?"
fi
ok "Admin token obtained"

# ── Step 3: Enable ZeroPageLogin ─────────────────────────────────────────────
log "Step 3/4 — Enabling ZeroPageLogin (header-based authentication)"

RESPONSE=$(am_put "/realms/root/realm-config/authentication" \
    '{"security":{"zeroPageLoginEnabled":true}}' \
    "$TOKEN")

ZPL_ENABLED=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('security',{}).get('zeroPageLoginEnabled'))" 2>/dev/null || echo "unknown")
if [[ "$ZPL_ENABLED" == "True" ]]; then
    ok "ZeroPageLogin enabled"
else
    log "Warning: ZeroPageLogin state: ${ZPL_ENABLED} (may already be enabled)"
fi

# ── Step 4: Verify user authentication ───────────────────────────────────────
log "Step 4/4 — Verifying user authentication"

AUTH_RESPONSE=$(curl -sf \
    --resolve "${HOSTNAME_AM}:${AM_HTTP_PORT}:127.0.0.1" \
    -X POST "http://${HOSTNAME_AM}:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}/json/authenticate" \
    -H "Content-Type: application/json" \
    -H "X-OpenAM-Username: user.1" \
    -H "X-OpenAM-Password: Password1" \
    -H "Accept-API-Version: resource=2.0,protocol=1.0" 2>/dev/null || echo "")

if echo "$AUTH_RESPONSE" | grep -q "tokenId"; then
    ok "user.1 authenticated successfully!"
else
    fail "user.1 authentication failed. Response: ${AUTH_RESPONSE}"
fi

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Post-configuration complete!"
echo ""
echo "  Test users:  user.1 through user.10"
echo "  Password:    Password1"
echo "  Auth tree:   ldapService (built-in, default)"
echo ""
echo "  Authenticate via:"
echo "    curl -X POST http://${HOSTNAME_AM}:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}/json/authenticate \\"
echo "      -H 'X-OpenAM-Username: user.1' \\"
echo "      -H 'X-OpenAM-Password: Password1' \\"
echo "      -H 'Accept-API-Version: resource=2.0,protocol=1.0'"
echo "═══════════════════════════════════════════════════════════════"
