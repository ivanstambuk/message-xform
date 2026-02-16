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

> **Key insight:** Both config store and user store point to the **same**
> PingDirectory instance. PingAM creates its own subtrees
> (`ou=services`, `ou=tokens`, etc.) under the base DN.

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
