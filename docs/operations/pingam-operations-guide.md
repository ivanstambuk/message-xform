# PingAM (Access Management) Operations Guide

> Practitioner's guide for building, running, and configuring the PingAM Docker image.

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
| 1 | [Docker Image Build](#1-docker-image-build) | Building the evaluation Docker image from the WAR file |
| 2 | [Running PingAM](#2-running-pingam) | Container startup, initial setup, context path |
| 3 | [Filesystem Layout](#3-filesystem-layout) | Key paths inside the container |
| 4 | [Important Notes](#4-important-notes) | Vendor Dockerfile limitations, production concerns |
| | **Part II — Rebuild Playbook** | |
| 5 | [Full Rebuild from Scratch](#5-full-rebuild-from-scratch) | Step-by-step for a clean machine |
| | **Part III — PingDirectory Compatibility** | |
| 6 | [Using PingDirectory as AM Backend](#6-using-pingdirectory-as-am-backend) | Verified: PingAM 8.0.2 on PingDirectory 11.0 |
| | **Part IV — Authentication & Identity** | |
| 7 | [Built-in Authentication Trees](#7-built-in-authentication-trees) | ldapService, Agent, amsterService |
| 8 | [REST Authentication — Callback Pattern](#8-rest-authentication--callback-pattern) | 2-step callback auth flow |
| 8b | [REST API Gotchas](#8b-rest-api-gotchas) | Host header, curl -sf, orgConfig trap |
| 9 | [Identity Store & Test Users](#9-identity-store--test-users) | User creation under ou=People |
| | **Part V — WebAuthn / FIDO2 Journeys** | |
| 10 | [WebAuthn Journey](#10-webauthn-journey) | Passkey journey flow, config, callbacks |
| 10b | [Importing Trees via REST API](#10b-importing-trees-via-rest-api) | Node-first import procedure |

---

## Part I — Docker Image

### 1. Docker Image Build

PingAM ships as a standard Java WAR file (`AM-8.0.2.war`). Unlike PingGateway
and PingDS, the vendor-supplied Dockerfiles in `samples/docker/` reference
**private Ping CI base images** (`gcr.io/engineering-devops/ci/base-images/...`)
and are designed for their internal ForgeOps pipeline — they cannot be built
locally.

We use a **custom Dockerfile** based on the pattern from [Ping Identity's
webinar repo](https://github.com/pingidentity/webinar-pingfed-pingam):
deploy the WAR onto a public Tomcat base image.

#### Prerequisites

- Docker daemon running (`docker info` to verify)
- PingAM WAR file at: `binaries/pingam/artifacts/AM-8.0.2.war`
- The WAR is downloaded from
  [Ping Identity Download Center (Backstage)](https://backstage.pingidentity.com/downloads)

#### Build command

```bash
# From the pingam binaries directory
cd binaries/pingam
docker build . -f docker/Dockerfile -t pingam:8.0.2
```

#### What the Dockerfile does

```dockerfile
FROM tomcat:10.1-jdk21-temurin-jammy       # Public Tomcat 10.1 + JDK 21 base
# Create forgerock user (uid 11111)
# Configure CATALINA_OPTS for AM (JVM args, module opens/exports)
# Deploy AM-8.0.2.war → /usr/local/tomcat/webapps/am/
# Pre-extract WAR for faster startup
USER 11111
EXPOSE 8080
CMD ["catalina.sh", "run"]
```

Key JVM options set via `CATALINA_OPTS`:
- `-Dcom.sun.identity.configuration.directory=$AM_HOME` — AM config home
- `-Dorg.forgerock.donotupgrade=true` — Skip upgrade wizard
- `--add-opens`/`--add-exports` for Java 21 module compatibility
- `-Xmx1g` and `-XX:MaxRAMPercentage=75`

- **Base image**: `tomcat:10.1-jdk21-temurin-jammy` (public Docker Hub)
- **User**: `forgerock` (uid `11111`) — consistent with PingDS/PingGateway
- **Final image size**: ~1.31 GB (WAR is 193 MB, Tomcat+JDK base is ~500 MB,
  unpacked webapp adds the rest)

#### Verify

```bash
docker image list | grep pingam
# Expected: pingam  8.0.2  <id>  1.31GB
```

### 2. Running PingAM

#### Basic run

```bash
# Start on port 8080 (detached)
docker run -d --name pingam -p 8080:8080 pingam:8.0.2
```

#### First-run behaviour

On first start (no configuration directory present), PingAM returns HTTP **302**
redirecting to the setup/configuration wizard at:
```
http://localhost:8080/am
```

PingAM **requires an external directory server** for its configuration
store, user store, CTS, and policy store. This can be either PingDS (ForgeRock DS)
or PingDirectory (Ping Identity) — see [Part III](#6-using-pingdirectory-as-am-backend)
for the PingDirectory setup. The setup wizard will prompt for DS connection details.

**Typical startup log** (abbreviated):
```
Base Directory: /home/forgerock/openam
Application Context: /am
Deployment of web application directory [.../webapps/am] has finished in [6,243] ms
Starting ProtocolHandler ["http-nio-8080"]
Server startup in [6276] milliseconds
```

#### With a PingDirectory backend (recommended)

For a functional AM instance, you need a directory server running first.
PingDirectory 11.0 is the recommended backend (see [Part III](#6-using-pingdirectory-as-am-backend)):

```bash
# Start PingDirectory first
docker run -d --name pingdirectory \
  -e PING_IDENTITY_ACCEPT_EULA=YES \
  -p 1636:1636 -p 1443:1443 \
  pingidentity/pingdirectory:11.0.0.1-latest

# Then start PingAM
docker run -d --name pingam \
  -p 8080:8080 \
  --link pingdirectory:pd.example.com \
  pingam:8.0.2
```

#### Stop

```bash
docker stop pingam
docker rm pingam
```

### 3. Filesystem Layout

| Path | Purpose |
|------|---------|
| `/usr/local/tomcat/` | Tomcat installation (`$CATALINA_HOME`) |
| `/usr/local/tomcat/webapps/am/` | Deployed AM web application |
| `/home/forgerock/` | ForgeRock home (`$FORGEROCK_HOME`) |
| `/home/forgerock/openam/` | AM configuration directory (`$AM_HOME`) |

#### Volume mounts (for persistence)

| Mount point | Purpose |
|-------------|---------|
| `/home/forgerock/openam` | AM configuration (survives container restarts) |

### 4. Important Notes

#### Why we don't use the vendor Dockerfiles

The PingAM distribution includes three Dockerfiles in `samples/docker/images/`:

| Dockerfile | Problem |
|---|---|
| `am-empty` | References private `gcr.io/engineering-devops/ci/base-images/tomcat:10-jdk17` |
| `am-base` | Depends on `am-empty` + Maven build artifacts (`base-config.tar`, `crypto-tool.jar`) |
| `am-cdk` | Depends on `am-base` + CDK configuration directory |

These are designed for Ping Identity's internal ForgeOps CI/CD pipeline and
use Maven variable interpolation (`${docker.push.repo}`, `${docker.tag}`).
They cannot be built from the distribution ZIP alone.

#### No official Docker Hub image

Unlike PingAccess (`pingidentity/pingaccess`), PingFederate
(`pingidentity/pingfederate`), and PingDirectory (`pingidentity/pingdirectory`),
there is **no official `pingidentity/pingam` image** on Docker Hub.

The community-recommended approach is to deploy the WAR onto a standard Tomcat
Docker image, which is what our custom Dockerfile does.

---

## Part II — Rebuild Playbook

### 5. Full Rebuild from Scratch

Use this section when setting up PingAM Docker on a **new machine** from zero.

#### Step 1: Download the distribution

1. Go to [Ping Identity Download Center (Backstage)](https://backstage.pingidentity.com/downloads)
2. Download **PingAM 8.0.2** → `AM-8.0.2.zip`
3. Place it in `binaries/pingam/artifacts/`
4. Extract: `cd binaries/pingam/extracted && unzip ../artifacts/AM-8.0.2.zip`
5. The WAR file is at: `binaries/pingam/artifacts/AM-8.0.2.war`
   (also available inside the ZIP at `openam/AM-8.0.2.war`)

#### Step 2: Build the Docker image

```bash
cd binaries/pingam
docker build . -f docker/Dockerfile -t pingam:8.0.2
```

**Expected output** (abbreviated):
```
Step 1/12 : FROM tomcat:10.1-jdk21-temurin-jammy
 → Pulls Tomcat 10.1 + JDK 21 base (~500 MB first time, cached after)
Step 7/12 : COPY artifacts/AM-8.0.2.war ...
 → Copies 193 MB WAR file
Step 8/12 : RUN ... jar xf ../am.war ...
 → Unpacks WAR for faster startup
Successfully tagged pingam:8.0.2
```

**Note:** The `docker build` sends ~1.3 GB of context because the `binaries/pingam/`
directory includes all artifacts. Consider adding a `.dockerignore` if build time
is a concern.

#### Step 3: Verify

```bash
# Check image exists
docker image list | grep pingam

# Quick smoke test
docker run --rm -d --name am-test -p 18080:8080 pingam:8.0.2
sleep 10
curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/am
# Expected: 302 (redirect to setup wizard — no DS backend configured)
docker stop am-test
```

#### Step 4: Tag for convenience (optional)

```bash
docker tag pingam:8.0.2 pingam:latest
```

### Key facts for rebuild

| Item | Value |
|------|-------|
| Distribution | `AM-8.0.2.zip` / `AM-8.0.2.war` |
| Source | [Backstage](https://backstage.pingidentity.com/downloads) |
| WAR file | `binaries/pingam/artifacts/AM-8.0.2.war` (193 MB) |
| Dockerfile path | `binaries/pingam/docker/Dockerfile` (custom — not vendor-supplied) |
| Base image | `tomcat:10.1-jdk21-temurin-jammy` (public Docker Hub) |
| Final image | `pingam:8.0.2` (~1.31 GB) |
| AM context path | `/am` (i.e., `http://localhost:8080/am`) |
| Process user | `forgerock` (uid `11111`) |
| Config directory | `/home/forgerock/openam/` |
| First-run response | HTTP 302 → setup wizard |
| Backend dependency | PingDS or PingDirectory (see [Part III](#6-using-pingdirectory-as-am-backend)) |

### Key differences from PingGateway and PingDS

| Aspect | PingGateway | PingDS | PingAM |
|--------|-------------|--------|--------|
| Vendor Dockerfile | ✅ Works | ✅ Works | ❌ Private CI deps |
| Our Dockerfile | Vendor-supplied | Vendor-supplied | **Custom** (WAR on Tomcat) |
| Base image | `gcr.io/.../java-21` | `gcr.io/.../java-21` | `tomcat:10.1-jdk21` |
| Image size | 327 MB | 243 MB | 1.31 GB |
| First-run | Welcome page (200) | Server ready (logs) | Setup wizard (302) |
| External deps | None | Keystore (or demo) | PingDirectory or PingDS |
| Docker Hub image | None | None | None |

---

## Part III — PingDirectory Compatibility

### 6. Using PingDirectory as AM Backend

> **Verified:** PingAM 8.0.2 runs on PingDirectory 11.0 as its sole LDAP backend
> (config store, CTS, policy store, AND user store). Tested Feb 16, 2026.

The Ping Identity documentation ambiguously states that PingAM requires "PingDS"
for its config and CTS stores. However, live testing proved that PingDirectory
works as a drop-in replacement with two configuration tweaks.

#### Why This Works

PingDS (ForgeRock DS / OpenDJ) and PingDirectory (Ping Identity / UnboundID)
are now both owned by Thoma Bravo and share an LDAP protocol foundation. PingAM's
`/config/configurator` endpoint uses standard LDAP operations (add, search, modify)
that both products support. The only differences are in schema strictness and
virtual attribute availability.

#### Required PingDirectory Tweaks

Two configuration changes must be applied to PingDirectory **before** configuring
PingAM.

##### Tweak 1: Schema Relaxation

**Problem:** PingAM writes LDAP entries (e.g., `ou=iPlanetAMAuthConfiguration,...`)
that lack a structural objectClass. PingDS accepts these silently; PingDirectory
rejects them with:

```
Object Class Violation: Entry 'ou=iPlanetAMAuthConfiguration,ou=services,dc=example,dc=com'
violates the Directory Server schema configuration because it does not include a
structural object class.
```

**Fix:**
```bash
dsconfig set-global-configuration-prop \
  --set single-structural-objectclass-behavior:accept
```

This tells PingDirectory to accept entries without structural objectClasses
instead of rejecting them.

##### Tweak 2: ETag Virtual Attribute

**Problem:** PingAM's Core Token Service (CTS) uses an `etag` attribute for
optimistic concurrency control on session tokens. PingDS has this as a built-in
feature. PingDirectory does NOT have an `etag` attribute, causing:

```
CTS: Unable to retrieve the etag from the token
```

This error occurs during authentication — the authentication tree evaluates
successfully, but session creation fails when CTS tries to persist the session.

**Fix (two steps):**

1. **Add `etag` attribute to PingDirectory schema:**

For **manual** application via `ldapmodify` (ad-hoc testing), use LDIF modify format:

```ldif
dn: cn=schema
changetype: modify
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.36733.2.1.999.1
  NAME 'etag'
  DESC 'CTS entry tag for optimistic concurrency control'
  EQUALITY caseIgnoreMatch
  SYNTAX 1.3.6.1.4.1.1466.115.121.1.15
  SINGLE-VALUE
  USAGE userApplications
  X-ORIGIN 'PingAM CTS compatibility' )
```

For **Docker Compose** (automated), the schema must be a native PD schema entry
mounted at `pd.profile/server-root/pre-setup/config/schema/99-etag.ldif`. This
format is different — it uses `objectClass: subschema` and is NOT an LDIF modify:

```ldif
dn: cn=schema
objectClass: top
objectClass: ldapSubentry
objectClass: subschema
cn: schema
attributeTypes: ( 1.3.6.1.4.1.36733.2.1.999.1
  NAME 'etag'
  DESC 'CTS entry tag for optimistic concurrency control'
  EQUALITY caseIgnoreMatch
  SYNTAX 1.3.6.1.4.1.1466.115.121.1.15
  SINGLE-VALUE
  USAGE userApplications
  X-ORIGIN 'PingAM CTS compatibility' )
```

> **Critical:** The etag schema MUST be loaded as a pre-setup file (before PD
> setup runs), not via ldapmodify after PD starts. The dsconfig step that creates
> the mirror VA references `etag` — if the schema isn't loaded yet, dsconfig fails
> and PD shuts down immediately after starting.

2. **Create mirror virtual attribute mapping `ds-entry-checksum → etag`:**

```bash
dsconfig create-virtual-attribute \
  --name "CTS ETag" \
  --type mirror \
  --set enabled:true \
  --set attribute-type:etag \
  --set source-attribute:ds-entry-checksum \
  --set base-dn:dc=example,dc=com
```

PingDirectory's `ds-entry-checksum` provides the same per-entry hash functionality
that PingDS's native `etag` does. The mirror virtual attribute makes it available
under the name PingAM expects.

#### PingAM Configuration Parameters

When configuring PingAM via the `/config/configurator` REST endpoint to use
PingDirectory, use these parameters:

| Parameter | Value | Notes |
|-----------|-------|-------|
| `DATA_STORE` | `dirServer` | External directory (not embedded) |
| `DIRECTORY_SSL` | `SSL` | Use LDAPS |
| `DIRECTORY_SERVER` | `<pd-hostname>` | PingDirectory hostname |
| `DIRECTORY_PORT` | `1636` | LDAPS port |
| `DIRECTORY_ADMIN_PORT` | `1443` | PD HTTPS admin port |
| `ROOT_SUFFIX` | `dc=example,dc=com` | PD's existing base DN |
| `DS_DIRMGRDN` | `cn=administrator` | PD root user DN |
| `DS_DIRMGRPASSWD` | `<password>` | PD root user password |
| `USERSTORE_TYPE` | `LDAPv3ForOpenDS` | PD is OpenDS-compatible |
| `USERSTORE_SSL` | `SSL` | Use LDAPS for user store too |
| `USERSTORE_HOST` | `<pd-hostname>` | Same PD instance |
| `USERSTORE_PORT` | `1636` | Same LDAPS port |
| `USERSTORE_SUFFIX` | `dc=example,dc=com` | Same or different base DN |
| `USERSTORE_MGRDN` | `cn=administrator` | PD root user DN |
| `AM_ENC_KEY` | random base64 | **Must not be empty.** `Encryption key must be provided.` Use `openssl rand -base64 24`. |

> **Key insight:** Both config store and user store point to the **same**
> PingDirectory instance. PingAM creates its own subtrees
> (`ou=services`, `ou=tokens`, etc.) under the base DN.

#### Automated Configuration (`configure-am.sh`)

The project includes `scripts/configure-am.sh` which automates the complete
first-run setup: TLS cert import → configurator POST → progress monitoring →
admin auth verification.

```bash
cd deployments/platform
./scripts/configure-am.sh            # full run
./scripts/configure-am.sh --skip-cert # skip cert import (already done)
```

The script is idempotent — it detects already-configured AM by trying admin
authentication before sending the configurator POST.

With a local PD, configuration completes in ~15-30 seconds (98 steps, 0 errors).
On remote/slow infrastructure, allow up to 5 minutes.

#### SSL Certificate Trust

PingAM's JVM must trust PingDirectory's TLS certificate. For self-signed certs:

```bash
# Extract PD's certificate
openssl s_client -connect <pd-host>:1636 -showcerts </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > pd-cert.pem

# Import into AM's JVM truststore (run as root in AM container)
keytool -importcert \
  -alias pingdirectory \
  -file pd-cert.pem \
  -cacerts \
  -storepass changeit \
  -trustcacerts \
  -noprompt
```

> **Gotcha:** PingAM runs as user `forgerock` (uid 11111), but the JVM cacerts
> file is owned by root. You must exec into the container as root (`docker exec -u 0`)
> to import certificates.
>
> **Gotcha — docker-compose v1:** The `pingam:8.0.2` image (built with newer
> Docker) causes `KeyError: 'ContainerConfig'` in docker-compose v1 (1.29.x).
> Install Docker Compose v2: `sudo apt-get install -y docker-compose-v2`.
> Then use `docker compose` (space, not hyphen). See
> [Platform Deployment Guide §9c](./platform-deployment-guide.md#docker-compose-v2-requirement).
>
> **Gotcha — HTTPS keystore:** If mounting a custom PKCS#12 keystore in
> `server.xml`, ensure the `SSL_PWD` environment variable matches the keystore
> password. If mismatched, Tomcat's HTTPS connector fails with `keystore password
> was incorrect`, but HTTP continues to work (non-fatal, degraded mode).
>
> **Gotcha — cert trust lost after container recreation (CRITICAL):**
> The JVM truststore (`cacerts`) lives inside the container filesystem, **not** on
> the `am-data` Docker volume (`/home/forgerock/openam`). If the AM container is
> deleted and recreated (e.g. `docker rm -f platform-pingam && docker compose up -d`),
> the PD certificate import is lost. AM will start Tomcat successfully but fail
> every healthcheck with HTTP 500 and log:
>
> ```
> ConfigurationException: Configuration store is not available.
> ```
>
> **Symptoms:** AM container stuck in `health: starting` for 5+ minutes. The
> Docker Compose healthcheck (`curl -sf http://localhost:8080/am/`) returns
> exit code 1 because AM responds with HTTP 500 (which `-f` treats as failure).
>
> **Fix:** Re-import the PD certificate and restart AM:
>
> ```bash
> # Extract PD cert from host (PD container lacks openssl)
> echo | openssl s_client -connect localhost:1636 -showcerts </dev/null 2>/dev/null \
>   | openssl x509 -outform PEM > /tmp/pd-cert.pem
>
> # Import into AM container
> docker cp /tmp/pd-cert.pem platform-pingam:/tmp/pd-cert.pem
> docker exec -u 0 platform-pingam keytool -importcert \
>   -alias pingdirectory -file /tmp/pd-cert.pem \
>   -cacerts -storepass changeit -trustcacerts -noprompt
>
> # Restart AM to pick up truststore change
> docker restart platform-pingam
> ```
>
> **Note:** The PD container image does NOT include `openssl`, so you must
> extract the certificate from the **host** using the mapped port (1636), not
> from inside the PD container.
>
> **After restart:** AM connects to PD's LDAPS within seconds, healthcheck
> passes on the first probe (HTTP 200), and PA (which depends on `service_healthy`
> from AM) starts automatically.

#### Monitoring and Debugging

##### PingDirectory Access Log

The most useful log for debugging AM↔PD connectivity is PingDirectory's access log:

```bash
# Live tail of PD access log
docker exec <pd-container> tail -f /opt/out/instance/logs/access
```

Target patterns to look for:
- `resultCode=0` — successful operations (normal)
- `resultCode=32` — "No Such Object" (missing entry — check base DN)
- `resultCode=65` — "Object Class Violation" (schema tweak not applied)
- `base="ou=famrecords,ou=openam-session,ou=tokens,..."` — CTS token operations
- `base="ou=services,..."` — AM config reads

##### PingAM Debug Logs

PingAM writes debug logs inside the container:

```bash
# Key debug files
docker exec <am-container> ls /home/forgerock/openam/var/debug/

# Most useful:
docker exec <am-container> tail -f /home/forgerock/openam/var/debug/CoreSystem

# Installation log (first-run only):
docker exec <am-container> cat /home/forgerock/openam/var/install.log
```

| Debug file | Contains |
|------------|----------|
| `CoreSystem` | CTS errors, LDAP connection issues, session persistence |
| `Authentication` | Auth tree execution, login failures |
| `IdRepo` | User store lookups, identity operations |
| `OAuth2Provider` | OAuth2/OIDC token operations |
| `Configuration` | Config store reads/writes |

##### PingAM Container Logs (stdout)

Tomcat/AM stdout shows startup and initial LDAP connections:

```bash
docker logs --tail 20 <am-container>
```

Look for:
- `Connection factory 'LdapClient(host=..., port=1636, protocol=LDAPS)' is now operational`
  — confirms AM↔PD connectivity
- `Server startup in [XXXX] milliseconds` — Tomcat ready

#### Test Evidence (Feb 16, 2026)

| Test | Result |
|------|--------|
| PingAM configurator → PingDirectory | ✅ 98 schema files loaded (all successful) |
| CTS token persistence | ✅ 13 tokens in `ou=tokens,dc=example,dc=com` |
| Admin authentication (`amAdmin`) | ✅ Valid `tokenId` + `iPlanetDirectoryPro` cookie |
| PD access log evidence | ✅ 4000+ successful LDAP ops from AM |
| PD `single-structural-objectclass-behavior` | Default: `reject` → set to: `accept` |
| PD `etag` virtual attribute | Created: mirror `ds-entry-checksum → etag` |
| User authentication (`user.1`) | ✅ Valid `tokenId` via callback auth against `ldapService` tree |
| ZeroPageLogin | Deliberately disabled (AM 8.0 default) — using callbacks instead |

---

## Part IV — Authentication & Identity

### 7. Built-in Authentication Trees

PingAM 8.0.2 ships with **3 built-in authentication trees** in the root realm
(no custom journey import needed for basic username/password login):

| Tree ID | Purpose | Node Flow |
|---------|---------|----------|
| `ldapService` | Username/password login (default) | ZeroPageLogin → Page Node (Username + Password) → Data Store Decision |
| `Agent` | Agent authentication | ZeroPageLogin → Page Node → Agent Data Store Decision |
| `amsterService` | Amster CLI authentication | Amster JWT Decision Node |

The default tree for the root realm is controlled by the `orgConfig` field:

```json
{
  "core": {
    "adminAuthModule": "ldapService",
    "orgConfig": "ldapService"
  }
}
```

> ⚠️ **`orgConfig` safety trap.** Never set `orgConfig` to a service name that
> AM cannot resolve. If set to a non-existent tree/chain, **all authentication
> breaks** — including the default `/json/authenticate` endpoint. Recovery
> requires authenticating via `authIndexType=service&authIndexValue=ldapService`
> (which always works regardless of orgConfig) and then PUT-ing orgConfig back
> to `ldapService`. See [§8b](#8b-rest-api-gotchas) for details.

#### Specifying an authentication tree

When calling `/json/authenticate`, you can select a specific tree:

```bash
# Default tree (uses orgConfig — ldapService)
curl -X POST "http://<am-host>:8080/am/json/authenticate" ...

# Explicit tree selection
curl -X POST "http://<am-host>:8080/am/json/authenticate?authIndexType=service&authIndexValue=ldapService" ...
```

> **Note:** Our platform keeps ZeroPageLogin **disabled** (the AM 8.0 default).
> All scripts use the callback-based authentication pattern (see §8). The
> `X-OpenAM-Username`/`X-OpenAM-Password` header approach is not used.

#### Query all trees via REST

```bash
curl -s -H "Host: am.platform.local:18080" \
  "http://127.0.0.1:18080/am/json/realms/root/realm-config/authentication/authenticationtrees/trees?_queryFilter=true" \
  -H "iPlanetDirectoryPro: <admin-token>" \
  -H "Accept-API-Version: protocol=2.0,resource=1.0"
```

### 8. REST Authentication — Callback Pattern

PingAM supports two REST authentication patterns. We use **callbacks** (the
vendor-recommended approach). ZeroPageLogin (header-based) is deliberately
kept disabled.

#### Callback authentication (recommended — what we use)

The callback pattern is a 2-step flow:

1. **Initiate** — `POST /json/authenticate` with empty body. AM returns an
   `authId` JWT and an array of callbacks describing what information it needs.
2. **Complete** — `POST /json/authenticate` with the `authId` and filled-in
   callbacks. AM returns a `tokenId` (session token).

```bash
# Step 1: Get authId and callbacks
STEP1=$(curl -s -H "Host: am.platform.local:18080" \
  -X POST "http://127.0.0.1:18080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0")

# STEP1 contains:
# {
#   "authId": "eyJ0eXAiOi...",
#   "callbacks": [
#     { "type": "NameCallback",     "input": [{"name":"IDToken1","value":""}], "_id": 0 },
#     { "type": "PasswordCallback", "input": [{"name":"IDToken2","value":""}], "_id": 1 }
#   ]
# }

AUTH_ID=$(echo "$STEP1" | python3 -c "import sys,json; print(json.load(sys.stdin)['authId'])")

# Step 2: Return filled callbacks
curl -s -H "Host: am.platform.local:18080" \
  -X POST "http://127.0.0.1:18080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0" \
  -d "{
    \"authId\": \"${AUTH_ID}\",
    \"callbacks\": [
      {\"type\":\"NameCallback\",\"input\":[{\"name\":\"IDToken1\",\"value\":\"bjensen\"}],
       \"output\":[{\"name\":\"prompt\",\"value\":\"User Name\"}],\"_id\":0},
      {\"type\":\"PasswordCallback\",\"input\":[{\"name\":\"IDToken2\",\"value\":\"Ch4ng31t\"}],
       \"output\":[{\"name\":\"prompt\",\"value\":\"Password\"}],\"_id\":1}
    ]
  }"
# Returns: { "tokenId": "AQIC5wM…", "successUrl": "/am/console", "realm": "/" }
```

#### Transformed response (via PingAccess + message-xform)

When PingAM is fronted by PingAccess with the message-xform adapter, the
success response is transformed before reaching the client:

| AM raw response | Transformed response |
|----------------|---------------------|
| `"tokenId": "AQIC5wM…"` | `"token": "AQIC5wM…"` |
| `"successUrl": "/am/console"` | `"redirectUrl": "/am/console"` |
| (none) | `"authenticated": true` |
| `"realm": "/"` | `"realm": "/"` |

Additionally, response headers are injected:
- `x-auth-provider: PingAM`
- `x-transform-engine: message-xform`

Callback responses (containing `authId` + `callbacks[]`) are **not** transformed —
the JSLT `if (.tokenId)` guard ensures only final success responses are modified.
This is critical because AM's callback protocol requires the `authId` JWT to be
echoed back verbatim in subsequent requests.

> **See:** [Platform Deployment Guide §9c](./platform-deployment-guide.md#9c-message-xform-plugin-wiring)
> for the full plugin wiring procedure, spec files, and profile configuration.

#### Why callbacks are preferred over ZeroPageLogin

| Aspect | Callback (2-step) | ZeroPageLogin (headers) |
|--------|-------------------|-------------------------|
| Security | `authId` JWT acts as CSRF token | Vulnerable to Login CSRF |
| Default state | Works out of the box | Disabled by default (AM 8.0) |
| MFA support | Handles any journey (MFA, WebAuthn, etc.) | Username/password only |
| Extensibility | Adding MFA nodes works transparently | Breaks silently if tree changes |
| Credential exposure | Credentials in POST body (not logged) | Headers may be logged by proxies |
| Vendor recommendation | ✅ Primary documented approach | Convenience shortcut only |

#### Why ZeroPageLogin is disabled (security rationale)

ZeroPageLogin (`openam.auth.zero.page.login.enabled`) defaults to `false`
in PingAM 8.0 for three reasons:

1. **Login CSRF protection** — With ZPL enabled, a malicious site can forge a
   POST to `/json/authenticate` with `X-OpenAM-Username`/`X-OpenAM-Password`
   headers, potentially authenticating a victim's browser. The `authId` JWT in
   the callback flow acts as a natural CSRF token.
2. **Credential logging risk** — The original ZPL setting also controlled
   GET-based login (credentials in URL query parameters), which browsers cache
   and servers log. Disabling it by default prevents accidental exposure.
3. **Principle of least privilege** — Forces administrators to make an explicit
   security decision if they need the shortcut.

> **Historical note:** The ForgeRock/Ping webinar reference code
> (`webinar-pingfed-pingam`) used ZeroPageLogin headers because it was built
> for AM 7.4/7.5 where the default was `true`. AM 8.0 tightened the defaults.

### 9. Identity Store & Test Users

#### Default identity store layout

When PingAM is configured with `USERSTORE_SUFFIX=dc=example,dc=com` and
`USERSTORE_TYPE=LDAPv3ForOpenDS`, it expects user entries under:

```
dc=example,dc=com
├── ou=People          ← user entries (inetOrgPerson)
└── ou=Groups          ← group entries
```

AM searches `ou=People` by default for user lookups. The DN format is:
```
uid=<username>,ou=People,dc=example,dc=com
```

#### Creating test users

Test users are defined in `config/test-users.ldif` and loaded via:

```bash
docker cp config/test-users.ldif <pd-container>:/tmp/
docker exec <pd-container> /opt/out/instance/bin/ldapmodify \
  --hostname localhost --port 1636 --useSsl --trustAll \
  --bindDN "cn=administrator" --bindPassword 2FederateM0re \
  --filename /tmp/test-users.ldif
```

Each user entry requires these objectClasses:
- `top`, `person`, `organizationalPerson`, `inetOrgPerson`

Minimum required attributes:
- `uid` (the login username)
- `cn`, `sn` (required by `person`)
- `userPassword` (plaintext — PD hashes on import via its password policy)

> **PD plaintext password handling:** PingDirectory accepts plaintext passwords
> in `userPassword` during LDAP add/modify operations and automatically hashes
> them using its configured password storage scheme (default: PBKDF2-SHA512).
> No pre-hashing is needed.

> **MAKELDIF users wiped by AM configurator:** If PingDirectory creates users
> via `MAKELDIF_USERS` during initial setup, those entries may be overwritten
> or displaced when PingAM's configurator creates its DIT structure
> (`ou=People`, `ou=Groups`, `ou=services`, `ou=tokens`, etc.) under the base DN.
> Always create test users **after** AM configuration completes.

### 8b. REST API Gotchas

These patterns were discovered through multi-hour debugging sessions and are
critical for anyone writing scripts that call the PingAM REST API.

#### Host header requirement (CRITICAL)

PingAM 8.0 validates the `Host` header against its configured `SERVER_URL`.
If the Host header doesn't match `am.platform.local:18080`, AM returns
HTTP 400 with no useful error — the response body is empty or generic.

```bash
# ✅ CORRECT: Explicit Host header + localhost IP
curl -s -H "Host: am.platform.local:18080" \
  -X POST "http://127.0.0.1:18080/am/json/authenticate" ...

# ❌ BROKEN: --resolve alone (may hang on DNS, Host header may mismatch)
curl --resolve "am.platform.local:18080:127.0.0.1" \
  "http://am.platform.local:18080/am/json/authenticate" ...

# ❌ BROKEN: Direct IP without Host header
curl "http://127.0.0.1:18080/am/json/authenticate" ...
```

The `--resolve` flag maps DNS resolution but **does not bypass DNS lookup
entirely** — if `am.platform.local` isn't in `/etc/hosts`, curl may still
hang. The `Host:` + `127.0.0.1` pattern avoids DNS completely.

#### curl -sf antipattern (CRITICAL)

**Never use `curl -sf` for AM API calls.** The `-f` flag ("fail silently")
makes curl return an empty response on HTTP errors (4xx/5xx), swallowing
AM's JSON error bodies which contain the actual diagnostic information.

```bash
# ✅ CORRECT: -s (silent) only
RESPONSE=$(curl -s --max-time 10 ...) 
# Then check $RESPONSE for errors

# ❌ BROKEN: -sf (fail silently) → errors invisible
RESPONSE=$(curl -sf --max-time 10 ...)
# On AM error: RESPONSE="" → script thinks success
```

This was the **root cause** of a multi-hour debugging session: node imports
were silently failing (401 from missing Host header), so the tree was
created referencing non-existent nodes, causing `"No Configuration found"`
when invoked via `authIndexType=service`.

#### orgConfig safety trap

The `orgConfig` field in `/json/realms/root/realm-config/authentication`
controls the **default** authentication service. Setting it to a name that
AM cannot resolve **breaks ALL authentication**, including the default
`/json/authenticate` endpoint.

**Recovery procedure:**
```bash
# Step 1: Auth via ldapService explicitly (always works regardless of orgConfig)
curl -s -H "Host: am.platform.local:18080" \
  -X POST "http://127.0.0.1:18080/am/json/authenticate?authIndexType=service&authIndexValue=ldapService" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0"
# (follow 2-step callback flow to get admin token)

# Step 2: Revert orgConfig to ldapService
curl -s -H "Host: am.platform.local:18080" \
  -X PUT "http://127.0.0.1:18080/am/json/realms/root/realm-config/authentication" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: protocol=1.0,resource=1.0" \
  -H "iPlanetDirectoryPro: ${TOKEN}" \
  -d '{"core":{"orgConfig":"ldapService","adminAuthModule":"ldapService"}}'
```

#### API version headers

All AM REST calls require `Accept-API-Version`. Wrong versions produce
unhelpful 400 errors.

| Endpoint Type | Header Value |
|--------------|-------------|
| Authentication (`/json/authenticate`) | `resource=2.0,protocol=1.0` |
| Realm config (`/realm-config/...`) | `protocol=1.0,resource=1.0` |
| Tree management (`/authenticationtrees/...`) | `protocol=2.0,resource=1.0` |
| User management (`/users/...`) | `protocol=2.0,resource=4.0` |

---

## Part V — WebAuthn / FIDO2 Journeys

### 10. WebAuthn Journey

The `WebAuthnJourney` tree enables passwordless authentication using
FIDO2/WebAuthn passkeys. It is adapted from the webinar reference journey
(`WebinarJourneyWebAuthN.journey.json`) with local-environment overrides.

#### Journey flow (complete wiring)

```
Start → UsernameCollector → WebAuthnAuth
  ├─ success → ✓ SUCCESS (tokenId)
  ├─ noDevice → PasswordCollector → DataStoreDecision
  │               ├─ true → WebAuthnRegistration
  │               │           ├─ success → RecoveryCodeDisplay → WebAuthnAuth (loop)
  │               │           └─ failure/error/unsupported → ✗ FAILURE
  │               └─ false → ✗ FAILURE
  ├─ recoveryCode → RecoveryCodeCollectorDecision
  │                   ├─ true (valid code) → ✓ SUCCESS
  │                   └─ false (invalid) → ✗ FAILURE
  ├─ failure/error/unsupported → ✗ FAILURE
```

> **Key insight:** The WebAuthn Authentication node has **6 outcomes**, not just
> success/noDevice. The `recoveryCode` outcome is triggered when the user selects
> "Use Recovery Code" via the ConfirmationCallback. See [gotchas](#webauthn-e2e-gotchas)
> for why this matters.

#### Node inventory

| Node Type | UUID | Key Settings |
|-----------|------|-------------|
| UsernameCollectorNode | `2d90dc82-...` | — |
| WebAuthnAuthenticationNode | `f1e03c7e-...` | RP=localhost, origins=[https://localhost:13000], `isRecoveryCodeAllowed:true` |
| PasswordCollectorNode | `3946ca73-...` | — |
| DataStoreDecisionNode | `4d9af624-...` | — |
| WebAuthnRegistrationNode | `7adf255e-...` | RP=localhost, name=Platform Local |
| RecoveryCodeDisplayNode | `7e41a4ca-...` | — |
| RecoveryCodeCollectorDecisionNode | `0efd4461-...` | `recoveryCodeType:OATH` |

#### WebAuthn node configuration

| Setting | Value | Notes |
|---------|-------|-------|
| `relyingPartyDomain` | `localhost` | Must match browser origin domain |
| `relyingPartyName` | `Platform Local` | Display name shown to user |
| `origins` | `["https://localhost:13000"]` | PingAccess engine URL |
| `userVerificationRequirement` | `DISCOURAGED` | Broader device compat for testing |
| `authenticatorAttachment` | `UNSPECIFIED` | Platform or cross-platform |
| `attestationPreference` | `NONE` | No attestation for testing |
| `acceptedSigningAlgorithms` | `["ES256", "RS256"]` | Standard WebAuthn algorithms |
| `timeout` | `60` seconds | WebAuthn ceremony timeout |
| `requiresResidentKey` | `false` | Not requiring discoverable credential |
| `generateRecoveryCodes` | `true` | After registration |

#### Adaptations from webinar reference

| Setting | Webinar | Platform | Reason |
|---------|---------|----------|--------|
| `relyingPartyDomain` | `webinar.local` | `localhost` | Local testing |
| `relyingPartyName` | `webinar.local` | `Platform Local` | Descriptive |
| `origins` | `["https://webinar.local"]` | `["https://localhost:13000"]` | PA proxy URL |
| Tree `_id` | `WebinarJourneyWebAuthN` | `WebAuthnJourney` | Cleaner name |

#### WebAuthn callback structure

When the journey reaches a WebAuthn node (registration or authentication),
AM returns 3–4 callbacks:

| # | Callback Type | Purpose |
|---|--------------|--------|
| 0 | `TextOutputCallback` | JavaScript calling `navigator.credentials.create()` or `.get()` |
| 1 | `TextOutputCallback` | UI helper JavaScript for rendering |
| 2 | `HiddenValueCallback` | Client submits `webAuthnOutcome` (JSON with legacyData) |
| 3 | `ConfirmationCallback` | *(Auth only)* "Use Recovery Code" option |

The `ConfirmationCallback` is only present on **authentication** challenges
(when `isRecoveryCodeAllowed` is true). See [gotchas](#webauthn-e2e-gotchas).

Special values for `webAuthnOutcome`:
- `"unsupported"` — WebAuthn not available in the browser
- `{"error":"..."}` — Error during ceremony (JSON)
- `{"legacyData":"...","authenticatorAttachment":"platform"}` — Success (JSON)

#### webAuthnOutcome `legacyData` format

The `legacyData` field contains raw data separated by `::` delimiters:

**Registration:**
```
clientData :: attestationObjectBytes :: credentialId
```

**Authentication:**
```
clientData :: authenticatorDataBytes :: signatureBytes :: rawId :: userHandle
```

Where:
- `clientData` — raw JSON string (not base64), e.g. `{"type":"webauthn.create","challenge":"...","origin":"...","crossOrigin":false}`
- `attestationObjectBytes` / `authenticatorDataBytes` / `signatureBytes` — comma-separated signed byte values (Int8Array `.toString()` format), e.g. `-93,99,102,...`
- `credentialId` / `rawId` — Base64URL-encoded credential ID
- `userHandle` — username string (`String.fromCharCode` applied to userHandle bytes)

> **Escaping rule:** The `clientData` JSON contains `"` characters. Within the
> `legacyData` JSON string, these must be escaped as `\"`. There must be exactly
> **one level** of escaping — double-escaping (`\\"` or `\\\"`) causes AM to reject
> the attestation with HTTP 401.

#### Import

```bash
# From deployments/platform/
./scripts/import-webauthn-journey.sh
```

See [§10b](#10b-importing-trees-via-rest-api) for the REST API procedure.

#### Verification

```bash
# Start the journey (returns NameCallback for username collection)
curl -s -H "Host: am.platform.local:18080" \
  -X POST "http://127.0.0.1:18080/am/json/authenticate?authIndexType=service&authIndexValue=WebAuthnJourney" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0"
```

#### Test walkthrough (full registration path)

```
Step 1: POST {} → NameCallback (enter username)
Step 2: Submit username → WebAuthn Auth → noDevice → PasswordCallback
Step 3: Submit password → DataStore OK → WebAuthn Registration (3 callbacks)
Step 4: Submit webAuthnOutcome → Registration success → RecoveryCodeDisplay (TextOutputCallback)
Step 5: Echo back → WebAuthn Auth challenge (4 callbacks: 2×TextOutput + HiddenValue + Confirmation)
Step 6: Submit webAuthnOutcome (leave ConfirmationCallback at default!) → ✓ tokenId
```

#### WebAuthn E2E gotchas

##### ConfirmationCallback trap (CRITICAL)

When the WebAuthn Auth node returns a challenge with `isRecoveryCodeAllowed:true`,
callback index 3 is a `ConfirmationCallback` with options `["Use Recovery Code"]`
and default value `100` (not selected).

**Do NOT set this to `0`**. Value `0` selects "Use Recovery Code", which triggers
the `recoveryCode` outcome on the WebAuthn Auth node → `RecoveryCodeCollectorDecisionNode`
→ prompts for a recovery code → infinite loop (no valid recovery code to submit).

The browser's JavaScript works correctly because `loginButton_0.click()` submits
the form with the `HiddenValueCallback` value; it does NOT explicitly set the
`ConfirmationCallback`. The default value `100` means "not selected" and AM
processes the assertion via the `success` outcome.

##### allowCredentials parsing

AM's authentication JavaScript uses `new Int8Array([...])` for credential IDs
within the `allowCredentials` array. When parsing this in a headless client:

1. **Don't use simple regex on the full script** — `allowCredentials:\s*\[...\]`
   fails because `]` inside `Int8Array([...])` matches before the outer `]`.
2. **Use `indexOf` + `substring`** to isolate the `allowCredentials:` section
   (up to the next `};`), then match `Int8Array\(\[([^\]]+)\]\)` within that section.
3. **AM uses quoted keys** — the `allowCredentials` array may contain
   `{"id": new Int8Array([...]), "type": "public-key"}` with quoted keys,
   not bare identifiers. Your regex must handle both formats.

##### Device cleanup for repeatable tests

Users accumulate WebAuthn devices across test runs. To ensure a clean state:

```bash
# Authenticate as admin
ADMIN_TOKEN=$(curl -s -H "Host: am.platform.local:18080" \
  -X POST "http://127.0.0.1:18080/am/json/authenticate" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: resource=2.0,protocol=1.0" \
  -H "X-OpenAM-Username: amAdmin" \
  -H "X-OpenAM-Password: Password1" | python3 -c "import sys,json;print(json.load(sys.stdin)['tokenId'])")

# List devices
curl -s -H "Host: am.platform.local:18080" \
  -H "iplanetDirectoryPro: ${ADMIN_TOKEN}" \
  -H "Accept-API-Version: resource=1.0, protocol=1.0" \
  "http://127.0.0.1:18080/am/json/realms/root/users/user.4/devices/2fa/webauthn?_queryFilter=true"

# Delete each device by UUID
curl -s -H "Host: am.platform.local:18080" \
  -H "iplanetDirectoryPro: ${ADMIN_TOKEN}" \
  -H "Accept-API-Version: resource=1.0, protocol=1.0" \
  -X DELETE "http://127.0.0.1:18080/am/json/realms/root/users/user.4/devices/2fa/webauthn/${DEVICE_UUID}"
```

> **Note:** The device API uses `Accept-API-Version: resource=1.0, protocol=1.0`
> (different from the authentication API's `resource=2.0`).

### 10b. Importing Trees via REST API

#### Endpoints

```
Base: /json/realms/root/realm-config/authentication/authenticationtrees

GET    /trees?_queryFilter=true              List all trees
PUT    /trees/{treeName}                     Create or update tree
DELETE /trees/{treeName}                     Delete tree

PUT    /nodes/{nodeType}/{nodeId}            Create or update node
DELETE /nodes/{nodeType}/{nodeId}            Delete node
```

All require: `Accept-API-Version: protocol=2.0,resource=1.0`

#### Import order (CRITICAL)

**Nodes MUST be created before the tree.** The tree definition references
nodes by UUID. If a referenced node doesn't exist, the tree is created
but broken — invoking it returns `"No Configuration found"`.

AM does **not** validate node references during tree creation. This is a
silent failure trap.

#### Node import

For each node in the journey JSON's `nodes` object:

```bash
# Strip _type, _outcomes, _id, _rev from the body — AM manages these
curl -s -H "Host: am.platform.local:18080" \
  -X PUT "http://127.0.0.1:18080/am/json/realms/root/realm-config/authentication/authenticationtrees/nodes/${nodeType}/${nodeId}" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: protocol=2.0,resource=1.0" \
  -H "iPlanetDirectoryPro: ${TOKEN}" \
  -d "${nodeBodyWithout_type_outcomes_id_rev}"
```

> **Important:** The PUT body must NOT include `_type`, `_outcomes`, `_id`,
> or `_rev`. Adding them may cause validation errors or silent failures.

#### Tree import

```bash
curl -s -H "Host: am.platform.local:18080" \
  -X PUT "http://127.0.0.1:18080/am/json/realms/root/realm-config/authentication/authenticationtrees/trees/${treeName}" \
  -H "Content-Type: application/json" \
  -H "Accept-API-Version: protocol=2.0,resource=1.0" \
  -H "iPlanetDirectoryPro: ${TOKEN}" \
  -d "${treeBody}"
```

The tree body includes `entryNodeId`, node wiring (connections/outcomes),
and UI layout coordinates.

#### Journey JSON format (frodo-compatible)

The journey JSON file is compatible with the `frodo` CLI tool and has
this top-level structure:

```json
{
  "meta":    { "origin": "...", "exportDate": "..." },
  "nodes":   { "<uuid>": { "_type": {...}, ...config... } },
  "tree":    { "_id": "TreeName", "entryNodeId": "<uuid>", "nodes": {...} },
  "scripts": {}, "emailTemplates": {}, "themes": [],
  "innerNodes": {}, "socialIdentityProviders": {},
  "saml2Entities": {}, "circlesOfTrust": {}
}
```

#### frodo CLI (alternative import method)

The `frodo` CLI (`@rockcarver/frodo-cli`) can also import journeys:

```bash
frodo journey import -k -f <journey.json> <connection-profile> <realm>
```

> **Note:** frodo v3 has CLI argument parsing issues — it may print help
> instead of executing. Use environment variables (`FRODO_HOST`,
> `FRODO_USERNAME`, `FRODO_PASSWORD`, `FRODO_AUTHENTICATION_SERVICE`)
> for more reliable operation. For our platform, the custom REST API
> import script (`import-webauthn-journey.sh`) is preferred.

#### Troubleshooting tree imports

| Symptom | Cause | Fix |
|---------|-------|-----|
| `"No Configuration found"` on invocation | Nodes don't exist (curl -sf hid errors) | Re-import with `-s` not `-sf`; verify each response |
| Empty curl response | Missing Host header | Add `-H "Host: am.platform.local:18080"` |
| Node PUT returns 400 | Body includes `_type`/`_outcomes`/`_rev` | Strip those fields — AM manages them |
| Tree PUT succeeds but invocation fails | Node UUIDs in tree don't match imported nodes | Verify UUIDs are identical |
