#!/usr/bin/env bash
# ==============================================================================
# configure-am-post.sh — Post-configuration setup for PingAM
# ==============================================================================
# Run AFTER configure-am.sh has completed initial setup.
# This script handles:
#   1. Create test users in PingDirectory
#   2. Verify admin authentication (callback-based)
#   3. Verify user authentication (callback-based)
#
# Authentication uses the callback pattern (2-step):
#   Step 1: POST /json/authenticate → get authId + callbacks
#   Step 2: POST /json/authenticate with filled callbacks → get tokenId
# This is the vendor-recommended approach. ZeroPageLogin (header-based)
# is deliberately kept disabled for security (Login CSRF protection).
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

AM_BASE="http://${HOSTNAME_AM}:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}"
RESOLVE="--resolve ${HOSTNAME_AM}:${AM_HTTP_PORT}:127.0.0.1"

# ── Helper functions ──────────────────────────────────────────────────────────

log()  { echo "$(date +%H:%M:%S) ── $*"; }
ok()   { echo "$(date +%H:%M:%S) ✓  $*"; }
fail() { echo "$(date +%H:%M:%S) ❌ $*" >&2; exit 1; }

# Authenticate using the callback pattern (2-step).
# Usage: authenticate_callback <username> <password>
# Returns: tokenId on stdout, or empty string on failure.
authenticate_callback() {
    local username="$1" password="$2"

    # Step 1: Get authId and callbacks
    local step1
    step1=$(curl -sf ${RESOLVE} \
        -X POST "${AM_BASE}/json/authenticate" \
        -H "Content-Type: application/json" \
        -H "Accept-API-Version: resource=2.0,protocol=1.0" 2>/dev/null || echo "")

    if [[ -z "$step1" ]]; then
        echo ""
        return
    fi

    local auth_id
    auth_id=$(echo "$step1" | python3 -c "import sys,json; print(json.load(sys.stdin)['authId'])" 2>/dev/null || echo "")

    if [[ -z "$auth_id" ]]; then
        echo ""
        return
    fi

    # Step 2: Return filled callbacks
    local step2
    step2=$(curl -sf ${RESOLVE} \
        -X POST "${AM_BASE}/json/authenticate" \
        -H "Content-Type: application/json" \
        -H "Accept-API-Version: resource=2.0,protocol=1.0" \
        -d "{
            \"authId\": \"${auth_id}\",
            \"callbacks\": [
                {\"type\":\"NameCallback\",\"input\":[{\"name\":\"IDToken1\",\"value\":\"${username}\"}],\"output\":[{\"name\":\"prompt\",\"value\":\"User Name\"}],\"_id\":0},
                {\"type\":\"PasswordCallback\",\"input\":[{\"name\":\"IDToken2\",\"value\":\"${password}\"}],\"output\":[{\"name\":\"prompt\",\"value\":\"Password\"}],\"_id\":1}
            ]
        }" 2>/dev/null || echo "")

    local token_id
    token_id=$(echo "$step2" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tokenId',''))" 2>/dev/null || echo "")
    echo "$token_id"
}

# ── Step 1: Create test users ────────────────────────────────────────────────
log "Step 1/3 — Creating test users in PingDirectory"

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

# ── Step 2: Verify admin authentication (callback-based) ────────────────────
log "Step 2/3 — Authenticating as amAdmin (callback-based)"
TOKEN=$(authenticate_callback "amAdmin" "${AM_ADMIN_PWD}")
if [[ -z "$TOKEN" ]]; then
    fail "Could not get admin token. Is AM configured?"
fi
ok "Admin token obtained (callback-based)"

# ── Step 3: Verify user authentication (callback-based) ─────────────────────
log "Step 3/3 — Verifying user.1 authentication (callback-based)"

USER_TOKEN=$(authenticate_callback "user.1" "Password1")
if [[ -n "$USER_TOKEN" ]]; then
    ok "user.1 authenticated successfully (callback-based)!"
else
    fail "user.1 authentication failed."
fi

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Post-configuration complete!"
echo ""
echo "  Test users:  user.1 through user.10"
echo "  Password:    Password1"
echo "  Auth tree:   ldapService (built-in, default)"
echo "  Auth method: Callback-based (ZeroPageLogin is disabled)"
echo ""
echo "  Authenticate via callbacks (2-step):"
echo ""
echo "  # Step 1: Get authId"
echo "    AUTH_ID=\$(curl -sf -X POST '${AM_BASE}/json/authenticate' \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'Accept-API-Version: resource=2.0,protocol=1.0' \\"
echo "      | python3 -c \"import sys,json; print(json.load(sys.stdin)['authId'])\")"
echo ""
echo "  # Step 2: Return filled callbacks"
echo "    curl -sf -X POST '${AM_BASE}/json/authenticate' \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'Accept-API-Version: resource=2.0,protocol=1.0' \\"
echo "      -d '{\"authId\":\"\${AUTH_ID}\",\"callbacks\":[...]}'"
echo "═══════════════════════════════════════════════════════════════"
