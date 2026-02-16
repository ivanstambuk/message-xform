#!/usr/bin/env bash
# ==============================================================================
# configure-pa.sh — Configure PingAccess to reverse-proxy PingAM
# ==============================================================================
# Configures PA to route all traffic under /am/* to PingAM's HTTP backend.
# This is a pure reverse-proxy setup — no authentication rules, no plugin.
# The message-xform plugin JAR can be mounted later in Phase 7.
#
# PA Admin API objects created:
#   1. Virtual Host  — pa.platform.local:3000 (or use existing default)
#   2. Site           — PingAM backend (am.platform.local:8080)
#   3. Application    — context root /am, type Web+API
#   4. Root Resource  — unprotected, wildcard, no rules
#
# Prerequisites:
#   - PingAccess container running (Admin API on port 19000)
#   - PingAM container running (HTTP on port 8080 on Docker network)
#
# Usage:
#   ./scripts/configure-pa.sh
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
PA_ADMIN_URL="https://localhost:${PA_ADMIN_PORT:-19000}/pa-admin-api/v3"
PA_USER="administrator"
PA_PASS="2Access"
AM_BACKEND_HOST="${HOSTNAME_AM:-am.platform.local}"
AM_BACKEND_PORT="8080"

# ── Helper functions ──────────────────────────────────────────────────────────
log()  { echo "$(date +%H:%M:%S) ── $*"; }
ok()   { echo "$(date +%H:%M:%S) ✓  $*"; }
fail() { echo "$(date +%H:%M:%S) ❌ $*" >&2; exit 1; }

pa_api() {
    local method="$1" path="$2"
    shift 2
    curl -sk -u "${PA_USER}:${PA_PASS}" \
         -H 'X-XSRF-Header: PingAccess' \
         -H 'Content-Type: application/json' \
         -X "$method" "${PA_ADMIN_URL}${path}" "$@" 2>/dev/null
}

# ── Step 1: Verify PA is ready ───────────────────────────────────────────────
log "Step 1/6 — Verifying PingAccess is ready"
VERSION=$(pa_api GET /version | python3 -c "import sys,json; print(json.load(sys.stdin).get('version',''))" 2>/dev/null || echo "")
if [[ -z "$VERSION" ]]; then
    fail "Cannot reach PA Admin API at ${PA_ADMIN_URL}"
fi
ok "PingAccess ${VERSION} is ready"

# ── Step 2: Check/Create Virtual Host ────────────────────────────────────────
log "Step 2/6 — Checking virtual hosts"

# PA ships with a default VH at localhost:3000. We'll use it for now.
# For the platform deployment, we create pa.platform.local:3000 if needed.
EXISTING_VHS=$(pa_api GET /virtualhosts)
VH_COUNT=$(echo "$EXISTING_VHS" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('items',[])))" 2>/dev/null || echo "0")
DEFAULT_VH_ID=$(echo "$EXISTING_VHS" | python3 -c "import sys,json; items=json.load(sys.stdin).get('items',[]); print(items[0]['id'] if items else '')" 2>/dev/null || echo "")

if [[ -n "$DEFAULT_VH_ID" ]]; then
    ok "Using existing virtual host (id=${DEFAULT_VH_ID}, ${VH_COUNT} total)"
else
    # Create a virtual host
    VH_RESPONSE=$(pa_api POST /virtualhosts -d "{
        \"host\": \"${HOSTNAME_PA}\",
        \"port\": 3000,
        \"agentResourceCacheTTL\": 0,
        \"keyPairId\": 0,
        \"trustedCertificateGroupId\": 0
    }")
    DEFAULT_VH_ID=$(echo "$VH_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
    if [[ -z "$DEFAULT_VH_ID" ]]; then
        fail "Failed to create virtual host. Response: ${VH_RESPONSE}"
    fi
    ok "Created virtual host ${HOSTNAME_PA}:3000 (id=${DEFAULT_VH_ID})"
fi

# ── Step 3: Create Site (PingAM backend) ─────────────────────────────────────
log "Step 3/6 — Creating Site (PingAM backend)"

# Check if site already exists
EXISTING_SITES=$(pa_api GET /sites)
EXISTING_SITE_ID=$(echo "$EXISTING_SITES" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
for s in items:
    if 'PingAM' in s.get('name',''):
        print(s['id'])
        break
else:
    print('')
" 2>/dev/null || echo "")

if [[ -n "$EXISTING_SITE_ID" ]]; then
    ok "PingAM site already exists (id=${EXISTING_SITE_ID}). Skipping."
    SITE_ID="$EXISTING_SITE_ID"
else
    SITE_RESPONSE=$(pa_api POST /sites -d "{
        \"name\": \"PingAM Backend\",
        \"targets\": [\"${AM_BACKEND_HOST}:${AM_BACKEND_PORT}\"],
        \"secure\": false,
        \"maxConnections\": 10,
        \"sendPaCookie\": false,
        \"availabilityProfileId\": 1,
        \"trustedCertificateGroupId\": 0
    }")
    SITE_ID=$(echo "$SITE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
    if [[ -z "$SITE_ID" ]]; then
        fail "Failed to create site. Response: ${SITE_RESPONSE}"
    fi
    ok "Created site 'PingAM Backend' → ${AM_BACKEND_HOST}:${AM_BACKEND_PORT} (id=${SITE_ID})"
fi

# ── Step 4: Create Application ───────────────────────────────────────────────
log "Step 4/6 — Creating Application (/am)"

# Check if application already exists
EXISTING_APPS=$(pa_api GET /applications)
EXISTING_APP_ID=$(echo "$EXISTING_APPS" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
for a in items:
    if a.get('contextRoot','') == '/am':
        print(a['id'])
        break
else:
    print('')
" 2>/dev/null || echo "")

if [[ -n "$EXISTING_APP_ID" ]]; then
    ok "Application /am already exists (id=${EXISTING_APP_ID}). Skipping."
    APP_ID="$EXISTING_APP_ID"
else
    APP_RESPONSE=$(pa_api POST /applications -d "{
        \"name\": \"PingAM Proxy\",
        \"contextRoot\": \"/am\",
        \"defaultAuthType\": \"Web\",
        \"applicationType\": \"Web\",
        \"spaSupportEnabled\": false,
        \"destination\": \"Site\",
        \"siteId\": ${SITE_ID},
        \"virtualHostIds\": [${DEFAULT_VH_ID}],
        \"caseSensitivePath\": false,
        \"webSessionId\": 0
    }")
    APP_ID=$(echo "$APP_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
    if [[ -z "$APP_ID" ]]; then
        fail "Failed to create application. Response: ${APP_RESPONSE}"
    fi
    ok "Created application 'PingAM Proxy' /am (id=${APP_ID})"
fi

# ── Step 5: Enable the Application ──────────────────────────────────────────
log "Step 5/6 — Enabling application"

# GET current state, modify, PUT back (full-replacement pattern)
CURRENT_APP=$(pa_api GET "/applications/${APP_ID}")
ENABLED=$(echo "$CURRENT_APP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('enabled',False))" 2>/dev/null || echo "")

if [[ "$ENABLED" == "True" ]]; then
    ok "Application already enabled"
else
    # Build the PUT body from the current state + enabled=true
    ENABLE_BODY=$(echo "$CURRENT_APP" | python3 -c "
import sys,json
app = json.load(sys.stdin)
app['enabled'] = True
# Remove read-only fields that PA doesn't accept on PUT
for field in ['resourceOrder']:
    app.pop(field, None)
json.dump(app, sys.stdout)
" 2>/dev/null)

    ENABLE_RESPONSE=$(pa_api PUT "/applications/${APP_ID}" -d "$ENABLE_BODY")
    ENABLED_NOW=$(echo "$ENABLE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('enabled',False))" 2>/dev/null || echo "")
    if [[ "$ENABLED_NOW" == "True" ]]; then
        ok "Application enabled"
    else
        fail "Failed to enable application. Response: ${ENABLE_RESPONSE}"
    fi
fi

# ── Step 6: Configure Root Resource (unprotected) ────────────────────────────
log "Step 6/6 — Configuring root resource (unprotected)"

RESOURCES=$(pa_api GET "/applications/${APP_ID}/resources")
ROOT_RESOURCE_ID=$(echo "$RESOURCES" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
for r in items:
    if r.get('rootResource', False):
        print(r['id'])
        break
else:
    print(items[0]['id'] if items else '')
" 2>/dev/null || echo "")

if [[ -z "$ROOT_RESOURCE_ID" ]]; then
    fail "Could not find root resource for application ${APP_ID}"
fi

# Update root resource to be unprotected (no auth needed for basic proxy)
CURRENT_RESOURCE=$(pa_api GET "/applications/${APP_ID}/resources/${ROOT_RESOURCE_ID}")
RESOURCE_BODY=$(echo "$CURRENT_RESOURCE" | python3 -c "
import sys,json
r = json.load(sys.stdin)
r['unprotected'] = True
r['enabled'] = True
r['methods'] = ['*']
r['pathPatterns'] = [{'type': 'WILDCARD', 'pattern': '/*'}]
json.dump(r, sys.stdout)
" 2>/dev/null)

RESOURCE_RESPONSE=$(pa_api PUT "/applications/${APP_ID}/resources/${ROOT_RESOURCE_ID}" -d "$RESOURCE_BODY")
UNPROTECTED=$(echo "$RESOURCE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('unprotected',False))" 2>/dev/null || echo "")
if [[ "$UNPROTECTED" == "True" ]]; then
    ok "Root resource set to unprotected"
else
    log "Warning: Root resource may not be unprotected. Response: ${RESOURCE_RESPONSE}"
fi

# ── Verification ─────────────────────────────────────────────────────────────
echo ""
log "Verifying proxy — accessing PingAM through PingAccess..."

# PA engine runs on 3000 inside container, mapped to 13000 on host
# IMPORTANT: Host header must match the VH (localhost:3000)
PROXY_TEST=$(curl -sk \
    -H "Host: localhost:3000" \
    -o /dev/null -w "%{http_code}" \
    "https://localhost:13000/am/json/health/live" 2>/dev/null || echo "000")

if [[ "$PROXY_TEST" -ge 200 && "$PROXY_TEST" -lt 500 ]]; then
    ok "PingAM accessible through PingAccess! (HTTP ${PROXY_TEST})"
else
    log "Warning: Proxy test returned HTTP ${PROXY_TEST}"
    log "This may be expected if AM health endpoint requires auth"
    # Try the AM root page
    PROXY_ROOT=$(curl -sk \
        -H "Host: localhost:3000" \
        -o /dev/null -w "%{http_code}" \
        "https://localhost:13000/am/" 2>/dev/null || echo "000")
    if [[ "$PROXY_ROOT" -ge 200 && "$PROXY_ROOT" -lt 500 ]]; then
        ok "PingAM root page accessible through PingAccess! (HTTP ${PROXY_ROOT})"
    else
        log "Proxy root returned HTTP ${PROXY_ROOT} — see troubleshooting"
    fi
fi

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  PingAccess configuration complete!"
echo ""
echo "  Admin API:   https://localhost:19000/pa-admin-api/v3"
echo "  Admin user:  administrator / 2Access"
echo "  Engine URL:  https://localhost:13000"
echo ""
echo "  Site:        PingAM Backend → ${AM_BACKEND_HOST}:${AM_BACKEND_PORT}"
echo "  Application: /am → PingAM (Web type, unprotected)"
echo ""
echo "  Access PingAM through PingAccess:"
echo "    curl -sk -H 'Host: localhost:3000' https://localhost:13000/am/"
echo "═══════════════════════════════════════════════════════════════"
