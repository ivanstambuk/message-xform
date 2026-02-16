# PingDS (Directory Server) Operations Guide

> Practitioner's guide for building, running, and configuring PingDS Docker images.

| Field        | Value                                                     |
|--------------|-----------------------------------------------------------|
| Status       | Living document                                           |
| Last updated | 2026-02-16                                                |
| Audience     | Developers, operators, CI/CD pipeline authors             |

---

## Topic Index

| # | Section | One-liner |
|---|---------|-----------|
| | **Part I — Docker Image** | |
| 1 | [Docker Image Build](#1-docker-image-build) | Building the evaluation Docker image from the distribution ZIP |
| 2 | [Running PingDS](#2-running-pingds) | Container startup, demo mode, secrets |
| 3 | [Filesystem Layout](#3-filesystem-layout) | Key paths inside the container |
| 4 | [Network Ports](#4-network-ports) | Exposed protocols and ports |
| 5 | [Environment Variables](#5-environment-variables) | Runtime configuration |
| | **Part II — Rebuild Playbook** | |
| 6 | [Full Rebuild from Scratch](#6-full-rebuild-from-scratch) | Step-by-step for a clean machine |

---

## Part I — Docker Image

### 1. Docker Image Build

PingDS ships a Dockerfile and setup script inside the distribution ZIP at
`samples/docker/`. Unlike PingGateway (which just copies binaries), PingDS
requires a **setup phase** during `docker build` that initializes the directory
server, configures logging for containers, and prepares the keystore.

#### Prerequisites

- Docker daemon running (`docker info` to verify)
- PingDS distribution extracted at:
  `binaries/pingds/extracted/ds-8.0.2/opendj/`
- The distribution ZIP (`DS-8.0.2.zip`) is downloaded from
  [Ping Identity Download Center (Backstage)](https://backstage.pingidentity.com/downloads)

#### Build command

```bash
# From the extracted opendj directory
cd binaries/pingds/extracted/ds-8.0.2/opendj
docker build . -f samples/docker/Dockerfile --build-arg runSetup=true -t pingds:8.0.2
```

**Critical:** The `--build-arg runSetup=true` flag is required. Without it, the
image will not have an initialized server and will fail to start.

#### What happens during build

1. **Base image**: `gcr.io/forgerock-io/java-21:latest` (same as PingGateway — public, no auth)
2. **Copies** the entire `opendj/` directory to `/opt/opendj/`
3. **Installs** `bash-completion` and `unzip` via apt
4. **Runs `setup.sh`** which:
   - Configures the DS server with LDAP (1389), LDAPS (1636), HTTP (8080),
     HTTPS (8443), Admin (4444), and Replication (8989) ports
   - Switches all logging to console (JSON format) for Docker compatibility
   - Removes file-based loggers (not useful in containers)
   - Deletes GSSAPI handler (not used in containers)
   - Configures replication bootstrap
   - Strips SSL keys from the build-time keystore (runtime secrets expected)
   - Removes default admin/monitor passwords (set at runtime)
5. **Copies** `docker-entrypoint.sh` to `/opt/opendj/` (via `cp -r samples/docker/* .`)
6. **Final image size**: ~243 MB

#### Verify

```bash
docker image list | grep pingds
# Expected: pingds  8.0.2  <id>  243MB
```

### 2. Running PingDS

PingDS requires TLS secrets at runtime. The simplest way to get started is
**demo mode**, which uses built-in demo certificates (NOT for production).

#### Demo mode (easiest — for development)

```bash
docker run --rm -d --name pingds \
  -e USE_DEMO_KEYSTORE_AND_PASSWORDS=true \
  -p 1389:1389 \
  -p 8080:8080 \
  pingds:8.0.2 start-ds
```

This sets `uid=admin` and `uid=monitor` passwords to `password` and uses a
built-in demo keystore. The WARNING in logs is expected:
```
WARNING: The container will use the demo keystore, YOUR DATA IS AT RISK
```

#### Production mode (with real secrets)

```bash
docker run --rm -d --name pingds \
  -e DS_SET_UID_ADMIN_AND_MONITOR_PASSWORDS=true \
  -e DS_UID_ADMIN_PASSWORD=<admin-password> \
  -e DS_UID_MONITOR_PASSWORD=<monitor-password> \
  --mount type=bind,src=/path/to/secrets,dst=/opt/opendj/secrets \
  -p 1389:1389 \
  -p 8080:8080 \
  pingds:8.0.2 start-ds
```

The `/path/to/secrets` directory must contain:
- **`keystore`**: PKCS#12 keystore with three entries:
  - `ca-cert` — CA public key
  - `ssl-key-pair` — SSL key pair signed by the CA
  - `master-key` — RSA master key pair for symmetric key protection
- **`keystore.pin`**: Plain-text file containing the keystore password

#### Entrypoint commands

| Command | Purpose |
|---------|---------|
| `start-ds` | Initialize (if needed) and start the server |
| `initialize-only` | Initialize only, then exit (for K8s init containers) |
| `dev` | Developer mode — no init, no start, just wait (for building custom images) |
| `help` | Display help text |
| `<any command>` | Run an arbitrary command (minimal init, e.g., `ldapsearch`) |

#### Stop

```bash
docker stop pingds
```

### 3. Filesystem Layout

| Path | Purpose |
|------|---------|
| `/opt/opendj/` | Server binaries and tools (WORKDIR) |
| `/opt/opendj/bin/` | CLI tools (dsconfig, ldapsearch, etc.) |
| `/opt/opendj/config/` | Configuration including schema |
| `/opt/opendj/data/` | Runtime persisted data (databases, changelog) |
| `/opt/opendj/secrets/` | Keystore and PIN (mount at runtime) |
| `/opt/opendj/docker-entrypoint.sh` | Container entrypoint script |
| `/opt/opendj/samples/` | Docker samples, demo secrets |

#### Volume mounts

| Mount point | When to mount |
|-------------|---------------|
| `/opt/opendj/data` | **Production**: persistent volume for database files |
| `/opt/opendj/secrets` | **Always**: TLS keystore and PIN (unless demo mode) |
| `/opt/opendj/config` | Optional: custom schema or configuration |

### 4. Network Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| `4444` | LDAPS | Administration connector |
| `1389` | LDAP | LDAP (supports StartTLS) |
| `1636` | LDAPS | LDAP over TLS |
| `8080` | HTTP | REST API |
| `8443` | HTTPS | REST API over TLS |
| `8989` | TLS | Replication |

All TLS ports support TLSv1.3 and use the SSL key-pair from the PKCS#12
keystore in `/opt/opendj/secrets/`.

### 5. Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `USE_DEMO_KEYSTORE_AND_PASSWORDS` | `false` | Use built-in demo keystore + password `password` |
| `DS_SET_UID_ADMIN_AND_MONITOR_PASSWORDS` | `false` | Enable admin/monitor password setting |
| `DS_UID_ADMIN_PASSWORD` | (unset) | Plain-text admin password |
| `DS_UID_MONITOR_PASSWORD` | (unset) | Plain-text monitor password |
| `DS_UID_ADMIN_PASSWORD_FILE` | `secrets/admin.pwd` | File containing admin password |
| `DS_UID_MONITOR_PASSWORD_FILE` | `secrets/monitor.pwd` | File containing monitor password |
| `DS_USE_PRE_ENCODED_PASSWORDS` | `false` | Passwords are pre-encoded (not plain-text) |
| `DS_SERVER_ID` | `$HOSTNAME` | Unique server identifier |
| `DS_GROUP_ID` | `default` | Data center / region identifier |
| `DS_ADVERTISED_LISTEN_ADDRESS` | FQDN | Address clients use to connect |
| `DS_BOOTSTRAP_REPLICATION_SERVERS` | auto-derived | Replication bootstrap addresses |
| `DS_USE_VIRTUAL_THREADS` | `false` | Enable Java virtual threads (experimental) |
| `OPENDJ_JAVA_ARGS` | (G1GC + defaults) | JVM arguments override |

---

## Part II — Rebuild Playbook

### 6. Full Rebuild from Scratch

Use this section when setting up PingDS Docker on a **new machine** from zero.

#### Step 1: Download the distribution

1. Go to [Ping Identity Download Center (Backstage)](https://backstage.pingidentity.com/downloads)
2. Download **PingDS 8.0.2** → `DS-8.0.2.zip`
3. Place it in `binaries/pingds/artifacts/`

#### Step 2: Extract

```bash
cd binaries/pingds/extracted
unzip ../artifacts/DS-8.0.2.zip
# Creates: ds-8.0.2/opendj/
```

#### Step 3: Build the Docker image

```bash
cd ds-8.0.2/opendj
docker build . -f samples/docker/Dockerfile --build-arg runSetup=true -t pingds:8.0.2
```

**Expected output** (abbreviated):

```
Step 1/19 : FROM gcr.io/forgerock-io/java-21:latest
 → Uses cached base image (~183 MB)
Step 12/19 : RUN if [ "${runSetup}" = "true" ]; then ./samples/docker/setup.sh; fi
 → Runs DS setup (initializes server, configures logging, prepares keystore)
 → "The Directory Server has started successfully" (setup only, server is stopped)
Successfully tagged pingds:8.0.2
```

Build takes ~30–60 seconds (setup phase is CPU-bound: key generation + DB init).

#### Step 4: Verify

```bash
# Check image exists
docker image list | grep pingds

# Quick smoke test (demo mode)
docker run --rm -d --name ds-test \
  -e USE_DEMO_KEYSTORE_AND_PASSWORDS=true \
  -p 11389:1389 \
  pingds:8.0.2 start-ds
sleep 8
docker logs ds-test 2>&1 | grep "started successfully"
# Expected: "The Directory Server has started successfully"
docker stop ds-test
```

#### Step 5: Tag for convenience (optional)

```bash
docker tag pingds:8.0.2 pingds:latest
```

### Key facts for rebuild

| Item | Value |
|------|-------|
| Distribution ZIP | `DS-8.0.2.zip` |
| Source | [Backstage](https://backstage.pingidentity.com/downloads) |
| Extracted to | `binaries/pingds/extracted/ds-8.0.2/opendj/` |
| Dockerfile path | `samples/docker/Dockerfile` (relative to `opendj/`) |
| **Build arg** | `--build-arg runSetup=true` (critical — without it, no server init) |
| Base image | `gcr.io/forgerock-io/java-21:latest` (public, ~183 MB) |
| Final image | `pingds:8.0.2` (~243 MB) |
| Process user | `forgerock` (uid `11111`) |
| Server root | `/opt/opendj/` |
| Demo mode | `-e USE_DEMO_KEYSTORE_AND_PASSWORDS=true` (passwords: `password`) |

### Key differences from PingGateway

| Aspect | PingGateway | PingDS |
|--------|-------------|--------|
| Dockerfile location | `docker/Dockerfile` | `samples/docker/Dockerfile` |
| Build context | Distribution root | `opendj/` subdirectory |
| Setup during build | None (just copies binaries) | `--build-arg runSetup=true` required |
| Runtime secrets | None (optional TLS) | Keystore **required** (or demo mode) |
| Image size | ~327 MB | ~243 MB |
| Entrypoint | Shell script (`start.sh`) | Multi-command entrypoint (`docker-entrypoint.sh`) |
| Default ports | 8080 (gateway), 8085 (admin) | 1389, 1636, 4444, 8080, 8443, 8989 |
