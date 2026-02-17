#!/usr/bin/env bash
# ==============================================================================
# import-webauthn-journey.sh — Import WebAuthn journey into PingAM
# ==============================================================================
# Imports the WebAuthnJourney authentication tree into PingAM using the
# REST API. This creates individual nodes first, then the tree that wires
# them together.
#
# The journey supports two flows:
#   1. Registration: Username → Password → DataStore → WebAuthn Register
#   2. Authentication: Username → WebAuthn Authenticate (if device registered)
#
# IMPORTANT: AM 8.0's authIndexType=service resolves chains, not trees
# directly. We import the tree via the trees REST API, which is sufficient.
# AM recognises the tree name and routes to it when the name matches.
#
# Prerequisites:
#   - PingAM running and configured
#   - Admin authentication working (ldapService chain)
#
# Usage:
#   ./scripts/import-webauthn-journey.sh
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLATFORM_DIR="$(dirname "$SCRIPT_DIR")"

# ── Load environment ─────────────────────────────────────────────────────────
if [[ -f "${PLATFORM_DIR}/.env" ]]; then
    set -a; source "${PLATFORM_DIR}/.env"; set +a
fi

# ── Configuration ─────────────────────────────────────────────────────────────
AM_PORT="${AM_HTTP_PORT:-18080}"
JOURNEY_FILE="${PLATFORM_DIR}/config/journeys/WebAuthnJourney.journey.json"
TREE_NAME="WebAuthnJourney"

# ── Helper functions ──────────────────────────────────────────────────────────
log()  { echo "$(date +%H:%M:%S) ── $*"; }
ok()   { echo "$(date +%H:%M:%S) ✓  $*"; }
fail() { echo "$(date +%H:%M:%S) ❌ $*" >&2; exit 1; }

# AM API call — uses Host header + 127.0.0.1 to avoid DNS issues
am_curl() {
    local method="$1" path="$2"
    shift 2
    curl -s --max-time 15 \
        -H "Host: ${HOSTNAME_AM}:${AM_PORT}" \
        -X "$method" "http://127.0.0.1:${AM_PORT}/am${path}" \
        -H "Content-Type: application/json" \
        "$@" 2>/dev/null
}

# Callback-based admin authentication (uses ldapService explicitly)
authenticate_admin() {
    local step1
    step1=$(am_curl POST "/json/authenticate?authIndexType=service&authIndexValue=ldapService" \
        -H "Accept-API-Version: resource=2.0,protocol=1.0")

    if ! echo "$step1" | python3 -c "import sys,json; json.load(sys.stdin)['authId']" &>/dev/null; then
        echo ""
        return 1
    fi

    local step2
    step2=$(echo "$step1" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for cb in d['callbacks']:
    if cb['type']=='NameCallback': cb['input'][0]['value']='amAdmin'
    elif cb['type']=='PasswordCallback': cb['input'][0]['value']='Password1'
json.dump(d, sys.stdout)")

    local result
    result=$(am_curl POST "/json/authenticate?authIndexType=service&authIndexValue=ldapService" \
        -H "Accept-API-Version: resource=2.0,protocol=1.0" \
        -d "$step2")

    echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tokenId',''))" 2>/dev/null
}

# AM REST API call with admin token
am_api() {
    local method="$1" path="$2" token="$3"
    shift 3
    am_curl "$method" "$path" \
        -H "Accept-API-Version: protocol=2.0,resource=1.0" \
        -H "iPlanetDirectoryPro: ${token}" \
        "$@"
}

# ── Validation ───────────────────────────────────────────────────────────────
if [[ ! -f "$JOURNEY_FILE" ]]; then
    fail "Journey file not found: ${JOURNEY_FILE}"
fi

# ── Step 1: Authenticate as admin ────────────────────────────────────────────
log "Step 1/3 — Authenticating as amAdmin"
ADMIN_TOKEN=$(authenticate_admin)
if [[ -z "$ADMIN_TOKEN" ]]; then
    fail "Failed to authenticate as amAdmin"
fi
ok "Admin token obtained"

# ── Step 2: Import individual nodes ──────────────────────────────────────────
log "Step 2/3 — Importing authentication tree nodes"

NODE_COUNT=0
NODE_ERRORS=0

# Parse and import each node
python3 -c "
import json
with open('${JOURNEY_FILE}') as f:
    j = json.load(f)
for node_id, node in j['nodes'].items():
    node_type = node['_type']['_id']
    body = {k: v for k, v in node.items() if k not in ('_type', '_outcomes', '_id', '_rev')}
    print(f'{node_id}|{node_type}|' + json.dumps(body))
" | while IFS='|' read -r node_id node_type node_body; do
    RESPONSE=$(am_api PUT \
        "/json/realms/root/realm-config/authentication/authenticationtrees/nodes/${node_type}/${node_id}" \
        "$ADMIN_TOKEN" \
        -d "$node_body")

    if echo "$RESPONSE" | python3 -c "import sys,json; assert '_id' in json.load(sys.stdin)" &>/dev/null; then
        ok "  Node: ${node_type} (${node_id})"
    else
        echo "  ⚠ Node ${node_id} (${node_type}) response: ${RESPONSE}" >&2
    fi
done

# ── Step 3: Import the tree ──────────────────────────────────────────────────
log "Step 3/3 — Importing authentication tree '${TREE_NAME}'"

TREE_BODY=$(python3 -c "
import json
with open('${JOURNEY_FILE}') as f:
    j = json.load(f)
tree = j['tree']
tree.pop('_rev', None)
print(json.dumps(tree))
")

TREE_RESPONSE=$(am_api PUT \
    "/json/realms/root/realm-config/authentication/authenticationtrees/trees/${TREE_NAME}" \
    "$ADMIN_TOKEN" \
    -d "$TREE_BODY")

if echo "$TREE_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d['_id']=='${TREE_NAME}'" &>/dev/null; then
    ok "Tree '${TREE_NAME}' imported successfully"
else
    fail "Tree import failed. Response: ${TREE_RESPONSE}"
fi

# ── Verification ─────────────────────────────────────────────────────────────
log "Verifying..."

# Test that the journey can be invoked
TEST_RESP=$(am_curl POST "/json/authenticate?authIndexType=service&authIndexValue=${TREE_NAME}" \
    -H "Accept-API-Version: resource=2.0,protocol=1.0")

if echo "$TEST_RESP" | python3 -c "import sys,json; assert 'authId' in json.load(sys.stdin)" &>/dev/null; then
    ok "Journey '${TREE_NAME}' responds to authIndexType=service ✓"
else
    echo "  ⚠ Journey not yet reachable via authIndexType=service (may need AM restart)"
fi

# List trees
TREES=$(am_api GET \
    "/json/realms/root/realm-config/authentication/authenticationtrees/trees?_queryFilter=true" \
    "$ADMIN_TOKEN")

TREE_NAMES=$(echo "$TREES" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for tree in d.get('result', []):
    status = '✓' if tree.get('enabled') else '✗'
    print(f'  {status} {tree[\"_id\"]}')
" 2>/dev/null || echo "  (could not list trees)")

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  WebAuthn Journey imported!"
echo ""
echo "  Tree name:       ${TREE_NAME}"
echo "  RP Domain:       localhost"
echo "  RP Name:         Platform Local"
echo "  Origin:          https://localhost:13000"
echo "  Signing algos:   ES256, RS256"
echo ""
echo "  Journey flow:"
echo "    Start → Username Collector"
echo "      → WebAuthn Authentication (if device registered → Success)"
echo "      → No Device? → Password → DataStore Decision"
echo "        → WebAuthn Registration → Recovery Codes → back to Auth"
echo ""
echo "  Available trees:"
echo "${TREE_NAMES}"
echo ""
echo "  Test with:"
echo "    curl -s -H 'Host: ${HOSTNAME_AM}:${AM_PORT}' \\"
echo "      -X POST 'http://127.0.0.1:${AM_PORT}/am/json/authenticate?authIndexType=service&authIndexValue=${TREE_NAME}' \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'Accept-API-Version: resource=2.0,protocol=1.0'"
echo "═══════════════════════════════════════════════════════════════"
