#!/usr/bin/env bash
# ============================================================================
# PingAccess E2E Bootstrap — Docker lifecycle + Karate delegation
# ============================================================================
# This thin wrapper handles infrastructure only (RQ-21, RQ-22):
#   1. Preflight checks (Docker, license, image)
#   2. Builds the shadow JAR (unless --skip-build)
#   3. Starts Docker containers (PA, echo backend, mock OIDC)
#   4. Waits for PA readiness
#   5. Delegates all test logic to Karate via Gradle
#   6. Tears down containers on exit
#
# All test assertions, provisioning, and validation live in Karate .feature
# files under e2e-pingaccess/src/test/java/e2e/.
#
# Usage:
#   ./scripts/pa-e2e-bootstrap.sh              # full E2E (build + test)
#   ./scripts/pa-e2e-bootstrap.sh --skip-build # skip Gradle shadow JAR build
# ============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
PA_IMAGE="pingidentity/pingaccess:latest"
PA_CONTAINER="pa-e2e-test"
ECHO_CONTAINER="pa-e2e-echo"
OIDC_CONTAINER="pa-e2e-oidc"
OIDC_BACKEND_CONTAINER="pa-e2e-oidc-backend"
PA_ADMIN_PORT=19000
PA_ENGINE_PORT=13000
ECHO_PORT=18080
OIDC_PORT=18443
JMX_PORT=19999
# IMPORTANT: container port must equal host port for JMX/RMI.
# RMI returns the server-side port in its stub; if container port (9999) ≠
# host port (19999), the client gets told to connect to localhost:9999
# which doesn't exist on the host.  Using the same port on both sides
# ensures the RMI stub matches what the Docker-mapped host port exposes.
JMX_CONTAINER_PORT=19999
PA_PASSWORD="2Access"
LICENSE_FILE="$PROJECT_ROOT/binaries/pingaccess/license/PingAccess-9.0-Development.lic"
SHADOW_JAR="$PROJECT_ROOT/adapter-pingaccess/build/libs/adapter-pingaccess-0.1.0-SNAPSHOT.jar"
SPECS_DIR="$PROJECT_ROOT/e2e/pingaccess/specs"
PROFILES_DIR="$PROJECT_ROOT/e2e/pingaccess/profiles"
SKIP_BUILD=false

# Colours
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

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

# ---------------------------------------------------------------------------
# Cleanup (runs on EXIT — RQ-02)
# ---------------------------------------------------------------------------
cleanup() {
    info "Cleaning up containers..."
    docker rm -f "$PA_CONTAINER" "$ECHO_CONTAINER" "$OIDC_CONTAINER" "$OIDC_BACKEND_CONTAINER" 2>/dev/null || true
    docker network rm pa-e2e-net 2>/dev/null || true
    rm -rf "$PROJECT_ROOT/.e2e-oidc-certs" 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Preflight checks (RQ-22)
# ---------------------------------------------------------------------------
info "=== PingAccess E2E Bootstrap ==="
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
# Step 2: Create Docker network and start containers
# ---------------------------------------------------------------------------
cleanup 2>/dev/null || true

info "Creating Docker network..."
docker network create pa-e2e-net >/dev/null

# 2a. Echo backend (RQ-21 — inline Python echo server)
info "Starting echo backend..."
docker run -d --name "$ECHO_CONTAINER" --network pa-e2e-net \
    -p "$ECHO_PORT:8080" \
    python:3.12-alpine \
    python3 -c '
import http.server, json, sys, re, base64

class EchoHandler(http.server.BaseHTTPRequestHandler):
    def handle_request(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else b"{}"

        # (a) /html/* -> return text/html body
        if "/html/" in self.path:
            html_body = b"<html><body><h1>Test Page</h1></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(html_body)))
            self._send_echo_headers()
            self.end_headers()
            self.wfile.write(html_body)
            return

        # X-Echo-Status header: override response status code
        # X-Echo-Body header: override response body (raw JSON string)
        # These give tests full control over the echo response without
        # path conflicts.  Used by status-routing and polymorphic E2E tests.
        echo_status = self.headers.get("X-Echo-Status")
        echo_body = self.headers.get("X-Echo-Body")

        # (b) /status/{code} -> return that status code with echo body
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

        # (c) Default: echo request body (or X-Echo-Body override)
        code = int(echo_status) if echo_status else 200
        out = echo_body.encode() if echo_body else body

        ct = self.headers.get("Content-Type", "application/json")
        self.send_response(code)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(out)))
        self._send_echo_headers()
        self.end_headers()
        self.wfile.write(out)

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

# 2b. OIDC infrastructure (two-container HTTPS architecture)
# See operations guide §25 for full details.
#
# Architecture:
#   pa-e2e-oidc-backend (mock-oauth2-server, native HTTPS :8443)
#   pa-e2e-oidc         (Python TLS proxy, :8443)
#     ├─ Patches .well-known metadata (injects token_endpoint_auth_methods_supported)
#     ├─ Sets Host header for correct id_token issuer
#     └─ Forwards all other requests to backend

CERTS_DIR="$PROJECT_ROOT/.e2e-oidc-certs"
mkdir -p "$CERTS_DIR"

# Generate PKCS12 keystore with SANs for Docker hostnames
info "Generating OIDC keystore..."
keytool -genkeypair -keyalg EC -alias oidc \
    -keystore "$CERTS_DIR/oidc.p12" -storetype PKCS12 \
    -storepass changeit -keypass changeit \
    -dname "CN=pa-e2e-oidc" \
    -ext "SAN=dns:pa-e2e-oidc,dns:pa-e2e-oidc-backend,dns:localhost,ip:127.0.0.1" \
    -validity 30 2>/dev/null

# Extract PEM cert + key for the Python proxy
openssl pkcs12 -in "$CERTS_DIR/oidc.p12" -passin pass:changeit \
    -nokeys -out "$CERTS_DIR/cert.pem" 2>/dev/null
openssl pkcs12 -in "$CERTS_DIR/oidc.p12" -passin pass:changeit \
    -nocerts -nodes -out "$CERTS_DIR/key.pem" 2>/dev/null
info "Keystore ready"

# 2b-i. OIDC backend (mock-oauth2-server with native HTTPS via JSON_CONFIG)
# MockWebServerWrapper wraps the default :8080 port with TLS (NettyWrapper does NOT).
info "Starting OIDC backend (mock-oauth2-server HTTPS)..."
OIDC_JSON_CONFIG='{"httpServer":{"type":"MockWebServerWrapper","ssl":{"keyPassword":"changeit","keystoreFile":"/tmp/oidc.p12","keystoreType":"PKCS12","keystorePassword":"changeit"}}}'
docker run -d --name "$OIDC_BACKEND_CONTAINER" --network pa-e2e-net \
    -v "$CERTS_DIR/oidc.p12:/tmp/oidc.p12:ro" \
    -e JSON_CONFIG="$OIDC_JSON_CONFIG" \
    ghcr.io/navikt/mock-oauth2-server:latest >/dev/null

# Wait for backend (no port mapping to host — check via docker exec wget)
info "Waiting for OIDC backend..."
oidc_backend_ready=false
for i in $(seq 1 30); do
    if docker exec "$OIDC_BACKEND_CONTAINER" \
        sh -c 'wget -qO- --no-check-certificate https://localhost:8080/default/.well-known/openid-configuration' >/dev/null 2>&1; then
        oidc_backend_ready=true
        break
    fi
    sleep 1
done
if [[ "$oidc_backend_ready" == "true" ]]; then
    info "OIDC backend ready (${i}s)"
else
    warn "OIDC backend did not start within 30s — Phase 8b will be skipped"
    docker logs "$OIDC_BACKEND_CONTAINER" 2>&1 | tail -20
fi

# 2b-ii. OIDC proxy (Python TLS-terminating reverse proxy)
# Patches .well-known metadata and sets Host header for issuer alignment
info "Starting OIDC proxy..."
docker run -d --name "$OIDC_CONTAINER" --network pa-e2e-net \
    -p "$OIDC_PORT:8443" \
    -v "$CERTS_DIR/cert.pem:/certs/cert.pem:ro" \
    -v "$CERTS_DIR/key.pem:/certs/key.pem:ro" \
    python:3.12-alpine \
    python3 -c '
import http.server, ssl, http.client, json, sys

BACKEND = "pa-e2e-oidc-backend"
BACKEND_PORT = 8080
HOST_HEADER = "pa-e2e-oidc:8443"

class OidcProxy(http.server.BaseHTTPRequestHandler):
    def do_request(self):
        # Read request body
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else None

        # Build backend connection (HTTPS, no cert verify)
        import ssl as _ssl
        ctx = _ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = _ssl.CERT_NONE
        conn = http.client.HTTPSConnection(BACKEND, BACKEND_PORT, context=ctx)

        # Forward headers, override Host for correct issuer
        headers = {}
        for name, value in self.headers.items():
            if name.lower() != "host":
                headers[name] = value
        headers["Host"] = HOST_HEADER

        conn.request(self.command, self.path, body=body, headers=headers)
        resp = conn.getresponse()
        resp_body = resp.read()

        # Patch .well-known metadata
        if "/.well-known/openid-configuration" in self.path:
            try:
                meta = json.loads(resp_body)
                meta["token_endpoint_auth_methods_supported"] = [
                    "client_secret_basic", "client_secret_post"
                ]
                # PA requires this PingFederate-specific field for
                # PingFederate Runtime / Web Session support
                if "end_session_endpoint" in meta:
                    meta["ping_end_session_endpoint"] = meta["end_session_endpoint"]
                resp_body = json.dumps(meta).encode()
            except Exception:
                pass

        # Send response
        self.send_response(resp.status)
        for name, value in resp.getheaders():
            if name.lower() not in ("transfer-encoding", "content-length"):
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(resp_body)))
        self.end_headers()
        self.wfile.write(resp_body)

    do_GET = do_request
    do_POST = do_request
    do_PUT = do_request
    do_DELETE = do_request

    def log_message(self, fmt, *args):
        pass

server = http.server.HTTPServer(("0.0.0.0", 8443), OidcProxy)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain("/certs/cert.pem", "/certs/key.pem")
server.socket = ctx.wrap_socket(server.socket, server_side=True)
print("OIDC proxy listening on :8443", flush=True)
server.serve_forever()
' >/dev/null

info "Waiting for OIDC proxy..."
oidc_ready=false
for i in $(seq 1 30); do
    if curl -skf "https://localhost:$OIDC_PORT/default/.well-known/openid-configuration" >/dev/null 2>&1; then
        oidc_ready=true
        break
    fi
    sleep 1
done
if [[ "$oidc_ready" == "true" ]]; then
    # Verify metadata patching worked
    meta=$(curl -sk "https://localhost:$OIDC_PORT/default/.well-known/openid-configuration")
    if echo "$meta" | python3 -c "import sys,json; d=json.load(sys.stdin); assert 'client_secret_basic' in d.get('token_endpoint_auth_methods_supported',[])" 2>/dev/null; then
        info "OIDC proxy ready — metadata patching verified (${i}s)"
    else
        warn "OIDC proxy running but metadata patch NOT detected"
    fi
else
    warn "OIDC proxy did not start within 30s — Phase 8b will be skipped"
    docker logs "$OIDC_CONTAINER" 2>&1 | tail -20
fi

# 2c. PingAccess
# JVM_OPTS is appended to the java command in PA's run.sh (separate from
# JAVA_OPTS which controls heap/GC).  We use it to enable JMX remote access
# for Phase 10 E2E tests (S-002-33, S-002-34).
JMX_JVM_OPTS="-Dcom.sun.management.jmxremote"
JMX_JVM_OPTS="$JMX_JVM_OPTS -Dcom.sun.management.jmxremote.port=$JMX_CONTAINER_PORT"
JMX_JVM_OPTS="$JMX_JVM_OPTS -Dcom.sun.management.jmxremote.rmi.port=$JMX_CONTAINER_PORT"
JMX_JVM_OPTS="$JMX_JVM_OPTS -Dcom.sun.management.jmxremote.authenticate=false"
JMX_JVM_OPTS="$JMX_JVM_OPTS -Dcom.sun.management.jmxremote.ssl=false"
JMX_JVM_OPTS="$JMX_JVM_OPTS -Djava.rmi.server.hostname=localhost"

info "Starting PingAccess..."
docker run -d --name "$PA_CONTAINER" --network pa-e2e-net \
    -e PING_IDENTITY_ACCEPT_EULA=YES \
    -e PA_ADMIN_PASSWORD_INITIAL="$PA_PASSWORD" \
    -e PING_IDENTITY_PASSWORD="$PA_PASSWORD" \
    -e JVM_OPTS="$JMX_JVM_OPTS" \
    -v "$LICENSE_FILE:/opt/out/instance/conf/pingaccess.lic:ro" \
    -v "$SHADOW_JAR:/opt/server/deploy/message-xform-adapter.jar:ro" \
    -v "$SPECS_DIR:/specs:ro" \
    -v "$PROFILES_DIR:/profiles:ro" \
    -p "$PA_ADMIN_PORT:9000" \
    -p "$PA_ENGINE_PORT:3000" \
    -p "$JMX_PORT:$JMX_CONTAINER_PORT" \
    "$PA_IMAGE" >/dev/null

# Wait for PA readiness
info "Waiting for PingAccess to start..."
max_wait=120
elapsed=0
while [[ $elapsed -lt $max_wait ]]; do
    if docker logs "$PA_CONTAINER" 2>&1 | grep -q "PingAccess is up" 2>/dev/null; then
        sleep 5
        version=$(curl -sk -u "administrator:$PA_PASSWORD" \
            -H "X-XSRF-Header: PingAccess" \
            "https://localhost:$PA_ADMIN_PORT/pa-admin-api/v3/version" 2>/dev/null \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])" 2>/dev/null || echo "")
        if [[ -n "$version" ]]; then
            info "PingAccess $version is ready (${elapsed}s)"
            break
        fi
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done
if [[ $elapsed -ge $max_wait ]]; then
    fail "PingAccess did not start within ${max_wait}s"
    docker logs "$PA_CONTAINER" 2>&1 | tail -30
    exit 1
fi

# ---------------------------------------------------------------------------
# Step 3: Run Karate tests via Gradle
# ---------------------------------------------------------------------------
echo ""
info "=== Running Karate E2E Tests ==="
echo ""

"$PROJECT_ROOT/gradlew" --no-daemon -p "$PROJECT_ROOT" \
    :e2e-pingaccess:test \
    -DshadowJar="$SHADOW_JAR" \
    ${KARATE_OPTIONS:+-Dkarate.options="$KARATE_OPTIONS"}

karate_exit=$?

echo ""
if [[ $karate_exit -eq 0 ]]; then
    ok "All Karate E2E tests passed"
else
    fail "Karate E2E tests failed (exit code: $karate_exit)"
fi

# Cleanup runs via EXIT trap
exit $karate_exit
