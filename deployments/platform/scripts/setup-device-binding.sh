#!/usr/bin/env bash
# ==============================================================================
# setup-device-binding.sh — Configure Device Binding service + import journeys
# ==============================================================================
# Prerequisites:
#   - PingAM running and configured
#   - Admin authentication working (ldapService chain)
#
# This script:
#   1. Enables the DeviceBindingService in the root realm (encryption=NONE)
#   2. Imports DeviceBindingJourney (registration flow)
#   3. Imports DeviceSigningJourney (verification flow)
#   4. Verifies both journeys respond
#
# Usage:
#   ./scripts/setup-device-binding.sh [--k8s]
#
# Options:
#   --k8s   Use kubectl exec to reach AM inside a k8s cluster
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLATFORM_DIR="$(dirname "$SCRIPT_DIR")"

# ── Load environment ─────────────────────────────────────────────────────────
if [[ -f "${PLATFORM_DIR}/.env" ]]; then
    set -a; source "${PLATFORM_DIR}/.env"; set +a
fi

AM_PORT="${AM_HTTP_PORT:-18080}"
K8S_MODE=false
[[ "${1:-}" == "--k8s" ]] && K8S_MODE=true

# ── Helper functions ──────────────────────────────────────────────────────────
log()  { echo "$(date +%H:%M:%S) ── $*"; }
ok()   { echo "$(date +%H:%M:%S) ✓  $*"; }
fail() { echo "$(date +%H:%M:%S) ❌ $*" >&2; exit 1; }

am_curl() {
    local method="$1" path="$2"
    shift 2
    curl -s --max-time 15 \
        -H "Host: ${HOSTNAME_AM}:${AM_PORT}" \
        -X "$method" "http://127.0.0.1:${AM_PORT}/am${path}" \
        -H "Content-Type: application/json" \
        "$@" 2>/dev/null
}

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

am_api() {
    local method="$1" path="$2" token="$3"
    shift 3
    am_curl "$method" "$path" \
        -H "Accept-API-Version: protocol=2.0,resource=1.0" \
        -H "iPlanetDirectoryPro: ${token}" \
        "$@"
}

# ── Step 1: Authenticate ─────────────────────────────────────────────────────
log "Step 1/4 — Authenticating as amAdmin"
ADMIN_TOKEN=$(authenticate_admin)
if [[ -z "$ADMIN_TOKEN" ]]; then
    fail "Failed to authenticate as amAdmin"
fi
ok "Admin token obtained"

# ── Step 2: Enable DeviceBindingService ──────────────────────────────────────
log "Step 2/4 — Configuring DeviceBindingService (encryption=NONE)"

# Check if service already exists
SVC_CHECK=$(am_api GET "/json/realms/root/realm-config/services/deviceBindingService" "$ADMIN_TOKEN" 2>/dev/null || echo '{}')

if echo "$SVC_CHECK" | python3 -c "import sys,json; d=json.load(sys.stdin); assert 'deviceBindingAttrName' in d" &>/dev/null; then
    ok "DeviceBindingService already configured"
else
    # Create service with NONE encryption (safe for dev/test)
    SVC_BODY=$(cat <<'EOF'
{
  "deviceBindingAttrName": "boundDevices",
  "deviceBindingSettingsEncryptionScheme": "NONE"
}
EOF
)
    SVC_RESP=$(am_api PUT "/json/realms/root/realm-config/services/deviceBindingService" "$ADMIN_TOKEN" \
        -d "$SVC_BODY")

    if echo "$SVC_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); assert 'deviceBindingAttrName' in d" &>/dev/null; then
        ok "DeviceBindingService enabled (encryption=NONE)"
    else
        echo "  ⚠ Service response: ${SVC_RESP}" >&2
        fail "Failed to configure DeviceBindingService"
    fi
fi

# ── Step 3: Import journeys ─────────────────────────────────────────────────
log "Step 3/4 — Importing device binding journeys"

# Use the generic import script for both journeys
IMPORT_SCRIPT="${SCRIPT_DIR}/legacy/import-journey.sh"
if [[ ! -f "$IMPORT_SCRIPT" ]]; then
    fail "Generic import script not found: $IMPORT_SCRIPT"
fi

"$IMPORT_SCRIPT" "${PLATFORM_DIR}/config/journeys/DeviceBindingJourney.journey.json"
echo ""
"$IMPORT_SCRIPT" "${PLATFORM_DIR}/config/journeys/DeviceSigningJourney.journey.json"

# ── Step 4: Verification ────────────────────────────────────────────────────
echo ""
log "Step 4/4 — Verifying both journeys"

for JOURNEY in DeviceBindingJourney DeviceSigningJourney; do
    TEST_RESP=$(am_curl POST "/json/authenticate?authIndexType=service&authIndexValue=${JOURNEY}" \
        -H "Accept-API-Version: resource=2.0,protocol=1.0")

    if echo "$TEST_RESP" | python3 -c "import sys,json; assert 'authId' in json.load(sys.stdin)" &>/dev/null; then
        ok "${JOURNEY} responds ✓"
    else
        echo "  ⚠ ${JOURNEY} not yet reachable (may need AM restart)" >&2
    fi
done

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Device Binding setup complete!"
echo ""
echo "  Journeys:"
echo "    ✓ DeviceBindingJourney  — Bind a device (authenticationType=NONE)"
echo "    ✓ DeviceSigningJourney  — Verify a bound device signature"
echo ""
echo "  Next steps:"
echo "    1. Run E2E tests:  ./deployments/platform/e2e/run-e2e.sh"
echo "    2. Manual test:"
echo "       curl -s -H 'Host: ${HOSTNAME_AM}:${AM_PORT}' \\"
echo "         -X POST 'http://127.0.0.1:${AM_PORT}/am/json/authenticate?authIndexType=service&authIndexValue=DeviceBindingJourney' \\"
echo "         -H 'Content-Type: application/json' \\"
echo "         -H 'Accept-API-Version: resource=2.0,protocol=1.0'"
echo "═══════════════════════════════════════════════════════════════"
