#!/usr/bin/env bash
# ==============================================================================
# configure-pa-api.sh — Configure PingAccess Application for clean /api URLs
# ==============================================================================
# Creates a second PA Application at context root /api that routes to the
# same PingAM backend site. This enables clean URLs:
#
#   POST /api/v1/auth/login                → AM authenticate (default tree)
#   POST /api/v1/auth/passkey              → AM authenticate + WebAuthnJourney
#   POST /api/v1/auth/passkey/usernameless → AM authenticate + UsernamelessJourney
#
# The message-xform profile (platform-am v4.0.0) handles URL rewriting on
# the request side, converting clean paths to /am/json/authenticate with
# the correct journey query parameters.
#
# Prerequisites:
#   - PingAccess container running (Admin API on port 19000)
#   - PingAM backend site already created (by configure-pa.sh)
#   - Message-xform plugin rule already wired (by configure-pa-plugin.sh)
#
# Usage:
#   ./scripts/configure-pa-api.sh
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
log "Step 1/5 — Verifying PingAccess is ready"
VERSION=$(pa_api GET /version | python3 -c "import sys,json; print(json.load(sys.stdin).get('version',''))" 2>/dev/null || echo "")
if [[ -z "$VERSION" ]]; then
    fail "Cannot reach PA Admin API at ${PA_ADMIN_URL}"
fi
ok "PingAccess ${VERSION} is ready"

# ── Step 2: Find existing PingAM site ────────────────────────────────────────
log "Step 2/5 — Finding PingAM backend site"

EXISTING_SITES=$(pa_api GET /sites)
SITE_ID=$(echo "$EXISTING_SITES" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
for s in items:
    if 'PingAM' in s.get('name',''):
        print(s['id'])
        break
else:
    print('')
" 2>/dev/null || echo "")

if [[ -z "$SITE_ID" ]]; then
    fail "PingAM backend site not found. Run configure-pa.sh first."
fi
ok "Found PingAM backend site (id=${SITE_ID})"

# ── Step 3: Find default Virtual Host ────────────────────────────────────────
log "Step 3/5 — Finding virtual host"

EXISTING_VHS=$(pa_api GET /virtualhosts)
DEFAULT_VH_ID=$(echo "$EXISTING_VHS" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
print(items[0]['id'] if items else '')
" 2>/dev/null || echo "")

if [[ -z "$DEFAULT_VH_ID" ]]; then
    fail "No virtual hosts found."
fi
ok "Using virtual host (id=${DEFAULT_VH_ID})"

# ── Step 4: Create/find Application (/api) ───────────────────────────────────
log "Step 4/5 — Creating Application (/api)"

EXISTING_APPS=$(pa_api GET /applications)
EXISTING_APP_ID=$(echo "$EXISTING_APPS" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
for a in items:
    if a.get('contextRoot','') == '/api':
        print(a['id'])
        break
else:
    print('')
" 2>/dev/null || echo "")

if [[ -n "$EXISTING_APP_ID" ]]; then
    ok "Application /api already exists (id=${EXISTING_APP_ID}). Skipping."
    APP_ID="$EXISTING_APP_ID"
else
    APP_RESPONSE=$(pa_api POST /applications -d "{
        \"name\": \"Auth API\",
        \"contextRoot\": \"/api\",
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
    ok "Created application 'Auth API' /api (id=${APP_ID})"
fi

# ── Step 5: Enable + Configure Root Resource ─────────────────────────────────
log "Step 5/5 — Enabling application and configuring root resource"

# Enable the application
CURRENT_APP=$(pa_api GET "/applications/${APP_ID}")
ENABLED=$(echo "$CURRENT_APP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('enabled',False))" 2>/dev/null || echo "")

if [[ "$ENABLED" != "True" ]]; then
    ENABLE_BODY=$(echo "$CURRENT_APP" | python3 -c "
import sys,json
app = json.load(sys.stdin)
app['enabled'] = True
for field in ['resourceOrder']:
    app.pop(field, None)
json.dump(app, sys.stdout)
" 2>/dev/null)
    pa_api PUT "/applications/${APP_ID}" -d "$ENABLE_BODY" > /dev/null
    ok "Application enabled"
else
    ok "Application already enabled"
fi

# Configure root resource as unprotected
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

pa_api PUT "/applications/${APP_ID}/resources/${ROOT_RESOURCE_ID}" -d "$RESOURCE_BODY" > /dev/null
ok "Root resource configured (unprotected, wildcard)"

# ── Step 6: Attach message-xform rule to /api application ────────────────────
log "Step 6 — Attaching message-xform rule"

# Find the existing MessageTransformRule
EXISTING_RULES=$(pa_api GET /rules)
RULE_ID=$(echo "$EXISTING_RULES" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
for r in items:
    if 'MessageTransform' in r.get('className',''):
        print(r['id'])
        break
else:
    print('')
" 2>/dev/null || echo "")

if [[ -z "$RULE_ID" ]]; then
    log "Warning: No MessageTransformRule found. Run configure-pa-plugin.sh first."
    log "The /api application is created but transforms won't be applied until the rule exists."
else
    # Check if rule is already attached to the resource
    CURRENT_RESOURCE=$(pa_api GET "/applications/${APP_ID}/resources/${ROOT_RESOURCE_ID}")
    HAS_RULE=$(echo "$CURRENT_RESOURCE" | python3 -c "
import sys,json
r = json.load(sys.stdin)
# Check all policy buckets for the rule
for bucket in r.get('policy', {}).values():
    if isinstance(bucket, list) and ${RULE_ID} in bucket:
        print('yes')
        break
else:
    print('')
" 2>/dev/null || echo "")

    if [[ "$HAS_RULE" == "yes" ]]; then
        ok "Message-xform rule already attached to /api resource"
    else
        # Attach rule to the Web policy bucket
        RESOURCE_WITH_RULE=$(echo "$CURRENT_RESOURCE" | python3 -c "
import sys,json
r = json.load(sys.stdin)
policy = r.get('policy', {})
web = policy.get('Web', [])
if ${RULE_ID} not in web:
    web.append(${RULE_ID})
policy['Web'] = web
r['policy'] = policy
json.dump(r, sys.stdout)
" 2>/dev/null)
        pa_api PUT "/applications/${APP_ID}/resources/${ROOT_RESOURCE_ID}" -d "$RESOURCE_WITH_RULE" > /dev/null
        ok "Message-xform rule (id=${RULE_ID}) attached to /api resource"
    fi
fi

# ── Verification ─────────────────────────────────────────────────────────────
echo ""
log "Verifying /api application..."

# Quick probe — /api/v1/auth/login should reach PA (may 500 to AM since
# the URL rewrite happens at the message-xform level, not PA routing)
PROXY_TEST=$(curl -sk \
    -H "Host: localhost:3000" \
    -o /dev/null -w "%{http_code}" \
    "https://localhost:13000/api/v1/auth/login" 2>/dev/null || echo "000")

# Any non-403 response means PA accepted the request (it didn't reject
# due to missing VH or context root match)
if [[ "$PROXY_TEST" != "403" && "$PROXY_TEST" != "000" ]]; then
    ok "PA accepted request to /api (HTTP ${PROXY_TEST})"
else
    log "Warning: PA returned HTTP ${PROXY_TEST} — check VH and application config"
fi

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Auth API application configured!"
echo ""
echo "  Application: /api → PingAM Backend (Web type, unprotected)"
echo ""
echo "  Clean auth URLs (POST):"
echo "    https://localhost:13000/api/v1/auth/login"
echo "    https://localhost:13000/api/v1/auth/passkey"
echo "    https://localhost:13000/api/v1/auth/passkey/usernameless"
echo ""
echo "  These are rewritten by message-xform to:"
echo "    /am/json/authenticate (default tree)"
echo "    /am/json/authenticate?authIndexType=service&authIndexValue=WebAuthnJourney"
echo "    /am/json/authenticate?authIndexType=service&authIndexValue=UsernamelessJourney"
echo "═══════════════════════════════════════════════════════════════"
