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

PingAM **requires an external directory server** (PingDS) for its configuration
store, user store, CTS, and policy store. The setup wizard will prompt for DS
connection details.

**Typical startup log** (abbreviated):
```
Base Directory: /home/forgerock/openam
Application Context: /am
Deployment of web application directory [.../webapps/am] has finished in [6,243] ms
Starting ProtocolHandler ["http-nio-8080"]
Server startup in [6276] milliseconds
```

#### With a PingDS backend

For a functional AM instance, you need PingDS running first:

```bash
# Start PingDS first (demo mode)
docker run -d --name pingds \
  -e USE_DEMO_KEYSTORE_AND_PASSWORDS=true \
  -p 1389:1389 -p 1636:1636 \
  pingds:8.0.2 start-ds

# Then start PingAM
docker run -d --name pingam \
  -p 8080:8080 \
  --link pingds:ds.example.com \
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
| Backend dependency | PingDS required for configuration/user stores |

### Key differences from PingGateway and PingDS

| Aspect | PingGateway | PingDS | PingAM |
|--------|-------------|--------|--------|
| Vendor Dockerfile | ✅ Works | ✅ Works | ❌ Private CI deps |
| Our Dockerfile | Vendor-supplied | Vendor-supplied | **Custom** (WAR on Tomcat) |
| Base image | `gcr.io/.../java-21` | `gcr.io/.../java-21` | `tomcat:10.1-jdk21` |
| Image size | 327 MB | 243 MB | 1.31 GB |
| First-run | Welcome page (200) | Server ready (logs) | Setup wizard (302) |
| External deps | None | Keystore (or demo) | PingDS backend |
| Docker Hub image | None | None | None |
