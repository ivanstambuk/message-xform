#!/usr/bin/env bash
# ==============================================================================
# generate-keys.sh — Generate TLS keypair for the platform
# ==============================================================================
# Usage:  ./scripts/generate-keys.sh
#
# Generates a self-signed PKCS#12 keystore and public certificate used by all
# platform containers (PingAccess, PingAM, PingDS, PingDirectory).
#
# Enterprise extension points:
#   CUSTOM_KEYSTORE_PATH   — skip generation and use a pre-built keystore
#   CUSTOM_CA_CERT_PATH    — use a custom CA certificate
#   ADDITIONAL_TRUST_CERTS_DIR — directory of additional CA certs to import
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLATFORM_DIR="$(dirname "$SCRIPT_DIR")"
SECRETS_DIR="${PLATFORM_DIR}/secrets"

# ── Step 0: Ensure .env exists ───────────────────────────────────────────────
if [[ ! -f "${PLATFORM_DIR}/.env" ]]; then
    echo "⚠ No .env found. Copying from .env.template..."
    cp "${PLATFORM_DIR}/.env.template" "${PLATFORM_DIR}/.env"
fi

# ── Step 1: Generate SSL password if not already set ─────────────────────────
if ! grep -q '^SSL_PWD=' "${PLATFORM_DIR}/.env" 2>/dev/null; then
    SSL_PWD=$(openssl rand -base64 32 | tr -d '=/+')
    printf "\nSSL_PWD=%s\n" "$SSL_PWD" >> "${PLATFORM_DIR}/.env"
    echo "✓ Generated SSL password"
else
    echo "✓ SSL password already exists in .env"
fi

# Source the .env to get variables
set -a
# shellcheck disable=SC1091
source "${PLATFORM_DIR}/.env"
set +a

# ── Step 2: Create secrets directory ─────────────────────────────────────────
mkdir -p "$SECRETS_DIR"

# ── Step 3: Check for custom keystore override ───────────────────────────────
if [[ -n "${CUSTOM_KEYSTORE_PATH:-}" && -f "${CUSTOM_KEYSTORE_PATH}" ]]; then
    echo "✓ Using custom keystore: ${CUSTOM_KEYSTORE_PATH}"
    cp "$CUSTOM_KEYSTORE_PATH" "${SECRETS_DIR}/tlskey.p12"

    if [[ -n "${CUSTOM_CA_CERT_PATH:-}" && -f "${CUSTOM_CA_CERT_PATH}" ]]; then
        echo "✓ Using custom CA cert: ${CUSTOM_CA_CERT_PATH}"
        cp "$CUSTOM_CA_CERT_PATH" "${SECRETS_DIR}/pubCert.crt"
    else
        # Export public cert from provided keystore
        keytool -exportcert \
            -alias tlskey \
            -keystore "${SECRETS_DIR}/tlskey.p12" \
            -storetype PKCS12 \
            -storepass "${SSL_PWD}" \
            -rfc \
            -file "${SECRETS_DIR}/pubCert.crt"
        echo "✓ Extracted public cert from custom keystore"
    fi
    echo "✓ Custom TLS keypair ready"
    exit 0
fi

# ── Step 4: Generate self-signed keypair ─────────────────────────────────────
echo "Generating self-signed TLS keypair..."

# Backup existing keys
[[ -f "${SECRETS_DIR}/tlskey.p12" ]] && mv "${SECRETS_DIR}/tlskey.p12" "${SECRETS_DIR}/tlskey.p12.bak"
[[ -f "${SECRETS_DIR}/pubCert.crt" ]] && mv "${SECRETS_DIR}/pubCert.crt" "${SECRETS_DIR}/pubCert.crt.bak"

# Build SAN list from all hostnames
SAN_DNS="dns:${TOP_LEVEL_DOMAIN}"
SAN_DNS="${SAN_DNS},dns:${HOSTNAME_PA}"
SAN_DNS="${SAN_DNS},dns:${HOSTNAME_AM}"
SAN_DNS="${SAN_DNS},dns:${HOSTNAME_DS}"
SAN_DNS="${SAN_DNS},dns:${HOSTNAME_PD}"
SAN_DNS="${SAN_DNS},dns:localhost"

keytool -genkey \
    -alias tlskey \
    -keystore "${SECRETS_DIR}/tlskey.p12" \
    -storetype PKCS12 \
    -keyalg RSA \
    -storepass "${SSL_PWD}" \
    -keypass "${SSL_PWD}" \
    -validity 365 \
    -keysize 2048 \
    -dname "CN=${TOP_LEVEL_DOMAIN}" \
    -ext "san=${SAN_DNS}"

echo "✓ Generated PKCS#12 keystore: ${SECRETS_DIR}/tlskey.p12"

# Export public certificate
keytool -exportcert \
    -alias tlskey \
    -keystore "${SECRETS_DIR}/tlskey.p12" \
    -storetype PKCS12 \
    -storepass "${SSL_PWD}" \
    -rfc \
    -file "${SECRETS_DIR}/pubCert.crt"

echo "✓ Exported public cert: ${SECRETS_DIR}/pubCert.crt"

# ── Step 5: Import additional trust certs (enterprise extension point) ───────
if [[ -n "${ADDITIONAL_TRUST_CERTS_DIR:-}" && -d "${ADDITIONAL_TRUST_CERTS_DIR}" ]]; then
    echo "Importing additional trust certificates from: ${ADDITIONAL_TRUST_CERTS_DIR}"
    for cert_file in "${ADDITIONAL_TRUST_CERTS_DIR}"/*.{crt,pem,cer}; do
        [[ -f "$cert_file" ]] || continue
        cert_alias="$(basename "$cert_file" | sed 's/\.[^.]*$//')"
        keytool -importcert \
            -alias "$cert_alias" \
            -file "$cert_file" \
            -keystore "${SECRETS_DIR}/tlskey.p12" \
            -storetype PKCS12 \
            -storepass "${SSL_PWD}" \
            -trustcacerts \
            -noprompt
        echo "  ✓ Imported: ${cert_file} (alias: ${cert_alias})"
    done
fi

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  TLS keypair generated successfully"
echo "  Keystore:   ${SECRETS_DIR}/tlskey.p12"
echo "  Public cert: ${SECRETS_DIR}/pubCert.crt"
echo "  SAN entries: ${SAN_DNS}"
echo "═══════════════════════════════════════════════════════════════"
