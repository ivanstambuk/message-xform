#!/usr/bin/env bash
# ==============================================================================
# configure-am.sh — Automate PingAM first-run configuration
# ==============================================================================
# Performs the complete PingAM initial setup:
#   1. Waits for PingDirectory to be healthy
#   2. Imports PingDirectory's TLS certificate into AM's JVM truststore
#   3. Sends the configurator POST to /am/config/configurator
#   4. Waits for configuration to complete (2-5 minutes)
#   5. Verifies admin authentication works
#
# Prerequisites:
#   - PingDirectory container running and healthy
#   - PingAM container running (Tomcat started, unconfigured)
#   - Both on the same Docker network
#
# Usage:
#   ./scripts/configure-am.sh
#   ./scripts/configure-am.sh --skip-cert   # if cert already imported
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
    echo "❌ No .env found. Run ./scripts/generate-keys.sh first."
    exit 1
fi

# ── Configuration ─────────────────────────────────────────────────────────────
AM_CONTAINER="${AM_CONTAINER:-platform-pingam}"
PD_CONTAINER="${PD_CONTAINER:-platform-pingdirectory}"

# AM uses HTTP internally (port 8080 inside container)
AM_INTERNAL_URL="http://${HOSTNAME_AM}:8080"
AM_HTTP_PORT="${AM_HTTP_PORT:-8080}"

PD_PASSWORD="${PD_ROOT_USER_PWD:-2FederateM0re}"
PD_DN="${PD_ROOT_USER_DN:-cn=administrator}"
PD_PORT="${PD_LDAPS_PORT:-1636}"
PD_BASE="${PD_BASE_DN:-dc=example,dc=com}"

AM_ADMIN_PWD="${PINGAM_ADMIN_PASSWORD:-Password1}"
AM_DEPLOYMENT_URI="${PINGAM_DEPLOYMENT_URI:-am}"
COOKIE_DOMAIN="${TOP_LEVEL_DOMAIN:-platform.local}"

SKIP_CERT=false
[[ "${1:-}" == "--skip-cert" ]] && SKIP_CERT=true

# ── Helper functions ──────────────────────────────────────────────────────────

log()  { echo "$(date +%H:%M:%S) ── $*"; }
ok()   { echo "$(date +%H:%M:%S) ✓  $*"; }
fail() { echo "$(date +%H:%M:%S) ❌ $*" >&2; exit 1; }

wait_for_container() {
    local name="$1" max_wait="${2:-60}" elapsed=0
    log "Waiting for container '${name}' to be running..."
    while [[ $elapsed -lt $max_wait ]]; do
        if docker inspect --format='{{.State.Running}}' "$name" 2>/dev/null | grep -q "true"; then
            ok "Container '${name}' is running"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    fail "Container '${name}' not running after ${max_wait}s"
}

wait_for_healthy() {
    local name="$1" max_wait="${2:-180}" elapsed=0
    log "Waiting for container '${name}' to be healthy..."
    while [[ $elapsed -lt $max_wait ]]; do
        local status
        status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo "unknown")
        if [[ "$status" == "healthy" ]]; then
            ok "Container '${name}' is healthy"
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    fail "Container '${name}' not healthy after ${max_wait}s (status: ${status:-unknown})"
}

wait_for_http() {
    local url="$1" max_wait="${2:-60}" elapsed=0
    log "Waiting for HTTP response from ${url}..."
    while [[ $elapsed -lt $max_wait ]]; do
        local http_code
        http_code=$(curl -sf -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
        if [[ "$http_code" != "000" ]]; then
            ok "HTTP response: ${http_code}"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    fail "No HTTP response from ${url} after ${max_wait}s"
}

# ── Step 1: Wait for PingDirectory ────────────────────────────────────────────
log "Step 1/5 — Waiting for PingDirectory"
wait_for_container "$PD_CONTAINER"
wait_for_healthy "$PD_CONTAINER" 180

# ── Step 2: Wait for PingAM (Tomcat) ─────────────────────────────────────────
log "Step 2/5 — Waiting for PingAM"
wait_for_container "$AM_CONTAINER"
# Wait for Tomcat to start (HTTP on port 8080 inside container, mapped to AM_HTTP_PORT)
wait_for_http "http://localhost:${AM_HTTP_PORT}" 60

# ── Step 3: Import PD certificate into AM's truststore ────────────────────────
if [[ "$SKIP_CERT" == "true" ]]; then
    log "Step 3/5 — Skipping cert import (--skip-cert)"
else
    log "Step 3/5 — Importing PingDirectory TLS certificate into AM's JVM truststore"

    # Extract PD's certificate from the Docker network
    # Use the host-mapped port to reach PD
    local_pd_port=$(docker port "$PD_CONTAINER" 1636/tcp 2>/dev/null | head -1 | cut -d: -f2)
    if [[ -z "$local_pd_port" ]]; then
        # Fallback: use the default host port
        local_pd_port="$PD_PORT"
    fi

    CERT_FILE=$(mktemp /tmp/pd-cert-XXXXXX.pem)
    openssl s_client -connect "localhost:${local_pd_port}" -showcerts </dev/null 2>/dev/null \
        | openssl x509 -outform PEM > "$CERT_FILE"

    if [[ ! -s "$CERT_FILE" ]]; then
        fail "Could not extract PD certificate from localhost:${local_pd_port}"
    fi

    # Copy cert into AM container
    docker cp "$CERT_FILE" "${AM_CONTAINER}:/tmp/pd-cert.pem"

    # Import as root (AM runs as uid 11111, but cacerts is root-owned)
    docker exec -u 0 "$AM_CONTAINER" keytool -importcert \
        -alias pingdirectory \
        -file /tmp/pd-cert.pem \
        -cacerts \
        -storepass changeit \
        -trustcacerts \
        -noprompt 2>&1 | grep -v "^$" || true

    rm -f "$CERT_FILE"
    ok "PD certificate imported into AM's JVM truststore"
fi

# ── Step 4: Run the configurator ──────────────────────────────────────────────
log "Step 4/5 — Configuring PingAM (this takes 2-5 minutes)"

# Check if AM is already configured by trying admin auth
log "Checking if AM is already configured..."
AUTH_CHECK=$(curl -sf -o /dev/null -w "%{http_code}" \
    --resolve "${HOSTNAME_AM}:${AM_HTTP_PORT}:127.0.0.1" \
    -X POST "http://${HOSTNAME_AM}:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}/json/authenticate" \
    -H "Content-Type: application/json" \
    -H "X-OpenAM-Username: amAdmin" \
    -H "X-OpenAM-Password: ${AM_ADMIN_PWD}" \
    -H "Accept-API-Version: resource=2.0,protocol=1.0" 2>/dev/null || echo "000")

if [[ "$AUTH_CHECK" == "200" ]]; then
    ok "AM is already configured and admin auth works. Skipping configuration."
    exit 0
fi
log "AM is not yet configured (auth returned HTTP ${AUTH_CHECK}). Proceeding..."

# Send the configurator POST
# Content-Type is application/x-www-form-urlencoded (NOT JSON)
HTTP_CODE=$(curl -sf -o /tmp/am-config-response.txt -w "%{http_code}" \
    --resolve "${HOSTNAME_AM}:${AM_HTTP_PORT}:127.0.0.1" \
    -X POST "http://${HOSTNAME_AM}:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}/config/configurator" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "SERVER_URL=http://${HOSTNAME_AM}:8080" \
    --data-urlencode "DEPLOYMENT_URI=${AM_DEPLOYMENT_URI}" \
    --data-urlencode "BASE_DIR=/home/forgerock/openam" \
    --data-urlencode "locale=en_US" \
    --data-urlencode "PLATFORM_LOCALE=en_US" \
    --data-urlencode "AM_ENC_KEY=$(openssl rand -base64 24)" \
    --data-urlencode "ADMIN_PWD=${AM_ADMIN_PWD}" \
    --data-urlencode "ADMIN_CONFIRM_PWD=${AM_ADMIN_PWD}" \
    --data-urlencode "AMLDAPUSERPASSWD=${AM_ADMIN_PWD}" \
    --data-urlencode "COOKIE_DOMAIN=${COOKIE_DOMAIN}" \
    --data-urlencode "acceptLicense=true" \
    --data-urlencode "DATA_STORE=dirServer" \
    --data-urlencode "DIRECTORY_SSL=SSL" \
    --data-urlencode "DIRECTORY_SERVER=${HOSTNAME_PD}" \
    --data-urlencode "DIRECTORY_PORT=${PD_PORT}" \
    --data-urlencode "DIRECTORY_ADMIN_PORT=4444" \
    --data-urlencode "DIRECTORY_JMX_PORT=1689" \
    --data-urlencode "ROOT_SUFFIX=${PD_BASE}" \
    --data-urlencode "DS_DIRMGRDN=${PD_DN}" \
    --data-urlencode "DS_DIRMGRPASSWD=${PD_PASSWORD}" \
    --data-urlencode "USERSTORE_TYPE=LDAPv3ForOpenDS" \
    --data-urlencode "USERSTORE_SSL=SSL" \
    --data-urlencode "USERSTORE_HOST=${HOSTNAME_PD}" \
    --data-urlencode "USERSTORE_PORT=${PD_PORT}" \
    --data-urlencode "USERSTORE_SUFFIX=${PD_BASE}" \
    --data-urlencode "USERSTORE_MGRDN=${PD_DN}" \
    --data-urlencode "USERSTORE_PASSWD=${PD_PASSWORD}" \
    2>/dev/null)

if [[ "$HTTP_CODE" -ge 200 && "$HTTP_CODE" -lt 400 ]]; then
    ok "Configurator accepted (HTTP ${HTTP_CODE})"
else
    cat /tmp/am-config-response.txt 2>/dev/null || true
    fail "Configurator returned HTTP ${HTTP_CODE}"
fi

# Wait for configuration to complete by watching the install log
log "Waiting for configuration to complete..."
MAX_CONFIG_WAIT=300  # 5 minutes
CONFIG_ELAPSED=0
while [[ $CONFIG_ELAPSED -lt $MAX_CONFIG_WAIT ]]; do
    # Check if boot.json exists (signals configuration complete)
    if docker exec "$AM_CONTAINER" test -f /home/forgerock/openam/config/boot.json 2>/dev/null; then
        # Count successful steps
        SUCCESS_COUNT=$(docker exec "$AM_CONTAINER" grep -c "Success\." /home/forgerock/openam/var/install.log 2>/dev/null || echo "0")
        FAIL_COUNT=$(docker exec "$AM_CONTAINER" grep -ci "fail\|error" /home/forgerock/openam/var/install.log 2>/dev/null || echo "0")
        ok "Configuration complete! (${SUCCESS_COUNT} successful steps, ${FAIL_COUNT} entries with fail/error)"
        break
    fi
    sleep 5
    CONFIG_ELAPSED=$((CONFIG_ELAPSED + 5))
    # Print progress every 15 seconds
    if (( CONFIG_ELAPSED % 15 == 0 )); then
        SUCCESS_COUNT=$(docker exec "$AM_CONTAINER" grep -c "Success\." /home/forgerock/openam/var/install.log 2>/dev/null || echo "0")
        log "  ... ${SUCCESS_COUNT} steps completed so far (${CONFIG_ELAPSED}s elapsed)"
    fi
done

if [[ $CONFIG_ELAPSED -ge $MAX_CONFIG_WAIT ]]; then
    fail "Configuration did not complete within ${MAX_CONFIG_WAIT}s"
fi

# ── Step 5: Verify admin authentication ───────────────────────────────────────
log "Step 5/5 — Verifying admin authentication"

# First request after config triggers lazy initialization (30-60s)
log "Sending first auth request (may take 30-60s for lazy init)..."
sleep 5  # brief pause for AM to restart after config

MAX_AUTH_WAIT=90
AUTH_ELAPSED=0
while [[ $AUTH_ELAPSED -lt $MAX_AUTH_WAIT ]]; do
    AUTH_RESPONSE=$(curl -sf \
        --resolve "${HOSTNAME_AM}:${AM_HTTP_PORT}:127.0.0.1" \
        -X POST "http://${HOSTNAME_AM}:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}/json/authenticate" \
        -H "Content-Type: application/json" \
        -H "X-OpenAM-Username: amAdmin" \
        -H "X-OpenAM-Password: ${AM_ADMIN_PWD}" \
        -H "Accept-API-Version: resource=2.0,protocol=1.0" \
        2>/dev/null || echo "")

    if echo "$AUTH_RESPONSE" | grep -q "tokenId"; then
        TOKEN_ID=$(echo "$AUTH_RESPONSE" | grep -o '"tokenId":"[^"]*"' | head -1)
        ok "Admin authentication successful!"
        ok "  ${TOKEN_ID}"
        break
    fi

    sleep 5
    AUTH_ELAPSED=$((AUTH_ELAPSED + 5))
    if (( AUTH_ELAPSED % 15 == 0 )); then
        log "  ... waiting for AM to initialize (${AUTH_ELAPSED}s)..."
    fi
done

if [[ $AUTH_ELAPSED -ge $MAX_AUTH_WAIT ]]; then
    fail "Admin authentication failed after ${MAX_AUTH_WAIT}s. Check AM logs: docker logs ${AM_CONTAINER}"
fi

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  PingAM configuration complete!"
echo ""
echo "  Admin console: http://localhost:${AM_HTTP_PORT}/${AM_DEPLOYMENT_URI}"
echo "  Admin user:    amAdmin"
echo "  Admin pass:    ${AM_ADMIN_PWD}"
echo "  Cookie domain: ${COOKIE_DOMAIN}"
echo ""
echo "  Config store:  ${HOSTNAME_PD}:${PD_PORT} (${PD_BASE})"
echo "  User store:    ${HOSTNAME_PD}:${PD_PORT} (${PD_BASE})"
echo "═══════════════════════════════════════════════════════════════"
