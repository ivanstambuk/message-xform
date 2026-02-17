#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Platform E2E Test Runner
# ---------------------------------------------------------------------------
# Runs Karate E2E tests against the platform deployment using the
# standalone Karate JAR. No Gradle — the platform stack is manually
# managed via docker compose, so Gradle Docker lifecycle makes no sense.
#
# Prerequisites:
#   cd deployments/platform && docker compose up -d
#   # Wait for all containers to be healthy
#
# Usage:
#   ./run-e2e.sh                     # Run all tests
#   ./run-e2e.sh auth-login.feature  # Run specific feature
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KARATE_VERSION="1.4.1"
KARATE_JAR="${SCRIPT_DIR}/karate-${KARATE_VERSION}.jar"

# Download standalone Karate JAR if not present
if [[ ! -f "${KARATE_JAR}" ]]; then
    echo "⬇  Downloading Karate standalone ${KARATE_VERSION}..."
    curl -sSfL -o "${KARATE_JAR}" \
        "https://github.com/karatelabs/karate/releases/download/v${KARATE_VERSION}/karate-${KARATE_VERSION}.jar"
    echo "✅ Downloaded: ${KARATE_JAR}"
fi

# Default: run all .feature files in the e2e directory
TARGET="${1:-.}"

echo "🚀 Running platform E2E tests..."
echo "   Target: ${TARGET}"
echo "   Config: ${SCRIPT_DIR}/karate-config.js"
echo ""

cd "${SCRIPT_DIR}"
java -jar "${KARATE_JAR}" "${TARGET}"
