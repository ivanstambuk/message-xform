#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Platform E2E Test Runner
# ---------------------------------------------------------------------------
# Runs Karate E2E tests against the platform deployment using the
# standalone Karate JAR. No Gradle — the platform stack is manually
# managed via docker compose or kubectl, so Gradle Docker lifecycle
# makes no sense.
#
# Environments:
#   docker (default):  docker compose up -d
#   k8s:               k3s cluster with Traefik IngressRoute
#
# Usage:
#   ./run-e2e.sh                              # Docker, all tests
#   ./run-e2e.sh auth-login.feature           # Docker, single feature
#   ./run-e2e.sh --env k8s                    # K8s, all tests
#   ./run-e2e.sh --env k8s auth-login.feature # K8s, single feature
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KARATE_VERSION="1.4.1"
KARATE_JAR="${SCRIPT_DIR}/karate-${KARATE_VERSION}.jar"
KARATE_ENV="docker"
PF_PIDS=()

# Parse arguments
POSITIONAL=()
while [[ $# -gt 0 ]]; do
    case $1 in
        --env)
            KARATE_ENV="$2"
            shift 2
            ;;
        *)
            POSITIONAL+=("$1")
            shift
            ;;
    esac
done
set -- "${POSITIONAL[@]:-}"

# Download standalone Karate JAR if not present
if [[ ! -f "${KARATE_JAR}" ]]; then
    echo "⬇  Downloading Karate standalone ${KARATE_VERSION}..."
    curl -sSfL -o "${KARATE_JAR}" \
        "https://github.com/karatelabs/karate/releases/download/v${KARATE_VERSION}/karate-${KARATE_VERSION}.jar"
    echo "✅ Downloaded: ${KARATE_JAR}"
fi

# Cleanup function for port-forwards
cleanup() {
    for pid in "${PF_PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done
}

# K8s environment: start port-forwards
if [[ "${KARATE_ENV}" == "k8s" ]]; then
    export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"
    NS="message-xform"

    echo "🔌 Starting K8s port-forwards..."

    # AM direct access (for admin/provisioning operations)
    kubectl port-forward svc/pingam 28080:8080 -n "${NS}" &
    PF_PIDS+=($!)

    # PA Admin API (for PA configuration verification)
    kubectl port-forward svc/pingaccess-admin 29000:9000 -n "${NS}" &
    PF_PIDS+=($!)

    trap cleanup EXIT

    # Wait for port-forwards to establish
    sleep 2
    echo "✅ Port-forwards active: AM=28080, PA Admin=29000"
    echo "   PA Engine via Traefik: https://localhost (port 443)"
fi

# Default: run all .feature files in the e2e directory
TARGET="${1:-.}"

echo "🚀 Running platform E2E tests..."
echo "   Environment: ${KARATE_ENV}"
echo "   Target: ${TARGET}"
echo "   Config: ${SCRIPT_DIR}/karate-config.js"
echo ""

cd "${SCRIPT_DIR}"
java -Dkarate.env="${KARATE_ENV}" -jar "${KARATE_JAR}" "${TARGET}"
