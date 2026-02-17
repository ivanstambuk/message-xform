#!/usr/bin/env bash
# ==============================================================================
# configure-pa-plugin.sh — Wire message-xform plugin into PingAccess
# ==============================================================================
# Creates a MessageTransform processing rule and attaches it to the existing
# PingAM Proxy application's root resource.
#
# Prerequisites:
#   - PA container running with the shadow JAR mounted at /opt/server/deploy/
#   - Spec and profile directories mounted at /specs and /profiles
#   - PA already configured with Site + Application (configure-pa.sh ran first)
#
# PA Admin API objects created/modified:
#   1. Rule        — MessageTransform rule with platform-am profile
#   2. Resource    — Root resource updated with the rule in its policy
#
# Usage:
#   ./scripts/configure-pa-plugin.sh
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
RULE_NAME="PingAM Transform Rule"

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

# ── Step 1: Verify PA is ready + plugin is loaded ────────────────────────────
log "Step 1/4 — Verifying PingAccess and plugin availability"
VERSION=$(pa_api GET /version | python3 -c "import sys,json; print(json.load(sys.stdin).get('version',''))" 2>/dev/null || echo "")
if [[ -z "$VERSION" ]]; then
    fail "Cannot reach PA Admin API at ${PA_ADMIN_URL}"
fi
ok "PingAccess ${VERSION} is ready"

# Check plugin descriptor
DESCRIPTOR=$(pa_api GET /rules/descriptors/MessageTransform)
PLUGIN_CLASS=$(echo "$DESCRIPTOR" | python3 -c "import sys,json; print(json.load(sys.stdin).get('className',''))" 2>/dev/null || echo "")

if [[ "$PLUGIN_CLASS" == "io.messagexform.pingaccess.MessageTransformRule" ]]; then
    ok "MessageTransform plugin discovered (${PLUGIN_CLASS})"
else
    fail "MessageTransform plugin NOT found. Is the shadow JAR mounted at /opt/server/deploy/?"
fi

# ── Step 2: Find or Create MessageTransform Rule ─────────────────────────────
log "Step 2/4 — Creating MessageTransform processing rule"

# Check if rule already exists
EXISTING_RULES=$(pa_api GET /rules)
EXISTING_RULE_ID=$(echo "$EXISTING_RULES" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
for r in items:
    if r.get('name','') == '${RULE_NAME}':
        print(r['id'])
        break
else:
    print('')
" 2>/dev/null || echo "")

if [[ -n "$EXISTING_RULE_ID" ]]; then
    ok "Rule '${RULE_NAME}' already exists (id=${EXISTING_RULE_ID})"
    RULE_ID="$EXISTING_RULE_ID"
else
    RULE_BODY=$(cat <<'EOJSON'
{
    "className": "io.messagexform.pingaccess.MessageTransformRule",
    "name": "PingAM Transform Rule",
    "supportedDestinations": ["Site"],
    "configuration": {
        "specsDir": "/specs",
        "profilesDir": "/profiles",
        "activeProfile": "platform-am",
        "errorMode": "PASS_THROUGH",
        "reloadIntervalSec": "30",
        "schemaValidation": "LENIENT",
        "enableJmxMetrics": "false"
    }
}
EOJSON
)

    RULE_RESPONSE=$(pa_api POST /rules -d "$RULE_BODY")
    RULE_ID=$(echo "$RULE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

    if [[ -z "$RULE_ID" ]]; then
        fail "Failed to create rule. Response: ${RULE_RESPONSE}"
    fi
    ok "Created rule '${RULE_NAME}' (id=${RULE_ID})"
fi

# ── Step 3: Find the PingAM application + root resource ─────────────────────
log "Step 3/4 — Attaching rule to PingAM Proxy application"

# Find application by context root
EXISTING_APPS=$(pa_api GET /applications)
APP_ID=$(echo "$EXISTING_APPS" | python3 -c "
import sys,json
items = json.load(sys.stdin).get('items',[])
for a in items:
    if a.get('contextRoot','') == '/am':
        print(a['id'])
        break
else:
    print('')
" 2>/dev/null || echo "")

if [[ -z "$APP_ID" ]]; then
    fail "PingAM Proxy application (/am) not found. Run configure-pa.sh first."
fi

# Find root resource
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

# ── Step 4: Attach rule to root resource policy ─────────────────────────────
log "Step 4/4 — Updating root resource policy with transform rule"

# Get current resource state
CURRENT_RESOURCE=$(pa_api GET "/applications/${APP_ID}/resources/${ROOT_RESOURCE_ID}")

# Update policy to include our rule in the Web bucket
# PA Application is type=Web, so we use the "Web" policy bucket
RESOURCE_BODY=$(echo "$CURRENT_RESOURCE" | python3 -c "
import sys,json
r = json.load(sys.stdin)
r['unprotected'] = True
r['enabled'] = True
r['methods'] = ['*']
r['pathPatterns'] = [{'type': 'WILDCARD', 'pattern': '/*'}]
# Add rule to the Web policy bucket (app is type=Web)
r['policy'] = {'Web': [{'type': 'Rule', 'id': ${RULE_ID}}]}
json.dump(r, sys.stdout)
" 2>/dev/null)

RESOURCE_RESPONSE=$(pa_api PUT "/applications/${APP_ID}/resources/${ROOT_RESOURCE_ID}" -d "$RESOURCE_BODY")
POLICY_CHECK=$(echo "$RESOURCE_RESPONSE" | python3 -c "
import sys,json
r = json.load(sys.stdin)
policy = r.get('policy', {})
web_rules = policy.get('Web', [])
has_rule = any(rule.get('id') == ${RULE_ID} for rule in web_rules)
print('True' if has_rule else 'False')
" 2>/dev/null || echo "")

if [[ "$POLICY_CHECK" == "True" ]]; then
    ok "Rule attached to root resource policy (Web bucket)"
else
    log "Warning: Rule may not be attached. Response: ${RESOURCE_RESPONSE}"
fi

# ── Verification ─────────────────────────────────────────────────────────────
echo ""
log "Verifying plugin — checking for MessageTransform in PA logs..."

# Check PA container logs for plugin loading messages
PLUGIN_LOG=$(docker exec platform-pingaccess grep -l "MessageTransform" /opt/out/instance/log/pingaccess.log 2>/dev/null && echo "found" || echo "")
if [[ "$PLUGIN_LOG" == "found" ]]; then
    ok "Plugin log entries found in pingaccess.log"
    # Show relevant lines
    docker exec platform-pingaccess grep -i "messagexform\|MessageTransform\|message-xform" /opt/out/instance/log/pingaccess.log 2>/dev/null | tail -10
else
    log "No plugin log entries yet (rule may not have been invoked)"
fi

echo ""
log "Testing transform — authenticating through PingAccess..."

# Test 1: Initiate auth (should get callbacks + X-Auth-Provider header)
TEST_RESPONSE=$(curl -sk --max-time 10 \
    -H "Host: localhost:3000" \
    -X POST "https://localhost:13000/am/json/authenticate" \
    -H "Content-Type: application/json" \
    -H "Accept-API-Version: resource=2.0,protocol=1.0" \
    -D /tmp/pa-test-headers.txt 2>/dev/null || echo "")

if [[ -n "$TEST_RESPONSE" ]]; then
    HAS_CALLBACKS=$(echo "$TEST_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print('True' if 'callbacks' in d else 'False')" 2>/dev/null || echo "")
    if [[ "$HAS_CALLBACKS" == "True" ]]; then
        ok "Auth callbacks received through PingAccess"
    fi

    # Check for injected headers
    if grep -qi "X-Auth-Provider" /tmp/pa-test-headers.txt 2>/dev/null; then
        ok "X-Auth-Provider header injected ✓"
    else
        log "Note: X-Auth-Provider header not found (profile may not match this path)"
    fi

    if grep -qi "X-Transform-Engine" /tmp/pa-test-headers.txt 2>/dev/null; then
        ok "X-Transform-Engine header injected ✓"
    fi
else
    log "Warning: No response from PA engine"
fi

rm -f /tmp/pa-test-headers.txt

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  message-xform plugin wired into PingAccess!"
echo ""
echo "  Rule:     ${RULE_NAME} (id=${RULE_ID})"
echo "  Profile:  platform-am v2"
echo "  Specs:    am-auth-response-v2 → am-strip-internal (chained)"
echo ""
echo "  Transform behaviour:"
echo "    /am/json/authenticate (POST) → response body cleaned + headers injected"
echo "    Callback: AM callbacks → clean fields[] + X-Auth-Session header"
echo "    Success:  tokenId → Set-Cookie + body stripped to {authenticated, realm}"
echo ""
echo "  Test:"
echo "    curl -sk -H 'Host: localhost:3000' \\"
echo "      -X POST 'https://localhost:13000/am/json/authenticate' \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'Accept-API-Version: resource=2.0,protocol=1.0' | python3 -m json.tool"
echo "═══════════════════════════════════════════════════════════════"
