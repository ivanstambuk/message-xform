# Platform Deployment Guide

> End-to-end walkthrough for deploying the PingAccess + PingAM + PingDirectory
> platform. From zero to a working authentication login in a single guide.

| Field        | Value                                                     |
|--------------|-----------------------------------------------------------|
| Status       | Living document                                           |
| Last updated | 2026-02-16                                                |
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
| 8 | [Start PingAccess](#8-start-pingaccess) | Reverse proxy and plugin wiring |
| | **Part V — Operations** | |
| 9 | [Log Monitoring](#9-log-monitoring) | Real-time log tailing recipes |
| 10 | [Troubleshooting](#10-troubleshooting) | Error→fix lookup table |
| 11 | [Teardown & Reset](#11-teardown--reset) | Clean restart procedures |

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
> **Workaround:** Use `docker run` directly (see below) or upgrade to
> docker-compose v2 (`docker compose` without the hyphen).

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

#### Admin authentication

```bash
curl -s -X POST "http://<am-host>:8080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "X-OpenAM-Username: amAdmin" \
  -H "X-OpenAM-Password: Password1" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0"
```

**Expected response:**
```json
{
  "tokenId": "d8ImuQ_gP7dJ146v...",
  "successUrl": "/am/console",
  "realm": "/"
}

Set-Cookie: iPlanetDirectoryPro=d8ImuQ...; Path=/; Domain=test.local; HttpOnly
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

#### Create test users and enable header-based auth

After AM is configured and verified, run `configure-am-post.sh` to set up
test users and enable the ZeroPageLogin feature:

```bash
cd deployments/platform
./scripts/configure-am-post.sh
```

This script performs four steps:
1. Creates 10 test users (user.1–user.10) in PingDirectory under `ou=People`
2. Obtains an admin token
3. Enables ZeroPageLogin (allows `X-OpenAM-Username`/`X-OpenAM-Password` headers
   for the default authentication tree)
4. Verifies user.1 can authenticate

> **Built-in vs custom journeys:** PingAM 8.0 ships with a `ldapService`
> authentication tree (ZeroPageLogin → Page Node → Data Store Decision). This
> is set as the default tree (`orgConfig: ldapService`) and handles standard
> username/password login out of the box — no custom journey import is needed
> for basic authentication.
>
> **ZeroPageLogin:** By default, this is *disabled*. Without it, the
> `X-OpenAM-Username`/`X-OpenAM-Password` headers are ignored on the default
> `/json/authenticate` endpoint, causing silent hangs or empty responses.
> The `configure-am-post.sh` script enables it automatically.

#### Verify user authentication

```bash
curl -s -X POST "http://<am-host>:8080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "X-OpenAM-Username: user.1" \
  -H "X-OpenAM-Password: Password1" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0"
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
```

PingAccess configuration (sites, applications, rules) is done via the Admin API
on port 9000 after startup. This will be automated in
`scripts/configure-pa.sh` (Phase 5 of the implementation plan).

---

## Part V — Operations

### 9. Log Monitoring

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

### 10. Troubleshooting

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
| `KeyError: 'ContainerConfig'` from docker-compose | docker-compose v1 incompatible with newer image format | Use `docker run` directly, or upgrade to docker-compose v2 (`docker compose`) |
| `Encryption key must be provided.` from configurator | `AM_ENC_KEY` parameter is empty | Generate a random key: `openssl rand -base64 24` |
| `keystore password was incorrect` on Tomcat HTTPS | PKCS#12 keystore mounted without matching `SSL_PWD` env var | Pass `SSL_PWD` to container or accept HTTP-only (HTTPS connector fails, HTTP still works) |
| `/json/authenticate` hangs or returns empty with `X-OpenAM-*` headers | ZeroPageLogin disabled (default in AM 8.0) | Enable via REST API: `PUT /json/realms/root/realm-config/authentication` with `{"security":{"zeroPageLoginEnabled":true}}` |
| MAKELDIF test users missing after AM setup | AM configurator overwrites PD base DN structure | Create test users **after** `configure-am.sh` completes, not during PD setup |


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

### 11. Teardown & Reset

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
│  PingAccess       Engine:3000   Admin :9000                         │
│                                                                     │
│  AM Auth:  curl -X POST http://am:8080/am/json/authenticate \       │
│            -H "X-OpenAM-Username: amAdmin"                   \      │
│            -H "X-OpenAM-Password: Password1"                 \      │
│            -H "Accept-API-Version: resource=2.0,protocol=1.0"       │
│                                                                     │
│  PD tweaks (both required before AM config):                        │
│    1. single-structural-objectclass-behavior: accept                │
│    2. etag mirror VA: ds-entry-checksum → etag                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## See Also

- [PingAM Operations Guide](./pingam-operations-guide.md) — image build, product deep-dive, PD compatibility details
- [PingAccess Operations Guide](./pingaccess-operations-guide.md) — reverse proxy configuration
- [Implementation Plan](../../deployments/platform/PLAN.md) — phased plan with live tracker
- [Platform README](../../deployments/platform/README.md) — architecture overview and quick start
