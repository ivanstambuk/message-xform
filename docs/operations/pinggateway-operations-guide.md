# PingGateway Operations Guide

> Practitioner's guide for building, running, configuring, and debugging PingGateway
> for the message-xform adapter.

| Field        | Value                                                          |
|--------------|----------------------------------------------------------------|
| Status       | Living document                                                |
| Last updated | 2026-02-16                                                     |
| Audience     | Developers, operators, CI/CD pipeline authors                  |
| See also     | [`pinggateway-sdk-guide.md`](../reference/pinggateway-sdk-guide.md) (SDK reference) |

---

## Topic Index

| # | Section | One-liner |
|---|---------|-----------|
| | **Part I — Docker Image** | |
| 1 | [Docker Image Build](#1-docker-image-build) | Building the evaluation Docker image from the distribution ZIP |
| 2 | [Running PingGateway](#2-running-pinggateway) | Container startup, ports, volumes, environment |
| 3 | [Filesystem Layout](#3-filesystem-layout) | Key paths inside the container |
| 4 | [Configuration Mounting](#4-configuration-mounting) | Injecting routes, config, and custom JARs |
| | **Part II — Configuration** | |
| 5 | [Default Configuration](#5-default-configuration) | What ships out of the box |
| 6 | [TLS Configuration](#6-tls-configuration) | Enabling HTTPS on the gateway |
| | **Part III — Rebuild Playbook** | |
| 7 | [Full Rebuild from Scratch](#7-full-rebuild-from-scratch) | Step-by-step for a clean machine |

---

## Part I — Docker Image

### 1. Docker Image Build

PingGateway ships a Dockerfile inside the distribution ZIP. The image is
**evaluation-only** — Ping Identity provides no commercial support for it in
production.

#### Prerequisites

- Docker daemon running (`docker info` to verify)
- PingGateway distribution extracted at:
  `binaries/pinggateway/dist/identity-gateway-2025.11.1/`
- The distribution ZIP (`PingGateway-2025.11.1.zip`) is downloaded from
  [Ping Identity Download Center (Backstage)](https://backstage.pingidentity.com/downloads)

#### Build command

```bash
# From the extracted distribution directory
cd binaries/pinggateway/dist/identity-gateway-2025.11.1
docker build . -f docker/Dockerfile -t pinggateway:2025.11.1
```

#### What the Dockerfile does

```dockerfile
FROM gcr.io/forgerock-io/java-21:latest    # ForgeRock Java 21 base (public, no auth)
ENV INSTALL_DIR /opt/ig
COPY --chown=forgerock:root . "${INSTALL_DIR}"
ENV IG_INSTANCE_DIR /var/ig
RUN mkdir -p "${IG_INSTANCE_DIR}" \
    && chown -R forgerock:root "${IG_INSTANCE_DIR}" "${INSTALL_DIR}" \
    && chmod -R g+rwx "${IG_INSTANCE_DIR}" "${INSTALL_DIR}"
USER 11111
ENTRYPOINT ${INSTALL_DIR}/bin/start.sh ${IG_INSTANCE_DIR}
```

- **Base image**: `gcr.io/forgerock-io/java-21:latest` — publicly accessible
  (no GCR auth required), ~183 MB
- **User**: `forgerock` (uid `11111`) — non-root
- **Final image size**: ~327 MB

#### Verify

```bash
docker image list | grep pinggateway
# Expected: pinggateway  2025.11.1  <id>  327MB
```

### 2. Running PingGateway

#### Basic run

```bash
# Start on port 8080 (foreground)
docker run -p 8080:8080 pinggateway:2025.11.1

# Start on port 8080 (detached)
docker run -d --name pinggateway -p 8080:8080 pinggateway:2025.11.1
```

#### Port mapping

| Container port | Purpose |
|----------------|---------|
| `8080` | Main gateway HTTP listener |
| `8085` | Admin endpoint (Studio, metrics) |

```bash
# Expose both gateway and admin ports
docker run -d --name pinggateway \
  -p 8080:8080 \
  -p 8085:8085 \
  pinggateway:2025.11.1
```

#### Smoke test

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080
# Expected: 200 (welcome page)
```

#### Stop

```bash
docker stop pinggateway
docker rm pinggateway
```

#### Run options

| Option | Example | Purpose |
|--------|---------|---------|
| PID override | `-e "IG_OPTS=-Dig.pid.file.mode=override"` | Allow restart if PID file exists |
| Custom port | `-p 8090:8080` | Map gateway to host port 8090 |
| Config volume | `-v $HOME/.openig:/var/ig/` | Mount local config directory |
| User override | `--user 11111` | Explicit UID for the process |
| Interactive | `-it pinggateway:2025.11.1 sh` | Shell access for debugging |

### 3. Filesystem Layout

| Path | Purpose |
|------|---------|
| `/opt/ig/` | PingGateway binaries (read-only after build) |
| `/opt/ig/bin/start.sh` | Entrypoint script |
| `/opt/ig/lib/` | Gateway JARs (~72 MB of libraries) |
| `/var/ig/` | Instance directory (`$IG_INSTANCE_DIR`) |
| `/var/ig/config/config.json` | Main gateway configuration |
| `/var/ig/config/admin.json` | Admin endpoint configuration |
| `/var/ig/config/routes/` | Route definitions (auto-created) |
| `/var/ig/scripts/groovy/` | Groovy scripts for filters/handlers |
| `/var/ig/lib/` | Custom JARs (plugins, adapters) |

### 4. Configuration Mounting

#### Mount a local config directory

```bash
docker run -d --name pinggateway \
  -p 8080:8080 \
  -v /path/to/my-ig-config:/var/ig/ \
  pinggateway:2025.11.1
```

The local directory should mirror the `/var/ig/` structure:

```
my-ig-config/
├── config/
│   ├── config.json          # Main config (optional — default used if absent)
│   ├── admin.json           # Admin config (optional)
│   └── routes/
│       └── my-route.json    # Route definitions
├── scripts/
│   └── groovy/
│       └── MyFilter.groovy  # Groovy scripts
└── lib/
    └── my-plugin.jar        # Custom JARs
```

#### Inject a custom plugin JAR (e.g., message-xform adapter)

```bash
docker run -d --name pinggateway \
  -p 8080:8080 \
  -v /path/to/adapter.jar:/var/ig/lib/adapter.jar:ro \
  -v /path/to/routes:/var/ig/config/routes:ro \
  pinggateway:2025.11.1
```

---

## Part II — Configuration

### 5. Default Configuration

When no `config.json` is provided, PingGateway starts with a default
configuration that:

- Serves a welcome page at `/`
- Creates `/var/ig/config/routes/` if it doesn't exist
- Listens on port `8080` (HTTP)
- Starts an admin endpoint on port `8085`

**Log output on default startup** (notable lines):

```
[main] INFO  o.forgerock.openig.standalone.Start @system -
  /var/ig/config/config.json not readable, using default config.json
[main] WARN  o.f.o.handler.router.RouterHandler @system -
  The route directory '/var/ig/config/routes' does not exist. Trying to create it.
[main] INFO  o.f.openig.launcher.Launcher @system -
  Gateway 16 verticles started on ports : [8080], Admin verticle started on port : 8085
```

### 6. TLS Configuration

To enable HTTPS, create a keystore and configure `admin.json`:

```bash
# Generate a self-signed certificate inside the container
docker exec -it pinggateway keytool -genkey \
  -alias ig \
  -keyalg RSA \
  -keystore /var/ig/keystore \
  -storepass password \
  -keypass password \
  -dname "CN=openig.example.com,O=Example Corp,C=FR"
```

Then add a connector to `/var/ig/config/admin.json`:

```json
{
  "port": 8443,
  "tls": {
    "type": "ServerTlsOptions",
    "config": {
      "keyManager": {
        "type": "SecretsKeyManager",
        "config": {
          "signingSecretId": "key.manager.secret.id",
          "secretsProvider": {
            "type": "KeyStoreSecretStore",
            "config": {
              "file": "/var/ig/keystore",
              "storePassword": "keystore.pass",
              "storeType": "JKS",
              "secretsProvider": {
                "type": "Base64EncodedSecretStore",
                "config": {
                  "secrets": {
                    "keystore.pass": "cGFzc3dvcmQ="
                  }
                }
              },
              "mappings": [
                {
                  "secretId": "key.manager.secret.id",
                  "aliases": ["ig"]
                }
              ]
            }
          }
        }
      }
    }
  }
}
```

Restart the container and verify: `curl -vk https://localhost:8443`

---

## Part III — Rebuild Playbook

### 7. Full Rebuild from Scratch

Use this section when setting up PingGateway Docker on a **new machine** from
zero.

#### Step 1: Download the distribution

1. Go to [Ping Identity Download Center (Backstage)](https://backstage.pingidentity.com/downloads)
2. Download **PingGateway 2025.11.1** → `PingGateway-2025.11.1.zip`
3. Place it in `binaries/pinggateway/artifacts/`

#### Step 2: Extract

```bash
cd binaries/pinggateway/dist
unzip ../artifacts/PingGateway-2025.11.1.zip
# Creates: identity-gateway-2025.11.1/
```

#### Step 3: Build the Docker image

```bash
cd identity-gateway-2025.11.1
docker build . -f docker/Dockerfile -t pinggateway:2025.11.1
```

**Expected output** (abbreviated):

```
Step 1/7 : FROM gcr.io/forgerock-io/java-21:latest
 → Pulls ~183 MB base image (public GCR, no auth required)
Step 3/7 : COPY --chown=forgerock:root . "${INSTALL_DIR}"
 → Copies ~72 MB of gateway binaries
Successfully tagged pinggateway:2025.11.1
```

#### Step 4: Verify

```bash
# Check image exists
docker image list | grep pinggateway

# Quick smoke test
docker run --rm -d --name pg-test -p 18080:8080 pinggateway:2025.11.1
sleep 5
curl -s -o /dev/null -w "%{http_code}" http://localhost:18080
# Expected: 200
docker stop pg-test
```

#### Step 5: Tag for convenience (optional)

```bash
docker tag pinggateway:2025.11.1 pinggateway:latest
```

### Key facts for rebuild

| Item | Value |
|------|-------|
| Distribution ZIP | `PingGateway-2025.11.1.zip` |
| Source | [Backstage](https://backstage.pingidentity.com/downloads) |
| Extracted to | `binaries/pinggateway/dist/identity-gateway-2025.11.1/` |
| Dockerfile path | `docker/Dockerfile` (relative to extracted dir) |
| Base image | `gcr.io/forgerock-io/java-21:latest` (public, ~183 MB) |
| Final image | `pinggateway:2025.11.1` (~327 MB) |
| Gateway port | `8080` |
| Admin port | `8085` |
| Process user | `forgerock` (uid `11111`) |
| Binaries path | `/opt/ig/` |
| Instance path | `/var/ig/` (= `$IG_INSTANCE_DIR`) |
