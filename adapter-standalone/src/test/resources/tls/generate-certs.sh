#!/usr/bin/env bash
# generate-certs.sh — Generate self-signed test certificates for TLS tests
# FX-004-06 (server.p12), FX-004-07 (client.p12), FX-004-08 (truststore.p12)
#
# Creates:
#   ca.p12            — CA keystore (signs server + client certs)
#   ca.pem            — CA certificate exported for import into truststore
#   server.p12        — Server certificate + key (signed by CA)
#   client.p12        — Client certificate + key for mTLS (signed by CA)
#   truststore.p12    — Truststore containing CA cert (verifies both server + client)
#
# All passwords: "changeit"
# Validity: 3650 days (10 years — for test stability)

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

PASS="changeit"
VALIDITY=3650
KEY_ALG="RSA"
KEY_SIZE=2048

echo "=== Generating CA ==="
keytool -genkeypair \
  -alias ca \
  -keyalg "$KEY_ALG" -keysize "$KEY_SIZE" \
  -dname "CN=Test CA, OU=message-xform, O=Test, L=Test, ST=Test, C=NL" \
  -ext bc:c \
  -validity "$VALIDITY" \
  -keystore ca.p12 -storetype PKCS12 \
  -storepass "$PASS" -keypass "$PASS" 2>/dev/null

# Export CA certificate as PEM
keytool -exportcert \
  -alias ca \
  -keystore ca.p12 -storetype PKCS12 -storepass "$PASS" \
  -rfc -file ca.pem 2>/dev/null

echo "=== Generating Server Certificate ==="
keytool -genkeypair \
  -alias server \
  -keyalg "$KEY_ALG" -keysize "$KEY_SIZE" \
  -dname "CN=localhost, OU=message-xform, O=Test, L=Test, ST=Test, C=NL" \
  -ext "san=dns:localhost,ip:127.0.0.1" \
  -validity "$VALIDITY" \
  -keystore server.p12 -storetype PKCS12 \
  -storepass "$PASS" -keypass "$PASS" 2>/dev/null

# Create CSR for server
keytool -certreq \
  -alias server \
  -keystore server.p12 -storetype PKCS12 -storepass "$PASS" \
  -file server.csr 2>/dev/null

# Sign server cert with CA
keytool -gencert \
  -alias ca \
  -keystore ca.p12 -storetype PKCS12 -storepass "$PASS" \
  -infile server.csr \
  -outfile server-signed.pem \
  -ext "san=dns:localhost,ip:127.0.0.1" \
  -validity "$VALIDITY" -rfc 2>/dev/null

# Import CA cert into server keystore (chain of trust)
keytool -importcert \
  -alias ca \
  -keystore server.p12 -storetype PKCS12 -storepass "$PASS" \
  -file ca.pem -noprompt 2>/dev/null

# Import signed server cert
keytool -importcert \
  -alias server \
  -keystore server.p12 -storetype PKCS12 -storepass "$PASS" \
  -file server-signed.pem 2>/dev/null

echo "=== Generating Client Certificate ==="
keytool -genkeypair \
  -alias client \
  -keyalg "$KEY_ALG" -keysize "$KEY_SIZE" \
  -dname "CN=Test Client, OU=message-xform, O=Test, L=Test, ST=Test, C=NL" \
  -validity "$VALIDITY" \
  -keystore client.p12 -storetype PKCS12 \
  -storepass "$PASS" -keypass "$PASS" 2>/dev/null

# Create CSR for client
keytool -certreq \
  -alias client \
  -keystore client.p12 -storetype PKCS12 -storepass "$PASS" \
  -file client.csr 2>/dev/null

# Sign client cert with CA
keytool -gencert \
  -alias ca \
  -keystore ca.p12 -storetype PKCS12 -storepass "$PASS" \
  -infile client.csr \
  -outfile client-signed.pem \
  -validity "$VALIDITY" -rfc 2>/dev/null

# Import CA cert into client keystore
keytool -importcert \
  -alias ca \
  -keystore client.p12 -storetype PKCS12 -storepass "$PASS" \
  -file ca.pem -noprompt 2>/dev/null

# Import signed client cert
keytool -importcert \
  -alias client \
  -keystore client.p12 -storetype PKCS12 -storepass "$PASS" \
  -file client-signed.pem 2>/dev/null

echo "=== Creating Truststore ==="
keytool -importcert \
  -alias ca \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$PASS" \
  -file ca.pem -noprompt 2>/dev/null

echo "=== Cleaning up intermediate files ==="
rm -f ca.pem server.csr server-signed.pem client.csr client-signed.pem

echo ""
echo "=== Generated TLS test fixtures ==="
echo "  server.p12      — Server certificate + key (FX-004-06)"
echo "  client.p12      — Client certificate + key (FX-004-07)"
echo "  truststore.p12  — CA truststore (FX-004-08)"
echo "  ca.p12          — CA keystore (internal, for re-signing)"
echo "  Password: $PASS"
echo "=== Done ==="
