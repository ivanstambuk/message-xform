#!/usr/bin/env bash
# ==============================================================================
# import-journey.sh — Import any authentication journey into PingAM
# ==============================================================================
# Generic journey import script. Imports an AM authentication tree (nodes + tree)
# from the frodo-style JSON export format.
#
# Usage:
#   ./scripts/import-journey.sh <journey-file>
#   ./scripts/import-journey.sh config/journeys/WebAuthnJourney.journey.json
#   ./scripts/import-journey.sh config/journeys/UsernamelessJourney.journey.json
#
# The tree name is read from the JSON file's tree._id field.
#
# Prerequisites:
#   - PingAM running and configured
#   - Admin authentication working (ldapService chain)
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

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <journey-file>" >&2
    echo "" >&2
    echo "Available journeys:" >&2
    ls -1 "${PLATFORM_DIR}/config/journeys/"*.journey.json 2>/dev/null | while read -r f; do
        name=$(python3 -c "import json; print(json.load(open('$f'))['tree']['_id'])")
        echo "  $f → $name" >&2
    done
    exit 1
fi

JOURNEY_FILE="$1"
# Resolve relative paths against PLATFORM_DIR
if [[ ! -f "$JOURNEY_FILE" ]] && [[ -f "${PLATFORM_DIR}/${JOURNEY_FILE}" ]]; then
    JOURNEY_FILE="${PLATFORM_DIR}/${JOURNEY_FILE}"
fi

TREE_NAME=$(python3 -c "import json; print(json.load(open('${JOURNEY_FILE}'))['tree']['_id'])")

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

log "Importing journey '${TREE_NAME}' from ${JOURNEY_FILE}"

# ── Step 1: Authenticate as admin ────────────────────────────────────────────
log "Step 1/3 — Authenticating as amAdmin"
ADMIN_TOKEN=$(authenticate_admin)
if [[ -z "$ADMIN_TOKEN" ]]; then
    fail "Failed to authenticate as amAdmin"
fi
ok "Admin token obtained"

# ── Step 2: Import individual nodes ──────────────────────────────────────────
log "Step 2/3 — Importing authentication tree nodes"

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

# List all trees
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
echo "  Journey '${TREE_NAME}' imported!"
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
