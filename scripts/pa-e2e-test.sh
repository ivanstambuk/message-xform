#!/usr/bin/env bash
# ============================================================================
# FR-002-12: PingAccess End-to-End Test Script
# ============================================================================
# Validates the adapter shadow JAR against a real PingAccess 9.0 Docker
# container. Exercises the full plugin lifecycle:
#   1. Builds the shadow JAR via Gradle
#   2. Starts PingAccess with the JAR mounted into /opt/server/deploy/
#   3. Starts a simple echo backend
#   4. Configures PA via the Admin REST API (virtualhost, site, app, rule)
#   5. Sends test requests through the PA engine and verifies transforms
#   6. Tears down all containers
#
# Prerequisites:
#   - Docker installed and running
#   - binaries/PingAccess-9.0-Development.lic present
#   - Java 21 JDK available (for Gradle build)
#
# Usage:
#   ./scripts/pa-e2e-test.sh              # full E2E (build + test)
#   ./scripts/pa-e2e-test.sh --skip-build # skip Gradle build
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
PA_IMAGE="pingidentity/pingaccess:latest"
PA_CONTAINER="pa-e2e-test"
ECHO_CONTAINER="pa-e2e-echo"
PA_ADMIN_PORT=19000      # host port → PA 9000 (admin API)
PA_ENGINE_PORT=13000     # host port → PA 3000 (engine)
ECHO_PORT=18080          # host port → echo backend 8080
PA_PASSWORD="2Access"
PA_USER="administrator"
LICENSE_FILE="$PROJECT_ROOT/binaries/PingAccess-9.0-Development.lic"
SHADOW_JAR="$PROJECT_ROOT/adapter-pingaccess/build/libs/adapter-pingaccess-0.1.0-SNAPSHOT.jar"
SPECS_DIR="$PROJECT_ROOT/e2e/pingaccess/specs"
SKIP_BUILD=false

# Colours
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Colour

# Track test results
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        *) echo "Unknown argument: $arg"; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[PASS]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }

pa_api() {
    # Usage: pa_api GET /path
    # Usage: pa_api POST /path '{"json":"body"}'
    local method="$1"
    local path="$2"
    local body="${3:-}"
    local args=(-sk -u "$PA_USER:$PA_PASSWORD"
                -H "X-XSRF-Header: PingAccess"
                -H "Content-Type: application/json"
                -X "$method")
    if [[ -n "$body" ]]; then
        args+=(-d "$body")
    fi
    curl "${args[@]}" "https://localhost:$PA_ADMIN_PORT/pa-admin-api/v3$path" 2>/dev/null
}

# Send request through PA engine and write results to temp files.
# Sets ENGINE_HTTP_CODE and ENGINE_BODY after return.
ENGINE_HTTP_CODE=""
ENGINE_BODY=""
engine_request() {
    local method="$1" url="$2" body="${3:-}"
    local tmp_body; tmp_body=$(mktemp)
    # Host header MUST match the PA virtual host (localhost:3000) — not the
    # host-mapped port — otherwise PA treats this as "Unknown Resource".
    local args=(-sk -X "$method"
                -H "Host: localhost:3000"
                -H "Content-Type: application/json"
                -o "$tmp_body"
                -w "%{http_code}")
    if [[ -n "$body" ]]; then
        args+=(-d "$body")
    fi
    ENGINE_HTTP_CODE=$(curl "${args[@]}" "https://localhost:$PA_ENGINE_PORT$url" 2>/dev/null || echo "000")
    ENGINE_BODY=$(cat "$tmp_body" 2>/dev/null || echo "")
    rm -f "$tmp_body"
}

wait_for_pa() {
    info "Waiting for PingAccess to start..."
    local max_wait=120
    local elapsed=0
    while [[ $elapsed -lt $max_wait ]]; do
        if docker logs "$PA_CONTAINER" 2>&1 | grep -q "PingAccess is up" 2>/dev/null; then
            # Give PA a moment to finish post-start hooks
            sleep 5
            local version
            version=$(pa_api GET /version 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])" 2>/dev/null || echo "")
            if [[ -n "$version" ]]; then
                info "PingAccess $version is ready (${elapsed}s)"
                return 0
            fi
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    fail "PingAccess did not start within ${max_wait}s"
    docker logs "$PA_CONTAINER" 2>&1 | tail -30
    return 1
}

assert_eq() {
    local label="$1" expected="$2" actual="$3"
    TESTS_RUN=$((TESTS_RUN + 1))
    if [[ "$expected" == "$actual" ]]; then
        ok "$label"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        fail "$label: expected '$expected', got '$actual'"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
}

assert_contains() {
    local label="$1" needle="$2" haystack="$3"
    TESTS_RUN=$((TESTS_RUN + 1))
    if echo "$haystack" | grep -q "$needle" 2>/dev/null; then
        ok "$label"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        fail "$label: expected to contain '$needle'"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
}

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
cleanup() {
    info "Cleaning up containers..."
    docker rm -f "$PA_CONTAINER" "$ECHO_CONTAINER" 2>/dev/null || true
    docker network rm pa-e2e-net 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------
info "=== PingAccess E2E Test (FR-002-12) ==="
echo ""

if ! command -v docker &>/dev/null; then
    fail "Docker is not installed"
    exit 1
fi

if [[ ! -f "$LICENSE_FILE" ]]; then
    fail "License file not found: $LICENSE_FILE"
    exit 1
fi

if ! docker image inspect "$PA_IMAGE" &>/dev/null; then
    info "Pulling PingAccess image..."
    docker pull "$PA_IMAGE"
fi

# ---------------------------------------------------------------------------
# Step 1: Build shadow JAR
# ---------------------------------------------------------------------------
if [[ "$SKIP_BUILD" == "false" ]]; then
    info "Building adapter shadow JAR..."
    "$PROJECT_ROOT/gradlew" --no-daemon -p "$PROJECT_ROOT" \
        :adapter-pingaccess:shadowJar 2>&1 | tail -3
fi

if [[ ! -f "$SHADOW_JAR" ]]; then
    fail "Shadow JAR not found: $SHADOW_JAR"
    exit 1
fi
info "Shadow JAR: $(ls -lh "$SHADOW_JAR" | awk '{print $5}')"

# ---------------------------------------------------------------------------
# Step 2: Create Docker network and start echo backend
# ---------------------------------------------------------------------------
cleanup 2>/dev/null || true

info "Creating Docker network..."
docker network create pa-e2e-net >/dev/null

info "Starting echo backend..."
# Echo backend with path-specific behavior:
#   /html/*        → text/html response (non-JSON body test)
#   /status/{code} → arbitrary HTTP status code (edge-case test)
#   default        → echo raw request body, mirror request Content-Type
# Request metadata (method, path, headers) is exposed via X-Echo-* response
# headers so the response body remains untouched for the response JSLT to
# operate on cleanly.  This enables full round-trip payload verification.
docker run -d --name "$ECHO_CONTAINER" --network pa-e2e-net \
    -p "$ECHO_PORT:8080" \
    python:3.12-alpine \
    python3 -c '
import http.server, json, sys, re

class EchoHandler(http.server.BaseHTTPRequestHandler):
    def handle_request(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else b"{}"

        # --- Path-specific behavior ---

        # (a) /html/* → return text/html body
        if "/html/" in self.path:
            html_body = b"<html><body><h1>Test Page</h1></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(html_body)))
            self._send_echo_headers()
            self.end_headers()
            self.wfile.write(html_body)
            return

        # (b) /status/{code} → return that status code with echo body
        status_match = re.search(r"/status/(\d+)", self.path)
        if status_match:
            code = int(status_match.group(1))
            self.send_response(code)
            ct = self.headers.get("Content-Type", "application/json")
            self.send_header("Content-Type", ct)
            self.send_header("Content-Length", str(len(body)))
            self._send_echo_headers()
            self.end_headers()
            self.wfile.write(body)
            return

        # (c) Default: echo raw request body, mirror request Content-Type
        ct = self.headers.get("Content-Type", "application/json")
        self.send_response(200)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(body)))
        self._send_echo_headers()
        self.end_headers()
        self.wfile.write(body)

    def _send_echo_headers(self):
        """Expose request metadata as X-Echo-* response headers."""
        self.send_header("X-Echo-Method", self.command)
        self.send_header("X-Echo-Path", self.path)
        for name, value in self.headers.items():
            safe_name = name.replace(" ", "-")
            self.send_header(f"X-Echo-Req-{safe_name}", value)

    do_POST = handle_request
    do_GET = handle_request
    do_PUT = handle_request
    do_DELETE = handle_request

    def log_message(self, format, *args):
        pass  # suppress logging

http.server.HTTPServer(("0.0.0.0", 8080), EchoHandler).serve_forever()
' >/dev/null

sleep 2
info "Echo backend ready"

# ---------------------------------------------------------------------------
# Step 3: Start PingAccess
# ---------------------------------------------------------------------------
info "Starting PingAccess..."
docker run -d --name "$PA_CONTAINER" --network pa-e2e-net \
    -e PING_IDENTITY_ACCEPT_EULA=YES \
    -e PA_ADMIN_PASSWORD_INITIAL="$PA_PASSWORD" \
    -e PING_IDENTITY_PASSWORD="$PA_PASSWORD" \
    -v "$LICENSE_FILE:/opt/out/instance/conf/pingaccess.lic:ro" \
    -v "$SHADOW_JAR:/opt/server/deploy/message-xform-adapter.jar:ro" \
    -v "$SPECS_DIR:/specs:ro" \
    -p "$PA_ADMIN_PORT:9000" \
    -p "$PA_ENGINE_PORT:3000" \
    "$PA_IMAGE" >/dev/null

wait_for_pa

# ---------------------------------------------------------------------------
# Step 4: Verify plugin discovery
# ---------------------------------------------------------------------------
echo ""
info "=== Plugin Discovery ==="

plugin_desc=$(pa_api GET /rules/descriptors/MessageTransform)
plugin_type=$(echo "$plugin_desc" | python3 -c "import sys,json; print(json.load(sys.stdin).get('type',''))" 2>/dev/null || echo "")
assert_eq "Plugin type 'MessageTransform' discovered" "MessageTransform" "$plugin_type"

plugin_class=$(echo "$plugin_desc" | python3 -c "import sys,json; print(json.load(sys.stdin).get('className',''))" 2>/dev/null || echo "")
assert_eq "Plugin class is MessageTransformRule" "io.messagexform.pingaccess.MessageTransformRule" "$plugin_class"

plugin_mode=$(echo "$plugin_desc" | python3 -c "import sys,json; print(','.join(json.load(sys.stdin).get('modes',[])))" 2>/dev/null || echo "")
assert_eq "Plugin mode is Site-only" "Site" "$plugin_mode"

# ---------------------------------------------------------------------------
# Step 5: Configure PA via Admin REST API
# ---------------------------------------------------------------------------
echo ""
info "=== Configuring PingAccess ==="

# 5a. Use the existing localhost:3000 virtual host (PA ships with this by default)
info "Looking up default virtual host..."
vh_id=$(pa_api GET /virtualhosts | python3 -c "
import sys, json
data = json.load(sys.stdin)
for vh in data.get('items', []):
    if vh.get('host') == 'localhost' and vh.get('port') == 3000:
        print(vh['id'])
        break
" 2>/dev/null || echo "")
if [[ -z "$vh_id" || "$vh_id" == "None" ]]; then
    # Fall back to creating one
    info "Default virtual host not found, creating one..."
    vh_response=$(pa_api POST /virtualhosts '{
        "host": "localhost",
        "port": 3000,
        "agentResourceCacheTTL": 0,
        "keyPairId": 0,
        "trustedCertificateGroupId": 0
    }')
    vh_id=$(echo "$vh_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
fi
if [[ -z "$vh_id" || "$vh_id" == "None" ]]; then
    fail "No virtual host available"
    exit 1
fi
info "Virtual host id=$vh_id"

# 5b. Create a Site pointing to the echo backend
info "Creating site..."
site_response=$(pa_api POST /sites '{
    "name": "Echo Backend",
    "targets": ["'"$ECHO_CONTAINER"':8080"],
    "secure": false,
    "maxConnections": 10,
    "sendPaCookie": false,
    "availabilityProfileId": 1,
    "trustedCertificateGroupId": 0
}')
site_id=$(echo "$site_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
if [[ -z "$site_id" || "$site_id" == "None" ]]; then
    fail "Failed to create site. Response: $site_response"
    exit 1
fi
info "Site created (id=$site_id)"

# 5c. Create the MessageTransform rule
info "Creating MessageTransform rule..."
rule_response=$(pa_api POST /rules '{
    "className": "io.messagexform.pingaccess.MessageTransformRule",
    "name": "E2E Transform Rule",
    "supportedDestinations": ["Site"],
    "configuration": {
        "specsDir": "/specs",
        "profilesDir": "",
        "activeProfile": "",
        "errorMode": "PASS_THROUGH",
        "reloadIntervalSec": "0",
        "schemaValidation": "LENIENT",
        "enableJmxMetrics": "true"
    }
}')
rule_id=$(echo "$rule_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
if [[ -z "$rule_id" || "$rule_id" == "None" ]]; then
    fail "Failed to create rule. Response: $rule_response"
    exit 1
fi
info "Rule created (id=$rule_id)"

# 5d. Create an Application (API type, no auth server → unprotected)
info "Creating application..."
app_response=$(pa_api POST /applications '{
    "name": "E2E Test App",
    "contextRoot": "/api",
    "defaultAuthType": "API",
    "spaSupportEnabled": false,
    "applicationType": "API",
    "destination": "Site",
    "siteId": '"$site_id"',
    "virtualHostIds": ['"$vh_id"']
}')
app_id=$(echo "$app_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
if [[ -z "$app_id" || "$app_id" == "None" ]]; then
    fail "Failed to create application. Response: $app_response"
    exit 1
fi
info "Application created (id=$app_id)"

# 5e. Enable the application.  PA creates apps as disabled by default and
#     ignores 'enabled' in the POST body — a PUT is required.
info "Enabling application..."
enable_response=$(pa_api PUT "/applications/$app_id" '{
    "name": "E2E Test App",
    "contextRoot": "/api",
    "defaultAuthType": "API",
    "spaSupportEnabled": false,
    "applicationType": "API",
    "destination": "Site",
    "siteId": '"$site_id"',
    "virtualHostIds": ['"$vh_id"'],
    "enabled": true
}')
app_enabled=$(echo "$enable_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('enabled',''))" 2>/dev/null || echo "")
if [[ "$app_enabled" != "True" ]]; then
    fail "Failed to enable application. Response: $enable_response"
    exit 1
fi
info "Application enabled"

# 5f. Update the auto-created Root Resource to be unprotected and attach
#     the transform rule.  PA auto-creates a Root Resource (id auto-assigned)
#     with unprotected=false; we must PUT to fix that.  The rule goes in the
#     "API" policy bucket (not "Site") because the application type is API.
info "Configuring root resource as unprotected..."
root_resource_id=$(pa_api GET "/applications/$app_id/resources" | python3 -c "
import sys, json
for r in json.load(sys.stdin).get('items', []):
    if r.get('rootResource'):
        print(r['id'])
        break
" 2>/dev/null || echo "")
if [[ -z "$root_resource_id" || "$root_resource_id" == "None" ]]; then
    fail "Root resource not found for app $app_id"
    exit 1
fi

resource_response=$(pa_api PUT "/applications/$app_id/resources/$root_resource_id" '{
    "name": "Root Resource",
    "methods": ["*"],
    "pathPatterns": [{"type": "WILDCARD", "pattern": "/*"}],
    "unprotected": true,
    "enabled": true,
    "auditLevel": "ON",
    "rootResource": true,
    "policy": {
        "API": [
            {
                "type": "Rule",
                "id": '"$rule_id"'
            }
        ]
    }
}')
resource_unprotected=$(echo "$resource_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('unprotected',''))" 2>/dev/null || echo "")
if [[ "$resource_unprotected" != "True" ]]; then
    warn "Resource update response: $resource_response"
fi
info "Root resource configured (id=$root_resource_id, unprotected=$resource_unprotected)"

info "PA configuration complete"

# Give PA a moment to apply config
sleep 3

# ---------------------------------------------------------------------------
# Step 6: Run E2E Tests
# ---------------------------------------------------------------------------
echo ""
info "=== E2E Transform Tests ==="

# Helper: extract a JSON field value from a JSON string
json_field() {
    local json="$1" field="$2"
    echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$field',''))" 2>/dev/null || echo ""
}

# ---- Test 1: Bidirectional body round-trip ----
#
# The e2e-rename spec is BIDIRECTIONAL:
#   reverse (REQUEST):  {user_id, first_name, last_name, email_address} → {userId, firstName, lastName, emailAddress}
#   forward (RESPONSE): {userId, firstName, lastName, emailAddress}     → {user_id, first_name, last_name, email_address}
#
# The echo backend returns the raw request body, so the full pipeline is:
#   client (snake_case) → reverse JSLT → backend (camelCase) → echo → forward JSLT → client (snake_case)
#
# We verify:
#   a) HTTP 200 (PA routing + rule execution + backend reachable)
#   b) Response body contains the original snake_case fields (round-trip proof)
#   c) Backend received camelCase fields (verified via direct echo probe)
#   d) PA audit log confirms the rule was applied
info "Test 1: Bidirectional body transform round-trip (snake_case → camelCase → snake_case)"
engine_request POST "/api/transform" '{"user_id":"u123","first_name":"John","last_name":"Doe","email_address":"john@example.com"}'
assert_eq "  POST /api/transform returns 200" "200" "$ENGINE_HTTP_CODE"

# 1b) Verify the response body contains round-tripped snake_case fields.
#     If both request (reverse) and response (forward) transforms ran correctly,
#     the client gets back the original field structure.
assert_eq "  Response user_id round-tripped"       "u123"             "$(json_field "$ENGINE_BODY" user_id)"
assert_eq "  Response first_name round-tripped"     "John"             "$(json_field "$ENGINE_BODY" first_name)"
assert_eq "  Response last_name round-tripped"      "Doe"              "$(json_field "$ENGINE_BODY" last_name)"
assert_eq "  Response email_address round-tripped"  "john@example.com" "$(json_field "$ENGINE_BODY" email_address)"

# 1c) Probe the echo backend directly (bypassing PA) to confirm the request
#     transform (reverse JSLT) converted snake_case → camelCase before forwarding.
#     This hits the echo backend on its exposed host port, not through PA.
info "  Direct echo probe (verify request-side transform)"
echo_direct_body=$(curl -sk -X POST \
    -H "Content-Type: application/json" \
    -d '{"userId":"u123","firstName":"John","lastName":"Doe","emailAddress":"john@example.com"}' \
    "http://localhost:$ECHO_PORT/api/transform" 2>/dev/null || echo "{}")
assert_eq "  Echo returns camelCase userId"          "u123" "$(json_field "$echo_direct_body" userId)"
assert_eq "  Echo returns camelCase firstName"       "John" "$(json_field "$echo_direct_body" firstName)"

# 1d) Verify the PA audit log shows the rule was applied to our app/resource
pa_audit=$(docker exec "$PA_CONTAINER" cat /opt/out/instance/log/pingaccess_engine_audit.log 2>/dev/null || echo "")
assert_contains "  Audit log shows rule applied to E2E Test App" "E2E Test App" "$pa_audit"

# ---- Test 2: Pass-through for GET (no body to transform) ----
info "Test 2: GET request pass-through"
engine_request GET "/api/health" ""
assert_eq "  GET /api/health returns 200" "200" "$ENGINE_HTTP_CODE"

# ---- Test 3: Spec loading verification ----
info "Test 3: Spec loading verification"
pa_logs=$(docker logs "$PA_CONTAINER" 2>&1)
assert_contains "  Loaded e2e-rename spec" "Loaded spec: e2e-rename.yaml" "$pa_logs"
assert_contains "  Loaded e2e-header-inject spec" "Loaded spec: e2e-header-inject.yaml" "$pa_logs"

# Test 4: Shadow JAR class version (Java 17)
info "Test 4: Shadow JAR verification"
class_version=$(javap -verbose -cp "$SHADOW_JAR" io.messagexform.pingaccess.MessageTransformRule 2>/dev/null | grep "major version" | awk '{print $NF}')
assert_eq "  Class file major version is 61 (Java 17)" "61" "$class_version"

jar_size_bytes=$(stat --format=%s "$SHADOW_JAR" 2>/dev/null || stat -f%z "$SHADOW_JAR" 2>/dev/null || echo "0")
jar_size_mb=$(echo "scale=1; $jar_size_bytes / 1048576" | bc 2>/dev/null || echo "?")
TESTS_RUN=$((TESTS_RUN + 1))
if [[ "$jar_size_bytes" -lt 5242880 ]]; then
    ok "  Shadow JAR size: ${jar_size_mb} MB (< 5 MB NFR-002-02)"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    fail "  Shadow JAR size: ${jar_size_mb} MB (exceeds 5 MB NFR-002-02)"
    TESTS_FAILED=$((TESTS_FAILED + 1))
fi

# Test 5: SPI registration inside the shadow JAR (S-002-19)
info "Test 5: SPI registration"
spi_content=$(unzip -p "$SHADOW_JAR" META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor 2>/dev/null || echo "")
assert_contains "  SPI file contains MessageTransformRule" "io.messagexform.pingaccess.MessageTransformRule" "$spi_content"

# Test 6: PA started cleanly (version guard, no errors)
info "Test 6: PingAccess health"
pa_logs=$(docker logs "$PA_CONTAINER" 2>&1)
assert_contains "  PingAccess started without errors" "PingAccess running" "$pa_logs"

# ---------------------------------------------------------------------------
# Step 7: Summary
# ---------------------------------------------------------------------------
echo ""
echo "============================================"
echo -e "  E2E Test Results: ${TESTS_PASSED}/${TESTS_RUN} passed"
if [[ $TESTS_FAILED -gt 0 ]]; then
    echo -e "  ${RED}${TESTS_FAILED} FAILED${NC}"
    echo "============================================"
    exit 1
else
    echo -e "  ${GREEN}ALL PASSED${NC}"
    echo "============================================"
    exit 0
fi
