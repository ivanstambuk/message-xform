# Platform Deployment Guide

> End-to-end walkthrough for deploying the PingAccess + PingAM + PingDirectory
> platform. From zero to a working authentication login in a single guide.

| Field        | Value                                                     |
|--------------|-----------------------------------------------------------|
| Status       | Living document                                           |
| Last updated | 2026-02-17                                                |
| Audience     | Developers, operators, CI/CD pipeline authors             |

---

## Topic Index

| # | Section | One-liner |
|---|---------|-----------|
| | **Part I — Architecture & Prerequisites** | |
| 1 | [Architecture Overview](#1-architecture-overview) | 3-container deployment and product roles |
| 2 | [Prerequisites](#2-prerequisites) | Software, images, licenses, and credentials |
| | **Part II — PingDirectory Setup** | |
| 3 | [Start PingDirectory](#3-start-pingdirectory) | Container startup and health check |
| 4 | [Compatibility Tweaks](#4-compatibility-tweaks) | Two required changes for PingAM compat |
| | **Part III — PingAM Setup** | |
| 5 | [Start PingAM](#5-start-pingam) | Container startup and TLS trust |
| 6 | [Configure PingAM](#6-configure-pingam) | Initial configuration via REST API |
| 7 | [Verify PingAM](#7-verify-pingam) | Admin login and health checks |
| | **Part IV — PingAccess Setup** | |
| 8 | [Start PingAccess](#8-start-pingaccess) | Container startup, license, and password hooks |
| 9 | [Configure PingAccess](#9-configure-pingaccess-reverse-proxy-for-pingam) | Reverse proxy for PingAM via Admin API |
| | **Part IV-B — WebAuthn / Passkey Journeys** | |
| 9b | [WebAuthn Journey](#9b-webauthn-journey) | Import and configure passkey authentication |
| | **Part IV-C — Message-xform Plugin** | |
| 9c | [Message-xform Plugin Wiring](#9c-message-xform-plugin-wiring) | Transform specs, profiles, and rule binding |
| | **Part V — Operations** | |
| 10 | [Log Monitoring](#10-log-monitoring) | Real-time log tailing recipes |
| 11 | [Troubleshooting](#11-troubleshooting) | Error→fix lookup table |
| 12 | [Teardown & Reset](#12-teardown--reset) | Clean restart procedures |
| | **Part VI — E2E Testing** | |
| 13 | [E2E Test Suite](#13-e2e-test-suite) | Karate tests for all authentication flows |

---

## Part I — Architecture & Prerequisites

### 1. Architecture Overview

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

**Why 3 containers, not 4?** Ping Identity documentation suggests PingAM needs
PingDS (ForgeRock DS) for its config and CTS stores. Live testing (Feb 2026)
proved PingDirectory 11.0 works as a drop-in replacement with two tweaks.
This eliminates PingDS entirely. See [PingAM Operations Guide §6](./pingam-operations-guide.md#6-using-pingdirectory-as-am-backend)
for the full technical deep-dive.

| Product | Image | Version | Role |
|---------|-------|---------|------|
| PingDirectory | `pingidentity/pingdirectory` | 11.0 | Config + CTS + policy + user store |
| PingAM | `pingam:8.0.2` (custom) | 8.0.2 | Authentication, journeys, OAuth2 |
| PingAccess | `pingidentity/pingaccess` | 9.0 | Reverse proxy, message-xform plugin |

### 2. Prerequisites

#### Software

| Requirement | Minimum | Verify |
|-------------|---------|--------|
| Docker Engine | 24.0+ | `docker info` |
| Docker Compose | v2 (built-in) | `docker compose version` |
| curl | any | `curl --version` |
| openssl | any | `openssl version` |
| keytool | JDK bundled | `keytool -help` (optional — only for manual cert work) |

#### Docker Images

```bash
# PingDirectory — pull from Docker Hub
docker pull pingidentity/pingdirectory:11.0.0.1-latest

# PingAM — build custom image (no Docker Hub image available)
cd binaries/pingam
docker build . -f docker/Dockerfile -t pingam:8.0.2
# See: docs/operations/pingam-operations-guide.md §1

# PingAccess — pull from Docker Hub
docker pull pingidentity/pingaccess:latest
```

#### License & Credentials

| Item | Source | Notes |
|------|--------|-------|
| PingDirectory license | [Ping Identity DevOps](https://devops.pingidentity.com) | Required for production; dev image has evaluation period |
| PingAccess license | [Backstage](https://backstage.pingidentity.com/downloads) | `PingAccess-9.0-Development.lic` — repo: `binaries/pingaccess/license/` |
| PingAM WAR | [Backstage](https://backstage.pingidentity.com/downloads) | `AM-8.0.2.war` — 193 MB |
| DevOps credentials | [Registration](https://devops.pingidentity.com/how-to/devopsRegistration/) | For Docker Hub pulls |

---

## Part II — PingDirectory Setup

### 3. Start PingDirectory

#### Using Docker Compose (recommended)

```bash
cd deployments/platform

# Generate TLS keypair and .env
./scripts/generate-keys.sh

# Start PingDirectory only
docker compose up -d pingdirectory

# Wait for healthy (takes 60–90 seconds on first start)
docker compose logs -f pingdirectory
# Look for: "Setting up server..." → "Server setup complete"
```

> **Startup time:** PingDirectory takes 60–90 seconds on first start
> (schema loading, index generation, MAKELDIF user creation). Subsequent
> starts are 10–15 seconds.
>
> **Minimum heap:** PD 11.0 requires `MAX_HEAP_SIZE=768m` (minimum). The
> default `512m` in many examples causes setup failure:
> ```
> The memory size '512m' is less than the minimum required size of '768m'
> ```

#### Manual start (for testing)

```bash
docker network create platform-test

docker run -d \
  --name test-pd \
  --network platform-test \
  --hostname pd.test.local \
  -e PING_IDENTITY_ACCEPT_EULA=YES \
  -e ROOT_USER_DN="cn=administrator" \
  -e ROOT_USER_PASSWORD=2FederateM0re \
  -e USER_BASE_DN=dc=example,dc=com \
  -e MAKELDIF_USERS=10 \
  -e MAX_HEAP_SIZE=512m \
  -p 1636:1636 -p 1443:1443 \
  pingidentity/pingdirectory:11.0.0.1-latest
```

> **Default password:** The PingDirectory Docker image uses `2FederateM0re` as
> the root user password. Do not confuse with PingDS's default (`Password1`).
> The root DN is `cn=administrator` (not `uid=admin` as in PingDS).

#### MAKELDIF test users

`MAKELDIF_USERS=10` auto-generates sample users under the base DN:

| DN pattern | Password | Attributes |
|------------|----------|------------|
| `uid=user.0,ou=People,dc=example,dc=com` | `password` | `cn`, `sn`, `mail`, `telephoneNumber` |
| `uid=user.1,...` through `uid=user.9,...` | `password` | Same attributes, unique values |

These users are available for testing PingAM authentication once AM is configured
with PingDirectory as the user store.

#### PD binary paths

PingDirectory tools are available at **two** paths inside the container:

| Path | When to use |
|------|-------------|
| `/opt/out/instance/bin/` | Always works — symlink to the active instance |
| `/opt/server/bin/` | Also works — direct path to server installation |

Both point to the same binaries. This guide uses `/opt/out/instance/bin/`.

#### Verify PingDirectory is running

```bash
# LDAPS health check
docker exec <pd-container> /opt/out/instance/bin/ldapsearch \
  --hostname localhost --port 1636 \
  --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --baseDN "" --searchScope base "(objectClass=*)" vendorVersion

# Expected: vendorVersion: Ping Identity Directory Server 11.0.0.1
```

### 4. Compatibility Tweaks

> ⚠️ **These MUST be applied before configuring PingAM.** Without them, PingAM
> configuration will fail with schema violations or CTS errors.

#### Tweak 1: Schema Relaxation

PingAM writes LDAP entries without structural objectClasses. PingDirectory's
default is to reject these.

```bash
docker exec <pd-container> /opt/out/instance/bin/dsconfig \
  set-global-configuration-prop \
  --set single-structural-objectclass-behavior:accept \
  --hostname localhost --port 1636 \
  --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --noPropertiesFile --no-prompt
```

**Without this:** PingAM configurator fails with:
```
Object Class Violation: Entry 'ou=iPlanetAMAuthConfiguration,ou=services,dc=example,dc=com'
violates the Directory Server schema configuration because it does not include a
structural object class.
```

#### Tweak 2: ETag Virtual Attribute

PingAM's CTS uses `etag` for optimistic concurrency. PingDirectory doesn't have
this attribute natively.

**Step 2a — Add `etag` to schema:**

```bash
# Copy the schema LDIF into the container
docker cp deployments/platform/config/etag-schema.ldif <pd-container>:/tmp/

# Apply
docker exec <pd-container> /opt/out/instance/bin/ldapmodify \
  --hostname localhost --port 1636 \
  --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --filename /tmp/etag-schema.ldif
```

**Step 2b — Create mirror virtual attribute:**

```bash
docker exec <pd-container> /opt/out/instance/bin/dsconfig \
  create-virtual-attribute \
  --name "CTS ETag" \
  --type mirror \
  --set enabled:true \
  --set attribute-type:etag \
  --set source-attribute:ds-entry-checksum \
  --set base-dn:dc=example,dc=com \
  --hostname localhost --port 1636 \
  --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --noPropertiesFile --no-prompt
```

**Without this:** PingAM authentication appears to succeed (tree evaluates) but
session creation fails:
```
CTS: Unable to retrieve the etag from the token
```
The user gets no session cookie, and the admin login silently fails (empty response body).

#### Verify tweaks applied

```bash
# Check schema relaxation
docker exec <pd-container> /opt/out/instance/bin/dsconfig \
  get-global-configuration-prop \
  --property single-structural-objectclass-behavior \
  --hostname localhost --port 1636 \
  --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --noPropertiesFile --no-prompt
# Expected: accept

# Check etag virtual attribute
docker exec <pd-container> /opt/out/instance/bin/dsconfig \
  list-virtual-attributes \
  --hostname localhost --port 1636 \
  --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --noPropertiesFile --no-prompt | grep -i etag
# Expected: CTS ETag  : mirror  : true  : etag
```

> **Docker Compose shortcut:** When using `docker-compose.yml`, both tweaks are
> applied automatically:
> - **Schema**: `etag-schema.ldif` mounted at `pd.profile/server-root/pre-setup/config/schema/99-etag.ldif`
>   (loaded during PD setup, *before* dsconfig runs)
> - **dsconfig**: `pd-post-setup.dsconfig` mounted at `pd.profile/dsconfig/pd-post-setup.dsconfig`
>   (runs after PD setup completes)
>
> **Critical ordering:** The etag schema MUST be loaded as a pre-setup schema
> file, not applied via `ldapmodify` after PD starts. If loaded later, the
> dsconfig step that creates the mirror VA fails with:
> ```
> The string value 'etag' is not a valid attribute type ... there is no such
> attribute defined in the schema
> ```
> PD then starts and immediately shuts down when it encounters the broken VA config.
>
> **PD profile paths (mount targets):**
>
> | Content | Mount path inside container |
> |---------|-----------------------------|
> | dsconfig batch files | `/opt/staging/pd.profile/dsconfig/<name>.dsconfig` |
> | Pre-setup schema | `/opt/staging/pd.profile/server-root/pre-setup/config/schema/<nn>-<name>.ldif` |
> | License file | `/opt/staging/pd.profile/server-root/pre-setup/PingDirectory.lic` |
>
> **NOT** `/opt/staging/dsconfig/` — that path causes:
> ```
> CONTAINER FAILURE: Resolve the location of your dsconfig directory
> ```
>
> **PD schema file format:** Files in `config/schema/` must be native PD schema
> entries (not LDIF modify format). Use `objectClass: subschema` (not
> `subschemaSubentry`), no `LDIF:UNWRAP` header, and standard LDIF line wrapping
> (continuation lines start with a single space).

> **Kubernetes approach (dual strategy):** On K8s, PD is already running when
> schema tweaks are needed. Use a two-pronged strategy:
>
> 1. **Live apply** — Run `ldapmodify` (schema) and `dsconfig` (VA + relaxation)
>    directly via `kubectl exec`. The `ldapmodify` for schema must use
>    `changetype: modify` format (not the pre-setup file format).
> 2. **Profile mount for fresh deploys** — Create a `pd-profile` ConfigMap
>    containing `pd-post-setup.dsconfig` and `99-etag.ldif`, and mount them
>    at the standard staging paths in `values-local.yaml`.
>
> Both changes persist in PD's PVC data after first application. The profile
> mounts are only needed for a fresh PD instance (empty PVC).
>
> See [K8s Operations Guide §5a](./kubernetes-operations-guide.md#5a-pingam-standalone-deployment)
> for the full kubectl commands.

---

## Part III — PingAM Setup

### 5. Start PingAM

#### Using Docker Compose

```bash
docker compose up -d pingam
docker compose logs -f pingam
# Wait for: "Server startup in [XXXX] milliseconds"
```

> **docker-compose v1 bug:** The `pingam:8.0.2` image (built with newer Docker)
> causes a `KeyError: 'ContainerConfig'` crash in docker-compose v1 (1.29.x).
> **Fix:** Install Docker Compose v2 — see [§9c](#docker-compose-v2-requirement).

> **Port collision:** If port 8080 is already in use on the host, override in
> `.env`:
> ```bash
> AM_HTTP_PORT=18080
> AM_HTTPS_PORT=18443
> ```

#### Manual start

```bash
docker run -d \
  --name test-am \
  --network platform-test \
  --hostname am.test.local \
  -p 8080:8080 \
  pingam:8.0.2
```

> **Tomcat startup:** ~6–10 seconds. Look for `Server startup in [XXXX] milliseconds`
> in the container logs. AM is ready for configuration once this message appears.

#### Import PingDirectory's TLS Certificate

PingAM must trust PingDirectory's certificate for LDAPS connections. Without this,
the configurator POST hangs for 30+ seconds and then returns `Client-Side Timeout`.

```bash
# 1. Extract PD's certificate
# When running on Docker network, use the host-mapped port:
openssl s_client -connect localhost:1636 -showcerts </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > /tmp/pd-cert.pem

# Or from inside the Docker network (e.g., from another container):
# openssl s_client -connect pd.test.local:1636 ...

# 2. Copy into AM container
docker cp /tmp/pd-cert.pem <am-container>:/tmp/pd-cert.pem

# 3. Import as root (AM runs as uid 11111, but cacerts is root-owned)
docker exec -u 0 <am-container> keytool -importcert \
  -alias pingdirectory \
  -file /tmp/pd-cert.pem \
  -cacerts \
  -storepass changeit \
  -trustcacerts \
  -noprompt

# Expected output: "Certificate was added to keystore"
```

> **Gotcha — `-u 0` is required:** PingAM runs as user `forgerock` (uid 11111),
> but the JVM's `cacerts` file is owned by root. Without `-u 0`, keytool fails
> with `Permission denied`. This applies to the container's JVM truststore at
> `/usr/local/openjdk-21/lib/security/cacerts` (or equivalent for the JDK version).
>
> **Gotcha — cert import must happen before configuration:** If AM is configured
> without the cert, the LDAP connection fails and AM ends up in a broken state.
> You must nuke and restart the AM container.

#### Verify AM is ready for configuration

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/am
# Expected: 302 (redirect to setup wizard — unconfigured)
```

### 6. Configure PingAM

PingAM's initial configuration is done via a single POST to `/config/configurator`.
The content type is `application/x-www-form-urlencoded` (NOT JSON).

> **Important:** Add the AM hostname to `/etc/hosts` if running outside Docker
> Compose. PingAM validates the Host header against its configured `SERVER_URL`.
>
> ```bash
> echo "127.0.0.1 am.test.local pd.test.local" | sudo tee -a /etc/hosts
> ```
>
> **Idempotency:** The configurator runs only once. If AM is already configured,
> the POST returns a 302 redirect to the AM console. To reconfigure, you must
> delete AM's config directory (`/home/forgerock/openam`) or start a fresh container.

#### Automated configuration

The recommended approach is `scripts/configure-am.sh` which handles the complete
setup sequence: TLS cert import → configurator POST → progress monitoring →
admin auth verification. It is idempotent (detects already-configured AM).

```bash
cd deployments/platform
./scripts/configure-am.sh

# OR skip cert import (if PD cert already in AM truststore):
./scripts/configure-am.sh --skip-cert
```

#### Manual configuration command

```bash
AM_ENC_KEY=$(openssl rand -base64 24)  # MUST NOT be empty

curl -s -w "\n--- HTTP %{http_code} ---\n" \
  -X POST http://<am-host>:8080/am/config/configurator \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "SERVER_URL=http://<am-host>:8080" \
  --data-urlencode "DEPLOYMENT_URI=am" \
  --data-urlencode "BASE_DIR=/home/forgerock/openam" \
  --data-urlencode "locale=en_US" \
  --data-urlencode "PLATFORM_LOCALE=en_US" \
  --data-urlencode "AM_ENC_KEY=${AM_ENC_KEY}" \
  --data-urlencode "ADMIN_PWD=Password1" \
  --data-urlencode "ADMIN_CONFIRM_PWD=Password1" \
  --data-urlencode "AMLDAPUSERPASSWD=Password1" \
  --data-urlencode "COOKIE_DOMAIN=test.local" \
  --data-urlencode "acceptLicense=true" \
  --data-urlencode "DATA_STORE=dirServer" \
  --data-urlencode "DIRECTORY_SSL=SSL" \
  --data-urlencode "DIRECTORY_SERVER=<pd-hostname>" \
  --data-urlencode "DIRECTORY_PORT=1636" \
  --data-urlencode "DIRECTORY_ADMIN_PORT=1443" \
  --data-urlencode "DIRECTORY_JMX_PORT=1689" \
  --data-urlencode "ROOT_SUFFIX=dc=example,dc=com" \
  --data-urlencode "DS_DIRMGRDN=cn=administrator" \
  --data-urlencode "DS_DIRMGRPASSWD=2FederateM0re" \
  --data-urlencode "USERSTORE_TYPE=LDAPv3ForOpenDS" \
  --data-urlencode "USERSTORE_SSL=SSL" \
  --data-urlencode "USERSTORE_HOST=<pd-hostname>" \
  --data-urlencode "USERSTORE_PORT=1636" \
  --data-urlencode "USERSTORE_SUFFIX=dc=example,dc=com" \
  --data-urlencode "USERSTORE_MGRDN=cn=administrator" \
  --data-urlencode "USERSTORE_PASSWD=2FederateM0re"
```

#### Parameter reference

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `DATA_STORE` | `dirServer` | External directory server (not embedded) |
| `DIRECTORY_SSL` | `SSL` | LDAPS connection to PingDirectory |
| `DIRECTORY_SERVER` | hostname | PingDirectory's Docker hostname |
| `DIRECTORY_PORT` | `1636` | PingDirectory LDAPS port |
| `DIRECTORY_ADMIN_PORT` | `1443` | PingDirectory HTTPS admin port |
| `ROOT_SUFFIX` | `dc=example,dc=com` | PD's base DN — AM creates subtrees under this |
| `DS_DIRMGRDN` | `cn=administrator` | PD root user DN (not `uid=admin` like PingDS) |
| `USERSTORE_TYPE` | `LDAPv3ForOpenDS` | PD is OpenDS-compatible for user lookups |
| `AM_ENC_KEY` | random base64 | **Must not be empty.** Encryption key for AM secrets. Generate with `openssl rand -base64 24`. Configurator rejects empty values with `Encryption key must be provided.` |
| `COOKIE_DOMAIN` | domain suffix | Session cookie domain (`.test.local`, `.platform.local`) |

> **Key insight:** Both the config store (`DS_DIRMGRDN`) and user store
> (`USERSTORE_MGRDN`) point to the **same** PingDirectory instance. PingAM
> automatically creates its own subtrees (`ou=services`, `ou=tokens`, etc.)
> under the base DN during configuration.
>
> **Kubernetes gotcha (hostname verification):** PD's self-signed certificate
> has CN = `pingdirectory-0.pingdirectory-cluster.<ns>.svc.cluster.local`.
> AM's LDAP SDK performs SSL hostname verification, so `DIRECTORY_SERVER` and
> `USERSTORE_HOST` must use the **headless service FQDN**, not the short
> service name `pingdirectory`. Using the short name causes `Client-Side
> Timeout` — a misleading error that's actually an SSL hostname mismatch.
> See [PingAM Operations Guide §13k](./pingam-operations-guide.md#13k-am-configurator-on-k8s)
> and [K8s Operations Guide §5a](./kubernetes-operations-guide.md#5a-pingam-standalone-deployment)
> for details.

#### What happens during configuration

The configurator performs ~98 LDAP operations. With a local PD, this completes in
**15–30 seconds**. On slow networks or shared infrastructure, it can take 2–5 minutes:

1. **Schema loading** (~30 LDIF files) — AM-specific objectClasses and attributes
   for config, CTS, UMA, OAuth2, SAML, etc. Key files loaded include:
   - `odsee_config_schema.ldif` / `odsee_config_index.ldif` — config store schema
   - `cts-container.ldif` / `cts-add-schema.ldif` — CTS token schema
   - `cts-add-multivalue.ldif` / `cts-add-ttlexpire.ldif` — CTS indexes
   - `opendj_uma_audit.ldif` — UMA audit schema

2. **Config tree creation** — `ou=services,dc=example,dc=com` with all AM service
   configurations (auth modules, realms, OAuth2 providers, etc.)

3. **CTS tree creation** — Token storage with this DIT structure:
   ```
   ou=tokens,dc=example,dc=com
    └─ ou=openam-session
        └─ ou=famrecords      ← CTS token entries (coreTokenId=...)
   ```

4. **Admin user creation** — `amAdmin` in the AM internal user store

5. **Boot file generation** — `/home/forgerock/openam/config/boot.json`:
   ```json
   {
     "instance": "http://am.test.local:8080/am",
     "dsameUser": "cn=dsameuser,ou=DSAME Users,dc=example,dc=com",
     "configStoreList": [{
       "baseDN": "dc=example,dc=com",
       "dirManagerDN": "cn=administrator",
       "ldapHost": "pd.test.local",
       "ldapPort": 1636,
       "ldapProtocol": "ldaps"
     }]
   }
   ```

**Timing:** With a local PD, the configurator typically takes 15–30 seconds
(~98 `Success.` steps). On slow infrastructure, it can take up to 5 minutes.
Monitor progress via the install log:

```bash
# Watch live progress
docker exec <am-container> tail -f /home/forgerock/openam/var/install.log

# Count successful steps
docker exec <am-container> grep -c "Success\." /home/forgerock/openam/var/install.log

# Check for failures (should be zero)
docker exec <am-container> grep -i "fail\|error" /home/forgerock/openam/var/install.log
```

### 7. Verify PingAM

After configuration, AM restarts automatically. The **first HTTP request** after
configuration triggers lazy initialization of all AM subsystems (CTS connections,
LDAP pools, crypto key generation). This can take 30–60 seconds — expect the
first call to hang or time out. Subsequent requests are fast.

#### Admin authentication (callback-based)

Authentication uses a 2-step callback pattern (ZeroPageLogin is disabled for
security — see [PingAM §8](./pingam-operations-guide.md#8-rest-authentication--callback-pattern)):

```bash
# Step 1: Get authId and callbacks
STEP1=$(curl -sf -X POST "http://<am-host>:8080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0")
AUTH_ID=$(echo "$STEP1" | python3 -c "import sys,json; print(json.load(sys.stdin)['authId'])")

# Step 2: Fill callbacks with credentials
curl -sf -X POST "http://<am-host>:8080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0" \
  -d "{\"authId\":\"${AUTH_ID}\",\"callbacks\":[
    {\"type\":\"NameCallback\",\"input\":[{\"name\":\"IDToken1\",\"value\":\"amAdmin\"}],
     \"output\":[{\"name\":\"prompt\",\"value\":\"User Name\"}],\"_id\":0},
    {\"type\":\"PasswordCallback\",\"input\":[{\"name\":\"IDToken2\",\"value\":\"Password1\"}],
     \"output\":[{\"name\":\"prompt\",\"value\":\"Password\"}],\"_id\":1}]}"
```

**Expected response:**
```json
{
  "tokenId": "d8ImuQ_gP7dJ146v...",
  "successUrl": "/am/console",
  "realm": "/"
}
```

> **Gotcha — FQDN validation:** PingAM rejects requests where the Host header
> doesn't match the configured `SERVER_URL`. If authenticating from the host
> machine, use the AM hostname (not `localhost`):
> ```
> curl: {"code":400,"reason":"Bad Request","message":"FQDN \"localhost\" is not valid."}
> ```
> Fix: add hostname to `/etc/hosts` or use `--resolve`.

#### Verify CTS tokens in PingDirectory

```bash
docker exec <pd-container> /opt/out/instance/bin/ldapsearch \
  --hostname localhost --port 1636 \
  --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --baseDN "ou=tokens,dc=example,dc=com" \
  --searchScope sub "(objectClass=*)" dn
# Expected: multiple coreTokenId entries
```

#### Verify etag is populated

```bash
docker exec <pd-container> /opt/out/instance/bin/ldapsearch \
  --hostname localhost --port 1636 \
  --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --baseDN "ou=famrecords,ou=openam-session,ou=tokens,dc=example,dc=com" \
  --searchScope one "(objectClass=*)" etag coreTokenId --sizeLimit 1
# Expected: etag: <number>  (from ds-entry-checksum mirror)
```

#### Create test users and verify authentication

After AM is configured and verified, run `configure-am-post.sh` to set up
test users and verify callback-based authentication:

```bash
cd deployments/platform
./scripts/configure-am-post.sh
```

This script performs three steps:
1. Creates 10 test users (user.1–user.10) in PingDirectory under `ou=People`
2. Verifies admin authentication (callback-based)
3. Verifies user.1 can authenticate (callback-based)

> **Built-in vs custom journeys:** PingAM 8.0 ships with a `ldapService`
> authentication tree (ZeroPageLogin → Page Node → Data Store Decision). This
> is set as the default tree (`orgConfig: ldapService`) and handles standard
> username/password login out of the box — no custom journey import is needed
> for basic authentication.
>
> **ZeroPageLogin is disabled** (AM 8.0 default). All scripts use the
> vendor-recommended callback pattern (2-step `authId` flow). This avoids
> Login CSRF vulnerabilities and works with any journey complexity.
> See [PingAM §8](./pingam-operations-guide.md#8-rest-authentication--callback-pattern).

#### Verify user authentication (callback-based)

```bash
# Step 1: Get authId
AUTH_ID=$(curl -sf -X POST "http://<am-host>:8080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['authId'])")

# Step 2: Fill callbacks
curl -sf -X POST "http://<am-host>:8080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0" \
  -d "{\"authId\":\"${AUTH_ID}\",\"callbacks\":[
    {\"type\":\"NameCallback\",\"input\":[{\"name\":\"IDToken1\",\"value\":\"user.1\"}],
     \"output\":[{\"name\":\"prompt\",\"value\":\"User Name\"}],\"_id\":0},
    {\"type\":\"PasswordCallback\",\"input\":[{\"name\":\"IDToken2\",\"value\":\"Password1\"}],
     \"output\":[{\"name\":\"prompt\",\"value\":\"Password\"}],\"_id\":1}]}"
```

**Expected response:**
```json
{
  "tokenId": "U_eOfuJbtBlB3et1...*",
  "successUrl": "/am/console",
  "realm": "/"
}
```

---

## Part IV — PingAccess Setup

### 8. Start PingAccess

```bash
docker compose up -d pingaccess
# Or via docker run (if compose can't adopt existing containers):
set -a && source .env && set +a
docker run -d --name platform-pingaccess \
  --hostname "${HOSTNAME_PA}" --network platform_platform \
  -p ${PA_ENGINE_PORT}:3000 -p ${PA_ADMIN_PORT}:9000 \
  -v ./secrets/tlskey.p12:/opt/in/instance/conf/tlskey.p12:ro \
  -v ./secrets/pubCert.crt:/opt/in/instance/conf/pubCert.crt:ro \
  -v ../../binaries/pingaccess/license/PingAccess-9.0-Development.lic:/opt/out/instance/conf/pingaccess.lic:ro \
  -e PING_IDENTITY_ACCEPT_EULA=YES \
  -e PA_ADMIN_PASSWORD_INITIAL=2Access \
  -e PING_IDENTITY_PASSWORD=2Access \
  pingidentity/pingaccess:latest
```

> **License required:** PA will fail with `CONTAINER FAILURE: License File absent`
> unless a `.lic` file is mounted at `/opt/out/instance/conf/pingaccess.lic`.
> The development license lives at `binaries/pingaccess/license/PingAccess-9.0-Development.lic`.
> The docker-compose.yml already mounts this.

> **Password hooks:** PA has a two-phase password system. Set both
> `PA_ADMIN_PASSWORD_INITIAL` and `PING_IDENTITY_PASSWORD` to the same value
> (default: `2Access`). If they differ, the `83-change-password.sh` hook will
> change your password after boot. See [PingAccess Operations Guide §3](./pingaccess-operations-guide.md#3-authentication--password-hooks).

#### Readiness detection

PA writes `"PingAccess running"` to stdout when fully started (typically 20–30s):

```bash
# Wait for PA readiness
while ! docker logs platform-pingaccess 2>&1 | grep -q "PingAccess running"; do sleep 5; done
echo "PA is ready"
```

#### Verify Admin API

```bash
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     https://localhost:${PA_ADMIN_PORT}/pa-admin-api/v3/version
# → {"version":"9.0.1.0"}
```

### 9. Configure PingAccess (reverse proxy for PingAM)

After PA is running, configure it to reverse-proxy PingAM:

```bash
./scripts/configure-pa.sh
```

This script performs 6 Admin API steps:
1. Verifies PA is ready (version check)
2. Uses the existing virtual host (`localhost:3000`, id=1)
3. Creates a **Site**: `PingAM Backend` → `am.platform.local:8080` (HTTP)
4. Creates an **Application**: `/am` context root, Web type
5. **Enables** the application (PA creates apps disabled by default — see [PA Ops §7](./pingaccess-operations-guide.md#7-application--resource-configuration-gotchas))
6. Configures the **Root Resource** as unprotected (wildcard `/*`, no auth)

The script is idempotent — re-running it skips objects that already exist.

#### Verify proxy

```bash
# Access PingAM through PingAccess (HTTPS engine port)
# Host header must match the PA virtual host (localhost:3000)
curl -sk -H 'Host: localhost:3000' \
     https://localhost:${PA_ENGINE_PORT}/am/
# → HTML redirect to AM login page
```

#### Verify authentication through the proxy (callback-based)

```bash
# Step 1: Get authId
AUTH_ID=$(curl -sf -k -H 'Host: localhost:3000' \
  -X POST "https://localhost:${PA_ENGINE_PORT}/am/json/authenticate" \
  -H 'Content-Type: application/json' \
  -H 'Accept-API-Version: resource=2.0,protocol=1.0' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['authId'])")

# Step 2: Fill callbacks
curl -sf -k -H 'Host: localhost:3000' \
  -X POST "https://localhost:${PA_ENGINE_PORT}/am/json/authenticate" \
  -H 'Content-Type: application/json' \
  -H 'Accept-API-Version: resource=2.0,protocol=1.0' \
  -d "{\"authId\":\"${AUTH_ID}\",\"callbacks\":[
    {\"type\":\"NameCallback\",\"input\":[{\"name\":\"IDToken1\",\"value\":\"user.1\"}],
     \"output\":[{\"name\":\"prompt\",\"value\":\"User Name\"}],\"_id\":0},
    {\"type\":\"PasswordCallback\",\"input\":[{\"name\":\"IDToken2\",\"value\":\"Password1\"}],
     \"output\":[{\"name\":\"prompt\",\"value\":\"Password\"}],\"_id\":1}]}"
# → {"tokenId":"...", "successUrl":"/am/console", "realm":"/"}
```

> **Host header is critical:** PA matches incoming requests by `Host:port`.
> If the Host header doesn't match a virtual host, PA returns **403 Forbidden**.
> See [PA Ops §6](./pingaccess-operations-guide.md#6-virtual-host-matching-critical).

> **Port mapping note:** The default PA engine port (3000) may conflict with
> other services (e.g., Tailscale, code-server). The `.env` maps PA to
> host ports `13000` (engine) and `19000` (admin). Inside the container,
> PA still listens on 3000/9000.

---

## Part IV-B — WebAuthn / Passkey Journeys

### 9b. WebAuthn Journey

The WebAuthn journey enables passwordless authentication using FIDO2 passkeys.
It is adapted from the webinar reference journey (`WebinarJourneyWebAuthN`)
with local-environment overrides.

#### Journey flow

```
Start → Username Collector
  → WebAuthn Authentication
      ├─ Device registered → ✓ Success
      └─ No device → Password Collector → DataStore Decision
                       → WebAuthn Registration → Recovery Codes
                           → loop back to WebAuthn Auth
```

#### Configuration

| Setting | Value |
|---------|-------|
| RP Domain | `localhost` |
| RP Name | `Platform Local` |
| Origin | `https://localhost:13000` |
| User Verification | `DISCOURAGED` |
| Signing Algorithms | ES256, RS256 |
| Attestation | `NONE` |

#### Import

```bash
# From deployments/platform/
./scripts/import-webauthn-journey.sh
```

The script uses PingAM's REST API to import individual nodes, then wires
them into the `WebAuthnJourney` authentication tree.

#### Manual verification

```bash
# Start the journey (returns NameCallback for username)
curl -s -H 'Host: am.platform.local:18080' \
  -X POST 'http://127.0.0.1:18080/am/json/authenticate?authIndexType=service&authIndexValue=WebAuthnJourney' \
  -H 'Content-Type: application/json' \
  -H 'Accept-API-Version: resource=2.0,protocol=1.0'
```

> **Host header is critical for AM API calls.** PingAM rejects requests
> whose `Host` header doesn't match the configured FQDN. Always use
> `-H 'Host: am.platform.local:18080'` with `http://127.0.0.1:18080`
> instead of `--resolve`. The `--resolve` flag alone does not set the
> Host header, leading to silent 400 errors.

> **`authIndexType=service` and trees.** In PingAM 8.0, this endpoint
> resolves authentication trees by name. The tree must be imported via
> the `/authenticationtrees/trees/{name}` API. If the error
> `"No Configuration found"` appears, the tree import likely failed
> silently — check that `curl` is not using `-f` (silent failure mode)
> and verify nodes were created before the tree.

> **Detailed reference:** For full REST API gotchas (Host header, curl flags,
> orgConfig trap), WebAuthn callback structure, and the tree import procedure,
> see [PingAM Ops §8b](./pingam-operations-guide.md#8b-rest-api-gotchas),
> [§10](./pingam-operations-guide.md#10-webauthn-journey), and
> [§10b](./pingam-operations-guide.md#10b-importing-trees-via-rest-api).

---

## Part IV-C — Message-xform Plugin

### 9c. Message-xform Plugin Wiring

The message-xform adapter transforms PingAM's raw authentication responses into
a cleaner API surface. It runs as a PingAccess Processing Rule, modifying
responses as they flow back from AM through PA to the client.

#### Why response-only?

PingAM's callback authentication protocol (see [PingAM §8](#8-rest-authentication--callback-pattern))
requires the `authId` JWT and callback structure to be echoed back **verbatim**.
Any request-side transformation would break this round-trip. The transform
engine therefore only operates in the **response direction**, cleaning up
existing fields rather than restructuring the protocol.

#### Architecture

```
                              ┌────────────────────────┐
  Client ────POST──▶ PA ──▶ AM ──▶ Auth Tree ────▶ PA ──▶ Transform ──▶ Client
            /authenticate           (callback)          │
                                                        ▼
                                                   Body rename:
                                                     tokenId → token
                                                     successUrl → redirectUrl
                                                   Field inject:
                                                     authenticated: true
                                                   Header inject:
                                                     X-Auth-Provider: PingAM
                                                     X-Transform-Engine: message-xform
```

#### File layout

```
deployments/platform/
├── specs/
│   ├── am-auth-url-rewrite.yaml       # Request: /api/v1/auth/login → AM (default tree)
│   ├── am-passkey-url.yaml            # Request: /api/v1/auth/passkey → AM + WebAuthnJourney
│   ├── am-passkey-usernameless-url.yaml # Request: /api/v1/auth/passkey/usernameless → AM + UsernamelessJourney
│   ├── am-auth-response-v2.yaml       # Response: login callback + success body transform
│   ├── am-webauthn-response.yaml      # Response: WebAuthn callback → structured passkey API
│   ├── am-strip-internal.yaml         # Response: remove _-prefixed internal fields
│   └── am-header-inject.yaml          # Response: header injection for all AM responses
├── profiles/
│   └── platform-am.yaml              # Routes specs to AM API paths (v4.0.0)
└── scripts/
    ├── configure-pa.sh               # Base PA config (site, /am app, resource)
    ├── configure-pa-api.sh           # Clean URL app (/api) + transform rule binding
    └── configure-pa-plugin.sh        # Plugin rule creation + policy binding
```

Inside the PingAccess container:

| Host path | Container path | Purpose |
|-----------|----------------|--------|
| `adapter-pingaccess/build/libs/adapter-pingaccess-0.1.0-SNAPSHOT.jar` | `/opt/server/deploy/message-xform-adapter.jar` | Shadow JAR (auto-discovered by PA classloader) |
| `specs/` | `/specs` | Transform spec YAML files |
| `profiles/` | `/profiles` | Profile files routing specs to paths |

#### Transform specs

##### Request-side: URL rewrite specs

Three request-side specs rewrite clean API URLs to PingAM's
`/am/json/authenticate` endpoint. All use JSLT identity transform (`.`) —
the request body passes through unchanged (D9: authId must be echoed verbatim).

| Spec | Clean URL | Target | Journey query params |
|------|-----------|--------|---------------------|
| `am-auth-url-rewrite` | `/api/v1/auth/login` | `/am/json/authenticate` | (none — default tree) |
| `am-passkey-url` | `/api/v1/auth/passkey` | `/am/json/authenticate` | `authIndexType=service&authIndexValue=WebAuthnJourney` |
| `am-passkey-usernameless-url` | `/api/v1/auth/passkey/usernameless` | `/am/json/authenticate` | `authIndexType=service&authIndexValue=UsernamelessJourney` |

All three inject:
- `Accept-API-Version: resource=2.0,protocol=1.0` (required by AM)

After the URL rewrite, PA forwards to AM at the rewritten path. The
response-side transforms then see the rewritten path (`/am/json/authenticate`)
in `request.getUri()` and match correctly.

##### Response-side: `am-auth-response-v2.yaml` — Login body transform

Transforms login callback and success responses from PingAM:

| AM raw field | Transformed field | Notes |
|-------------|-------------------|-------|
| `tokenId` | `token` | Cleaner field name (success responses) |
| `successUrl` | `redirectUrl` | More descriptive |
| (none) | `authenticated: true` | Client convenience boolean |
| `realm` | `realm` | Preserved as-is |
| `callbacks[]` | `fields[]` | Callback array renamed (callback responses) |
| `authId` | `_authId` (internal) | Extracted to `X-Auth-Session` header |

The spec handles both callback **and** success responses:
- Callback responses: produces `fields[]` array
- Success responses: renames `tokenId` → `token`, adds `authenticated: true`

##### `am-webauthn-response.yaml` — WebAuthn passkey transform

Parses embedded JavaScript from WebAuthn TextOutputCallbacks into structured
JSON using JSLT `capture()` regex:

**Registration output:**
```json
{
  "type": "webauthn-register",
  "challengeRaw": "121,84,25,-26,...",
  "rpId": "localhost",
  "userVerification": "required",
  "timeout": 60000
}
```

**Authentication output:**
```json
{
  "type": "webauthn-auth",
  "challengeRaw": "69,84,25,-26,...",
  "rpId": "localhost",
  "userVerification": "required",
  "timeout": 60000,
  "hasRecoveryOption": true
}
```

`challengeRaw` is a comma-separated string of signed bytes matching AM's
`Int8Array([...])` format. The client converts to `ArrayBuffer`:
```js
new Int8Array(challengeRaw.split(",").map(Number)).buffer
```

The `authId` is extracted to the `X-Auth-Session` response header.

##### `am-strip-internal.yaml` — Internal field removal

Removes all `_`-prefixed fields (e.g., `_authId`) from the response body.
Runs after the body transform step in the profile chain (ADR-0008).

##### `am-header-inject.yaml` — Header injection

Adds custom headers to all PingAM API responses (`/am/json/**`):

| Header | Value | Purpose |
|--------|-------|--------|
| `X-Auth-Provider` | `PingAM` | Identifies the authentication provider |
| `X-Transform-Engine` | `message-xform` | Identifies the transform engine |

> **Header case:** PA normalizes response headers to lowercase.
> Clients should check for `x-auth-provider` (not `X-Auth-Provider`).

#### Profile routing

The `platform-am.yaml` profile (v4.0.0) handles both request-side URL rewriting
and response-side body transforms:

**Request-side (URL rewriting):**

| Spec | Method | Path (incoming) | Direction | Query params injected |
|------|--------|-----------------|-----------|----------------------|
| `am-passkey-usernameless-url` | `POST` | `/api/v1/auth/passkey/usernameless` | Request | `authIndexType=service&authIndexValue=UsernamelessJourney` |
| `am-passkey-url` | `POST` | `/api/v1/auth/passkey` | Request | `authIndexType=service&authIndexValue=WebAuthnJourney` |
| `am-auth-url-rewrite` | `POST` | `/api/v1/auth/login` | Request | (none — default tree) |

> **Path ordering:** Most specific path first (usernameless before passkey).

**Response-side (body transforms):**

| Spec | Method | Path (rewritten) | Direction | Body predicate |
|------|--------|-------------------|-----------|----------------|
| `am-webauthn-response` | `POST` | `/am/json/authenticate` | Response | TextOutputCallback contains `navigator.credentials` |
| `am-auth-response-v2` | `POST` | `/am/json/authenticate` | Response | Everything else (login callbacks + success) |
| `am-strip-internal` | `POST` | `/am/json/authenticate` | Response | (always runs — chains after body transform) |
| `am-header-inject` | `POST` | `/am/json/**` | Response | (no predicate) |

**PA Application architecture:**

Two PA Applications share the same PingAM backend site:

| Application | Context root | Purpose | Created by |
|-------------|--------------|---------|------------|
| PingAM Proxy | `/am` | Direct AM access (legacy, E2E tests) | `configure-pa.sh` |
| Auth API | `/api` | Clean URL surface for API consumers | `configure-pa-api.sh` |

Both have the MessageTransformRule attached. The profile handles routing
based on the request path.

#### Setup procedure

**Step 1: Build the shadow JAR** (if not already built):
```bash
cd /path/to/message-xform
./gradlew :adapter-pingaccess:shadowJar
# → adapter-pingaccess/build/libs/adapter-pingaccess-0.1.0-SNAPSHOT.jar (~4.7 MB)
```

**Step 2: Restart PingAccess** with the new volume mounts:
```bash
cd deployments/platform
docker compose up -d pingaccess  # recreates container with JAR + specs + profiles
```

The `docker-compose.yml` already includes the volume mounts:
```yaml
# message-xform plugin (Phase 7)
- ../../adapter-pingaccess/build/libs/adapter-pingaccess-0.1.0-SNAPSHOT.jar:/opt/server/deploy/message-xform-adapter.jar:ro
- ./specs:/specs:ro
- ./profiles:/profiles:ro
```

**Step 3: Configure the base PA proxy** (if not already done):
```bash
./scripts/configure-pa.sh
```

**Step 4: Wire the plugin:**
```bash
./scripts/configure-pa-plugin.sh
```

This script:
1. Verifies the `MessageTransformRule` descriptor is available in PA (JAR auto-discovered)
2. Creates a Processing Rule with the spec/profile configuration
3. Finds the existing PingAM Proxy application and its root resource
4. Attaches the rule to the root resource's **Web** policy bucket
5. Verifies transform is active via PA engine logs

> **Idempotent:** The script detects existing rules and resources. Re-running
> it safely skips already-created objects.

#### Verification

```bash
# Test 1: Callback response passes through unchanged
curl -sk -H 'Host: localhost:3000' \
  -X POST 'https://localhost:13000/am/json/authenticate' \
  -H 'Content-Type: application/json' \
  -H 'Accept-API-Version: resource=2.0,protocol=1.0'
# → { "authId": "...", "callbacks": [...] }  (unchanged — JSLT guard)

# Test 2: Auth success is transformed
# (fill callbacks with user.1 / Password1 and submit)
# → { "token": "...", "authenticated": true, "redirectUrl": "/am/console", "realm": "/" }

# Test 3: Headers injected
curl -sk -H 'Host: localhost:3000' \
  -X POST 'https://localhost:13000/am/json/authenticate' \
  -H 'Content-Type: application/json' \
  -H 'Accept-API-Version: resource=2.0,protocol=1.0' \
  -o /dev/null -v 2>&1 | grep -i 'x-auth-provider\|x-transform-engine'
# → x-auth-provider: PingAM
# → x-transform-engine: message-xform

# Test 4: Hot-reload active (30s)
docker logs platform-pingaccess 2>&1 | grep 'Registry reloaded'
# → Registry reloaded: specs=4, profile=platform-am  (every 30s)
```

#### Docker Compose v2 requirement

docker-compose v1 (1.29.x, the Ubuntu package `docker-compose`) has a
`KeyError: 'ContainerConfig'` bug with newer Docker images, including
PingDirectory 11.0. This causes `docker-compose up` to crash when
recreating containers.

**Fix:** Install Docker Compose v2:
```bash
sudo apt-get install -y docker-compose-v2
# Verify: docker compose version → 2.37.1 or later
```

After installation, use `docker compose` (space, not hyphen) for all commands.
The `docker-compose.yml` file format is compatible with both versions — only
the CLI binary changed.

> **Affected images (confirmed):** `pingidentity/pingdirectory:11.0.0.1-latest`.
> The `pingam:8.0.2` custom build and `pingidentity/pingaccess:latest` also
> trigger this when docker-compose v1 tries to recreate them.

---

## Part V — Operations

### 10. Log Monitoring

#### Quick reference: log locations

| Product | Log | Path / Command |
|---------|-----|---------------|
| PingDirectory | Access log | `docker exec <pd> tail -f /opt/out/instance/logs/access` |
| PingDirectory | Error log | `docker exec <pd> tail -f /opt/out/instance/logs/errors` |
| PingAM | Install log | `docker exec <am> cat /home/forgerock/openam/var/install.log` |
| PingAM | Debug (CTS) | `docker exec <am> tail -f /home/forgerock/openam/var/debug/CoreSystem` |
| PingAM | Debug (Auth) | `docker exec <am> tail -f /home/forgerock/openam/var/debug/Authentication` |
| PingAM | Debug (IdRepo) | `docker exec <am> tail -f /home/forgerock/openam/var/debug/IdRepo` |
| PingAM | Stdout | `docker logs --tail 30 <am>` |
| PingAccess | Engine log | Standard Docker logs: `docker logs --tail 30 <pa>` |

#### PingDirectory access log patterns

The PD access log is the **single most useful diagnostic tool** for debugging
AM↔PD issues. Every LDAP operation from AM appears here with timing and result.

```
# Successful operation
SEARCH RESULT ... base="ou=services,dc=example,dc=com" ... resultCode=0

# CTS token search (session management)
SEARCH RESULT ... base="ou=famrecords,ou=openam-session,ou=tokens,dc=example,dc=com" ...

# Heartbeat (AM keepalive — every few seconds)
SEARCH RESULT ... base="" scope=0 filter="(objectClass=*)" attrs="1.1" resultCode=0
```

#### PingAM debug file reference

| File | When to check |
|------|---------------|
| `CoreSystem` | CTS errors, LDAP connection failures, session persistence issues |
| `Authentication` | Login failures, auth tree execution errors |
| `IdRepo` | User lookup failures, identity store connectivity |
| `OAuth2Provider` | Token issuance, OIDC flow errors |
| `Configuration` | Config store read/write failures |
| `Federation` | SAML/federation errors |

### 11. Troubleshooting

#### Error → Fix Lookup Table

| Error Message | Cause | Fix |
|---------------|-------|-----|
| `Object Class Violation: ... does not include a structural object class` | PD schema is too strict for AM entries | Apply Tweak 1: `single-structural-objectclass-behavior:accept` |
| `CTS: Unable to retrieve the etag from the token` | PD lacks `etag` virtual attribute | Apply Tweak 2: add `etag` schema + mirror VA |
| `Client-Side Timeout` during AM config | AM can't connect to PD via LDAPS | Import PD cert into AM's JVM truststore (see §5) |
| `Permission denied` on `keytool -importcert` | Running as `forgerock` (uid 11111) | Use `docker exec -u 0` to run as root |
| `FQDN "localhost" is not valid` | Host header doesn't match AM's `SERVER_URL` | Add hostname to `/etc/hosts` or use `--resolve` |
| Empty response body from `/authenticate` | AM lazy-initializing after config/restart | Wait 30–60 seconds, retry. Check `CoreSystem` debug log |
| `NullPointerException` with `DATA_STORE=embedded` | Embedded DS not supported in AM 8.0 Docker | Use `DATA_STORE=dirServer` with external PingDirectory |
| `Realm not found` on `/json/realms/root/authenticate` | Incorrect realm path in URL | Use `/json/authenticate` (no realm prefix for root) |
| HTTP 405 on `/json/authenticate` | Used GET instead of POST | Use `-X POST` with curl |
| `Warning: 100 OpenAM REST "No Accept-API-Version specified"` | Missing version header (non-fatal) | Add `-H "Accept-API-Version: resource=2.0,protocol=1.0"` |
| `Configuration failed: The LDAP operation failed.` | Schema violation (Tweak 1 not applied) | Apply `single-structural-objectclass-behavior:accept` first |
| POST to `/config/configurator` returns 302 | AM already configured | Delete `/home/forgerock/openam` and start fresh container |
| `bindPassword` conflicts with `bindPasswordFile` | PD tool.properties has defaults | Add `--noPropertiesFile` to dsconfig/ldapsearch commands |
| `The memory size '512m' is less than the minimum required size of '768m'` | PD 11.0 needs ≥768m heap | Set `MAX_HEAP_SIZE=768m` in environment |
| `CONTAINER FAILURE: Resolve the location of your dsconfig directory` | dsconfig mounted at wrong path | Mount at `/opt/staging/pd.profile/dsconfig/`, **NOT** `/opt/staging/dsconfig/` |
| `The string value 'etag' is not a valid attribute type` | etag schema not loaded before dsconfig | Mount schema at `pd.profile/server-root/pre-setup/config/schema/99-etag.ldif` (pre-setup, not runtime) |
| `Schema configuration file 99-etag.ldif ... cannot be parsed` | Incorrect LDIF format for schema file | Use `objectClass: subschema` (not `subschemaSubentry`). No `LDIF:UNWRAP` header. |
| `KeyError: 'ContainerConfig'` from docker-compose | docker-compose v1 incompatible with newer image format | Install `docker-compose-v2`: `sudo apt-get install -y docker-compose-v2`. Use `docker compose` (space). See [§9c](#docker-compose-v2-requirement). |
| `Encryption key must be provided.` from configurator | `AM_ENC_KEY` parameter is empty | Generate a random key: `openssl rand -base64 24` |
| `keystore password was incorrect` on Tomcat HTTPS | PKCS#12 keystore mounted without matching `SSL_PWD` env var | Pass `SSL_PWD` to container or accept HTTP-only (HTTPS connector fails, HTTP still works) |
| `/json/authenticate` hangs or returns empty with `X-OpenAM-*` headers | ZeroPageLogin disabled (default in AM 8.0) — this is intentional | Use callback-based auth (2-step `authId` flow) instead. See [PingAM §8](./pingam-operations-guide.md#8-rest-authentication--callback-pattern) |
| MAKELDIF test users missing after AM setup | AM configurator overwrites PD base DN structure | Create test users **after** `configure-am.sh` completes, not during PD setup |
| `CONTAINER FAILURE: License File absent` (PingAccess) | No `.lic` file at expected path | Mount license: `-v .../PingAccess-9.0-Development.lic:/opt/out/instance/conf/pingaccess.lic:ro` |
| PA Admin API returns 401 Unauthorized | Password changed by `83-change-password.sh` hook | Set `PA_ADMIN_PASSWORD_INITIAL` and `PING_IDENTITY_PASSWORD` to the same value |
| PA engine returns 403 Forbidden | `Host:port` doesn't match any virtual host | Add `-H 'Host: localhost:3000'` to curl. See [PA Ops §6](./pingaccess-operations-guide.md#6-virtual-host-matching-critical) |
| Port 3000 already in use (PA engine won't start) | Another service (e.g., Tailscale, code-server) binds port 3000 | Remap PA engine: `-p 13000:3000` and use `PA_ENGINE_PORT=13000` in `.env` |
| docker-compose v1 can't start PA (`container name already in use`) | Existing containers created via `docker run` lack compose labels | Use `docker run` directly on same network, or `docker rm -f` the stale container first |
| `No Configuration found` on `authIndexType=service&authIndexValue=WebAuthnJourney` | Tree nodes weren't created (curl `-sf` hid errors) | Re-run import with `-s` (not `-sf`). Verify nodes exist before importing the tree. Use `Host:` header, not `--resolve` |
| Changing `orgConfig` to a tree name breaks **all** authentication | `orgConfig` must point to a chain or tree that AM can resolve at startup | Revert via `authIndexType=service&authIndexValue=ldapService` (which always works), then PUT orgConfig back to `ldapService` |
| `Rule with name '...' already exists.` | Plugin rule already created (script re-run) | Script is idempotent — existing rules are reused |
| Transform not applied (original `tokenId` in response) | Rule not attached to application resource, or profile doesn't match path | Verify: `curl …/pa-admin-api/v3/applications/1/resources` shows rule in policy. Check `activeProfile` matches YAML filename (without `.yaml`). |
| PA logs show `Registry reloaded: specs=0` | Specs directory empty or mountpoint wrong | Verify: `docker exec platform-pingaccess ls /specs/` shows YAML files |
| Headers not appearing in response | Case mismatch — PA lowercases headers | Check for `x-auth-provider` (lowercase) not `X-Auth-Provider` |
| `ConfigurationException: Configuration store is not available` + AM stuck in `health: starting` | AM container was recreated (`docker rm -f` + `docker compose up -d`), losing the PD cert from JVM truststore | Re-import PD cert and restart AM. See [PingAM Ops Guide §6](./pingam-operations-guide.md#ssl-certificate-trust) for the exact steps. The JVM `cacerts` file lives in the container filesystem, NOT on the `am-data` volume. |
| Karate E2E logout through PA returns 403 despite correct `iPlanetDirectoryPro` header | Karate's HTTP client auto-forwarded AM's `Domain=platform.local` cookies to PA's `localhost:3000` VH — AM rejects the domain mismatch | Add `* configure cookies = null` before each cross-domain request in Karate feature files. Always clear cookies when switching between AM-direct and PA-proxied calls. |
| WebAuthn auth loops back to recovery code prompt instead of succeeding | `ConfirmationCallback` value set to `0` (= "Use Recovery Code") triggers the `recoveryCode` branch of the journey | Leave `ConfirmationCallback` at its default value (`100`) — do NOT set it to `0`. Only `HiddenValueCallback` and `MetadataCallback` carry WebAuthn data. |
| WebAuthn assertion fails with HTTP 401 | `legacyData` field has double-escaped quotes (`\\"` instead of `\"`) | Apply exactly ONE level of `"` → `\"` escaping when building the HiddenValueCallback. Double-escaping causes AM to reject the credential. |
| `allowCredentials` parsing fails (empty or malformed) | AM's JS uses `Int8Array([...])` inside `allowCredentials`, breaking naive bracket regex on nested arrays | Use `indexOf('Int8Array')` + substring isolation instead of regex for parsing credential IDs from the challenge script. |
| Device cleanup API returns 404 or 401 | Wrong `Accept-API-Version` header for device management | Use `resource=1.0, protocol=1.0` (NOT `resource=2.0`) for the `/users/{uid}/devices/2fa/webauthn` endpoint. |

#### Startup order

Services must start in this order:

1. **PingDirectory** (must be healthy before AM starts)
2. **PingAM** (must be configured before PA proxies to it)
3. **PingAccess** (connects to AM as backend)

Docker Compose `depends_on` with `condition: service_healthy` handles this
automatically.

#### Fresh restart (nuke all state)

```bash
cd deployments/platform
docker compose down -v   # removes containers AND volumes
docker compose up -d     # fresh start
```

### 12. Teardown & Reset

#### Stop all containers (preserve data)

```bash
docker compose stop
```

#### Full teardown (delete data volumes)

```bash
docker compose down -v
```

#### Reset just PingAM (keep PD data)

```bash
docker compose rm -sf pingam
docker volume rm platform_am-data
docker compose up -d pingam
# Re-run configuration script after AM starts
```

---

## Part VI — E2E Testing

### 13. E2E Test Suite

Platform E2E tests live in `deployments/platform/e2e/` and use a standalone
Karate JAR (no Gradle submodule — D10). They test all authentication flows
through the full stack: Karate → PingAccess → PingAM → PingDirectory.

#### Test inventory

| Feature | File | Scenarios | Description |
|---------|------|-----------|-------------|
| Login | `auth-login.feature` | 3 | Initiate callback, submit credentials + cookie, bad credentials → 401 |
| Logout | `auth-logout.feature` | 1 | Authenticate → logout → validate session = false |
| Passkey | `auth-passkey.feature` | 3 | Full registration + auth, device-exists auth, unsupported fallback |
| Passkey (usernameless) | `auth-passkey-usernameless.feature` | 2 | Full reg + auth (discoverable credential), returning-user auth |
| Clean URL Login | `clean-url-login.feature` | 3 | Initiate via /api/v1/auth/login, full login + cookie, bad creds → 401 |
| Clean URL Passkey | `clean-url-passkey.feature` | 2 | Identifier-first username prompt, usernameless WebAuthn challenge |
| **Total** | | **14** | |

#### Helpers

| File | Purpose |
|------|---------|
| `helpers/webauthn.js` | Pure JDK FIDO2 ceremony engine — EC P-256 key generation, CBOR attestation encoding, assertion signing (SHA256withECDSA). No external dependencies. |
| `helpers/delete-device.feature` | Reusable device cleanup via AM Admin API (`/users/{uid}/devices/2fa/webauthn`) |
| `helpers/basic-auth.js` | HTTP Basic Auth header generator for AM admin operations |

#### Prerequisites

- Platform stack running (`docker compose up -d`)
- AM configured (`configure-am.sh` + `configure-am-post.sh`)
- WebAuthn journey imported (`import-webauthn-journey.sh`)
- Message-xform plugin wired (`configure-pa-plugin.sh`)
- PD cert imported into AM's JVM truststore (lost on container recreation)

#### Running tests

```bash
cd deployments/platform/e2e

# Run all tests (auto-downloads Karate JAR on first run)
./run-e2e.sh

# Run a specific feature
java -jar karate-1.4.1.jar auth-passkey.feature

# Run a specific scenario by line number
java -jar karate-1.4.1.jar auth-passkey.feature:15
```

Results are written to `target/karate-reports/` as HTML. Open
`karate-summary.html` for the full test report.

#### Key gotchas

- **Cookie jar isolation**: Clear Karate cookies (`* configure cookies = null`)
  between AM-direct and PA-proxied calls to avoid domain mismatch (platform.local
  vs localhost:3000).
- **ConfirmationCallback trap**: In the WebAuthn journey, setting
  `ConfirmationCallback` value to `0` selects "Use Recovery Code" and sends the
  journey into an infinite loop. Leave it at its default value (`100`).
- **legacyData escaping**: When building the `HiddenValueCallback` for WebAuthn
  assertions, apply exactly ONE level of `"` → `\"` escaping. Double-escaping
  causes AM to reject with HTTP 401.
- **Device API version**: The AM Admin API for WebAuthn device management uses
  `Accept-API-Version: resource=1.0, protocol=1.0` — different from the auth
  API's `resource=2.0`.
- **PD cert trust**: The JVM truststore lives in the container filesystem, not
  on the `am-data` volume. Recreating the AM container loses the PD cert.
  Re-import with `configure-am.sh` or manually (see §5).

---

## Quick Reference Card

```
┌─────────────────────────────────────────────────────────────────────┐
│  PLATFORM QUICK REFERENCE                                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Start:    docker compose up -d                                     │
│  Logs:     docker compose logs -f                                   │
│  Stop:     docker compose stop                                      │
│  Reset:    docker compose down -v && docker compose up -d           │
│                                                                     │
│  PingDirectory    LDAPS :1636   HTTPS :1443                         │
│                   user: cn=administrator  pw: 2FederateM0re         │
│                                                                     │
│  PingAM           HTTP  :8080   HTTPS :8443  path: /am              │
│                   user: amAdmin             pw: Password1           │
│                                                                     │
│  PingAccess       Engine:13000  Admin :19000  (container: 3000/9000) │
│                   user: administrator        pw: 2Access            │
│                                                                     │
│  AM Auth (callback — 2 steps via PA):                                │
│    1. POST https://localhost:13000/am/json/authenticate               │
│       → { authId, callbacks[] }                                      │
│    2. POST with filled callbacks                                     │
│       → { token, authenticated, redirectUrl, realm }                 │
│       (transformed by message-xform from tokenId/successUrl)         │
│                                                                     │
│  Transform plugin (v4.0.0):                                          │
│    Specs:    am-auth-response-v2, am-webauthn-response,              │
│              am-strip-internal, am-header-inject                     │
│    Profile:  platform-am.yaml (match.when body predicates)           │
│    Headers:  x-auth-session (authId), x-auth-provider,              │
│              x-transform-engine                                      │
│    Setup:    ./scripts/configure-pa-plugin.sh                       │
│              ./scripts/configure-pa-api.sh  # clean URL app         │
│                                                                     │
│  Clean Auth API (POST via PA, rewritten by message-xform):          │
│    POST https://localhost:13000/api/v1/auth/login                   │
│    POST https://localhost:13000/api/v1/auth/passkey                 │
│    POST https://localhost:13000/api/v1/auth/passkey/usernameless    │
│                                                                     │
│  PD tweaks (both required before AM config):                        │
│    1. single-structural-objectclass-behavior: accept                │
│    2. etag mirror VA: ds-entry-checksum → etag                      │
│                                                                     │
│  E2E Tests (14 scenarios):                                           │
│    cd deployments/platform/e2e                                      │
│    ./run-e2e.sh                     # all tests                     │
│    java -jar karate-1.4.1.jar auth-passkey.feature  # single file   │
│    Results: target/karate-reports/karate-summary.html               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## See Also

- [PingAM Operations Guide](./pingam-operations-guide.md) — image build, product deep-dive, PD compatibility, [transformed response surface](./pingam-operations-guide.md#transformed-response-via-pingaccess--message-xform)
- [PingAccess Operations Guide](./pingaccess-operations-guide.md) — reverse proxy configuration, [Admin API recipe](./pingaccess-operations-guide.md#5-admin-api--full-configuration-recipe), [deployment patterns](./pingaccess-operations-guide.md#13-deployment-patterns-hot-reload--faq)
- [E2E Validation Record](../../deployments/platform/e2e/e2e-results.md) — test breakdown, spec coverage, and run history
- [Platform README](../../deployments/platform/README.md) — architecture overview and quick start
