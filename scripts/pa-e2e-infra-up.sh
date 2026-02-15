#!/usr/bin/env bash
# ============================================================================
# PingAccess E2E — Idempotent Infrastructure Startup
# ============================================================================
# Starts Docker containers (echo backend, mock-OIDC, PingAccess) if not
# already running.  Extracted from pa-e2e-bootstrap.sh for Gradle task use.
#
# Creates a marker file .e2e-infra-started in the project root when this
# script actually starts containers.  pa-e2e-infra-down.sh checks this
# marker to decide whether to tear down (avoids destroying manually-started
# infrastructure).
#
# Usage:  bash scripts/pa-e2e-infra-up.sh
# ============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARKER="$PROJECT_ROOT/.e2e-infra-started"

# ---------------------------------------------------------------------------
# Configuration  (keep in sync with pa-e2e-bootstrap.sh)
# ---------------------------------------------------------------------------
PA_IMAGE="pingidentity/pingaccess:latest"
PA_CONTAINER="pa-e2e-test"
ECHO_CONTAINER="pa-e2e-echo"
OIDC_CONTAINER="pa-e2e-oidc"
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

# Colours
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[PASS]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }

# ---------------------------------------------------------------------------
# Idempotency check — skip if PA is already responding
# ---------------------------------------------------------------------------
if curl -sfk -u "administrator:$PA_PASSWORD" \
    -H "X-XSRF-Header: PingAccess" \
    "https://localhost:$PA_ADMIN_PORT/pa-admin-api/v3/version" >/dev/null 2>&1; then
    info "PingAccess E2E infrastructure already running — skipping startup"
    exit 0
fi

# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------
info "=== PingAccess E2E Infrastructure Startup ==="
echo ""

if ! command -v docker &>/dev/null; then
    fail "Docker is not installed"
    exit 1
fi

if [[ ! -f "$LICENSE_FILE" ]]; then
    fail "License file not found: $LICENSE_FILE"
    exit 1
fi

if [[ ! -f "$SHADOW_JAR" ]]; then
    fail "Shadow JAR not found: $SHADOW_JAR (run :adapter-pingaccess:shadowJar first)"
    exit 1
fi

if ! docker image inspect "$PA_IMAGE" &>/dev/null; then
    info "Pulling PingAccess image..."
    docker pull "$PA_IMAGE"
fi

# ---------------------------------------------------------------------------
# Clean up any stale containers
# ---------------------------------------------------------------------------
docker rm -f "$PA_CONTAINER" "$ECHO_CONTAINER" "$OIDC_CONTAINER" 2>/dev/null || true
docker network rm pa-e2e-net 2>/dev/null || true

# ---------------------------------------------------------------------------
# Start containers
# ---------------------------------------------------------------------------
info "Creating Docker network..."
docker network create pa-e2e-net >/dev/null

# 1. Echo backend (RQ-21)
info "Starting echo backend..."
docker run -d --name "$ECHO_CONTAINER" --network pa-e2e-net \
    -p "$ECHO_PORT:8080" \
    python:3.12-alpine \
    python3 -c '
import http.server, json, sys, re

class EchoHandler(http.server.BaseHTTPRequestHandler):
    def handle_request(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else b"{}"

        if "/html/" in self.path:
            html_body = b"<html><body><h1>Test Page</h1></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(html_body)))
            self._send_echo_headers()
            self.end_headers()
            self.wfile.write(html_body)
            return

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

        ct = self.headers.get("Content-Type", "application/json")
        self.send_response(200)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(body)))
        self._send_echo_headers()
        self.end_headers()
        self.wfile.write(body)

    def _send_echo_headers(self):
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
        pass

http.server.HTTPServer(("0.0.0.0", 8080), EchoHandler).serve_forever()
' >/dev/null

sleep 2
info "Echo backend ready"

# 2. Mock OAuth2 server
info "Starting mock-oauth2-server..."
docker run -d --name "$OIDC_CONTAINER" --network pa-e2e-net \
    -p "$OIDC_PORT:8080" \
    ghcr.io/navikt/mock-oauth2-server:latest >/dev/null

info "Waiting for mock-oauth2-server..."
oidc_ready=false
for i in $(seq 1 30); do
    if curl -sf "http://localhost:$OIDC_PORT/default/.well-known/openid-configuration" >/dev/null 2>&1; then
        oidc_ready=true
        break
    fi
    sleep 1
done
if [[ "$oidc_ready" == "true" ]]; then
    info "mock-oauth2-server ready (${i}s)"
else
    fail "mock-oauth2-server did not start within 30s"
    docker logs "$OIDC_CONTAINER" 2>&1 | tail -20
    exit 1
fi

# 3. PingAccess
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

# ---------------------------------------------------------------------------
# Wait for PA readiness
# ---------------------------------------------------------------------------
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
            ok "PingAccess $version is ready (${elapsed}s)"
            # Write marker so teardown script knows we started it
            echo "started=$(date -Iseconds)" > "$MARKER"
            exit 0
        fi
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done

fail "PingAccess did not start within ${max_wait}s"
docker logs "$PA_CONTAINER" 2>&1 | tail -30
exit 1
