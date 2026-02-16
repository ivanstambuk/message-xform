# Platform Deployment — PingAccess + PingAM + PingDirectory

> End-to-end identity platform combining PingAccess (reverse proxy with
> message-xform plugin), PingAM (authentication journeys), and PingDirectory
> (config store, CTS, user store — single directory for all PingAM needs).

## Architecture

```
                 ┌─────────────────────────┐
   Browser ────▶│     PingAccess  (9.0)    │
                │  (reverse proxy)         │
                │  + message-xform plugin  │
                │  port 3000               │
                └───────────┬──────────────┘
                            │ proxied HTTP
                            ▼
                 ┌─────────────────────────┐
                 │     PingAM  (8.0.2)     │
                 │  (authentication)       │
                 │  journeys, OAuth2       │
                 │  port 8080 (/am)        │
                 └──────────┬──────────────┘
                            │ LDAPS (config +
                            │ CTS + users)
                            ▼
                 ┌─────────────────────────┐
                 │  PingDirectory (11.0)   │
                 │  (all-in-one store)     │
                 │  AM config + CTS +      │
                 │  user/identity store    │
                 │  port 1636              │
                 └─────────────────────────┘
```

### Key Finding: Single PingDirectory

Live testing (Feb 16, 2026) confirmed that **PingAM 8.0.2 runs directly on
PingDirectory 11.0** without needing PingDS (ForgeRock DS) as a separate
container. Two PingDirectory configuration tweaks are required:

1. **Schema relaxation**: `single-structural-objectclass-behavior: accept`
   (PingAM writes entries without structural objectClasses that PingDirectory
   normally rejects)
2. **ETag virtual attribute**: A mirror virtual attribute maps the `etag`
   attribute (expected by PingAM CTS) to PingDirectory's native
   `ds-entry-checksum`

This eliminates PingDS entirely, giving us a clean 3-container deployment.

## Products

| Product | Docker Image | Version | Role |
|---------|--------------|---------|------|
| PingAccess | `pingidentity/pingaccess` (Docker Hub) | 9.0 | Reverse proxy, message-xform plugin host |
| PingAM | Custom Docker image (WAR on Tomcat) | 8.0.2 | Authentication engine, journeys, OAuth2 |
| PingDirectory | `pingidentity/pingdirectory` (Docker Hub) | 11.0 | Config store, CTS, policy store, user/identity store |

## Quick Start

```bash
# 1. Generate TLS keypair and .env
./scripts/generate-keys.sh

# 2. Build custom PingAM image if not already built
cd ../../binaries/pingam && docker build . -f docker/Dockerfile -t pingam:8.0.2

# 3. Start the platform
docker compose up -d

# 4. Configure PingAM (first run only)
./scripts/configure-am.sh

# 5. Import authentication journeys
./scripts/import-journeys.sh
```

## Directory Layout

```
deployments/platform/
├── README.md                 ← This file
├── PLAN.md                   ← Phased implementation plan with live tracker
├── docker-compose.yml        ← 3-container orchestration
├── .env.template             ← Environment template (passwords, hostnames)
├── scripts/
│   ├── generate-keys.sh      ← TLS keypair generation (extensible for enterprise certs)
│   ├── configure-am.sh       ← PingAM initial setup via REST API
│   └── import-journeys.sh    ← Import authentication trees/journeys
├── config/
│   ├── server.xml            ← Tomcat SSL configuration for PingAM
│   └── pd-post-setup.sh      ← PingDirectory post-setup (schema relaxation + etag VA)
├── secrets/                  ← Generated at runtime (gitignored)
│   ├── tlskey.p12
│   └── pubCert.crt
└── journeys/                 ← PingAM authentication tree exports
    ├── UsernamePassword.json
    └── WebAuthn.json
```

## TLS Certificate Extension Points

The `scripts/generate-keys.sh` generates a self-signed dev keypair by default.

For enterprise deployments, the following extension points exist:
- **Custom CA cert**: Mount your CA certificate chain via `CUSTOM_CA_CERT_PATH`
- **Custom keystore**: Supply a pre-built PKCS#12 keystore via `CUSTOM_KEYSTORE_PATH`
- **Trust store**: Import additional root/intermediate CAs via `ADDITIONAL_TRUST_CERTS_DIR`

See [Platform Deployment Guide](../../docs/operations/platform-deployment-guide.md) for details.

## See Also

- [Implementation Plan](./PLAN.md) — phased plan with live tracker
- [PingAM Operations Guide](../../docs/operations/pingam-operations-guide.md) — individual image build
- [PingAccess Operations Guide](../../docs/operations/pingaccess-operations-guide.md) — individual image build
- [Platform Deployment Guide](../../docs/operations/platform-deployment-guide.md) — comprehensive walkthrough
