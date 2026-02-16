# Platform Deployment — Implementation Plan

> Phased plan for standing up PingAccess + PingAM + PingDirectory as a
> 3-container docker-compose deployment with message-xform plugin integration.
> PingDirectory serves as the unified LDAP backend for ALL PingAM needs.

| Field        | Value                    |
|--------------|--------------------------|
| Created      | 2026-02-16               |
| Status       | 🔄 In Progress           |
| Current Step | Phase 2, Step 2.6        |

---

## Product Lineage (for clarity)

| Product | Lineage | Docker Image | Our Version |
|---------|---------|--------------|-------------|
| **PingDirectory** | Ping Identity (UnboundID) | `pingidentity/pingdirectory` | 11.0 |
| **PingAM** | ForgeRock (OpenAM) | Custom build (WAR on Tomcat) | 8.0.2 |
| **PingAccess** | Ping Identity | `pingidentity/pingaccess` | 9.0 |

> Both Ping Identity and ForgeRock are now owned by Thoma Bravo and product lines
> are merging. PingAM 8.0.2 works directly on PingDirectory 11.0 (verified).

---

## Phase Overview

| Phase | Description | Status |
|-------|-------------|--------|
| **1** | [Verify PingDirectory as AM backend](#phase-1--verify-pingdirectory-as-am-backend) | ✅ Done |
| **2** | [Docker Compose skeleton + TLS](#phase-2--docker-compose-skeleton--tls) | ✅ Done |
| **3** | [PingAM initial configuration script](#phase-3--pingam-initial-configuration-script) | ✅ Done |
| **4** | [Username/Password journey](#phase-4--usernamepassword-journey) | ✅ Done |
| **5** | [PingAccess reverse proxy integration](#phase-5--pingaccess-reverse-proxy-integration) | 🔲 Not Started |
| **6** | [WebAuthn / Passkey journeys](#phase-6--webauthn--passkey-journeys) | 🔲 Not Started |
| **7** | [Message-xform plugin wiring](#phase-7--message-xform-plugin-wiring) | 🔲 Not Started |
| **8** | [E2E smoke tests (Karate)](#phase-8--e2e-smoke-tests-karate) | 🔲 Not Started |
| **9** | [Documentation & deployment guide](#phase-9--documentation--deployment-guide) | 🔲 Not Started |

---

## Phase 1 — Verify PingDirectory as AM Backend

**Goal:** Confirm PingDirectory (Ping Identity, not ForgeRock PingDS) can serve
as PingAM's config store, CTS store, and identity store. This is the critical
assumption underpinning the 3-container architecture.

| Step | Task | Status |
|------|------|--------|
| 1.1 | Research PingAM 8.0 docs for PingDirectory compatibility | ✅ Done |
| 1.2 | Check if `pingidentity/pingdirectory` image supports AM setup profiles | ✅ Done |
| 1.3 | Test: start PingDirectory container, inspect available schemas | ⏭️ Skipped (not needed for decision) |
| 1.4 | Document findings — confirm or reject 3-container architecture | ✅ Done |

**Risk:** If PingDirectory cannot serve as AM's config/CTS store, we fall back
to a 4-container architecture (PingAccess + PingAM + PingDS + PingDirectory)
similar to the webinar reference.

### Step 1.1 Findings

PingAM 8.0 docs state the following store requirements:

| Store | Requirement |
|-------|-------------|
| **Configuration** | PingDS **or** file-based config (FBC, new in AM 8.0) |
| **CTS tokens** | Must be PingDS |
| **Policy store** | Must be PingDS |
| **Application store** | Must be PingDS |
| **UMA store** | Must be PingDS |
| **Identity/User store** | Any LDAPv3 — PingDirectory, AD, Oracle, etc. |

Critical observations:
1. The Ping docs use "PingDS" ambiguously — sometimes meaning "ForgeRock DS",
   sometimes meaning "PingDirectory". Need practical verification.
2. **File-based config (FBC)** is a new option in AM 8.0 — config stored in JSON
   files on the filesystem instead of LDAP. This could eliminate the config store
   dependency on PingDS entirely.
3. The `pingidentity/pingdirectory` Docker image supports **server profiles**
   via `SERVER_PROFILE_URL` / `SERVER_PROFILE_PATH` env vars. These can include
   LDIF files for am-config/am-cts schema, which may make PingDirectory usable
   as the CTS/policy store.
4. The webinar reference uses PingDS for AM internals and PingDirectory for
   user data — this is the proven pattern.

**Decision required:** Two viable architectures:
- **Option A (3 containers):** PingAM with FBC + PingDirectory (user/CTS store)
  — requires AM 8.0 FBC + verifying PD can host CTS/policy data
- **Option B (4 containers):** PingAM + PingDS (config/CTS) + PingDirectory
  (user store) + PingAccess — proven pattern from webinar
- **Option C (3 containers, PingDS only):** PingAM + PingDS (config/CTS/identity)
  + PingAccess — simplest, but uses PingDS instead of PingDirectory for users

→ Proceeding with Step 1.2 to verify `pingidentity/pingdirectory` image capabilities.

### Step 1.2 Findings

The `pingidentity/pingdirectory:11.0.0.1-latest` Docker image:
- Uses `SERVER_PROFILE_URL`/`SERVER_PROFILE_PATH` for configuration
- Uses `pd.profile` (PingDirectory profile), **not** `--profile am-config` (PingDS syntax)
- Has `USER_BASE_DN=dc=example,dc=com`, `ROOT_USER_DN=cn=administrator`
- Supports `MAKELDIF_USERS` for auto-generating test users
- Full TLS support via env vars

PingDS (`opendj/setup`) and PingDirectory use **different setup mechanisms**:
- PingDS: `--profile am-config --profile am-cts --profile am-identity-store`
- PingDirectory: `pd.profile` directory structure with LDIF files

While PingDirectory *could* theoretically host AM data via custom LDIF schema
imports, this is untested and fragile. The `--profile am-config` etc. are PingDS
specific setup profiles that create the exact schema, indexes, and bind accounts
that PingAM expects.

### Step 1.4 — Architectural Decision (REVISED)

**Decision: 3-container architecture** (PingAccess + PingAM + PingDirectory)

**⚠️ Original decision (D4) was WRONG.** Live testing on Feb 16, 2026 proved that
PingAM 8.0.2 runs directly on PingDirectory 11.0 without PingDS. Two tweaks needed:

1. **Schema relaxation**: `single-structural-objectclass-behavior: accept`
   PingAM writes LDAP entries without structural objectClasses that PD rejects.
2. **ETag virtual attribute**: PingAM CTS expects `etag` attribute for optimistic
   concurrency. PD doesn't have it natively but `ds-entry-checksum` serves same
   purpose. A mirror virtual attribute maps `ds-entry-checksum → etag`.

**Test evidence:**
- PingAM configurator loaded 98+ schema files into PD (all successful)
- CTS token store created at `ou=tokens,dc=example,dc=com` (13 entries)
- PD access log showed 4000+ operations from AM (all resultCode=0)
- Admin authentication returned valid `tokenId` and `iPlanetDirectoryPro` cookie

```
 Browser ──▶ PingAccess (9.0) ──▶ PingAM (8.0.2) ──▶ PingDirectory (11.0)
            (reverse proxy)      (authentication)    (unified store)
            + message-xform      (journeys, OAuth2)  config + CTS +
            port 3000                                 users
                                                     port 1636
```

---

## Phase 2 — Docker Compose Skeleton + TLS

**Goal:** Create docker-compose.yml with all 3 containers starting, TLS keypair
generation, and the `.env` template.

| Step | Task | Status |
|------|------|--------|
| 2.1 | Create `.env.template` with all variables | ✅ Done |
| 2.2 | Create `scripts/generate-keys.sh` (self-signed + enterprise extension points) | ✅ Done |
| 2.3 | Create `docker-compose.yml` (PingDirectory + PingAM + PingAccess) | ✅ Done |
| 2.4 | Create Tomcat `server.xml` with HTTPS connector | ✅ Done |
| 2.5 | Create PD post-setup dsconfig (schema relaxation + etag VA) | ✅ Done |
| 2.6 | Create PD etag schema LDIF | ✅ Done |
| 2.7 | Add `secrets/` to `.gitignore` | ✅ Done |
| 2.8 | Test: `docker compose up` — all 3 containers start cleanly | ✅ Done |

---

## Phase 3 — PingAM Initial Configuration Script

**Goal:** Shell script that automates PingAM's first-run setup via REST API
(equivalent to the webinar's Java `Main.configurePingAM()`).

| Step | Task | Status |
|------|------|--------|
| 3.1 | Create `scripts/configure-am.sh` — call `/config/configurator` endpoint | ✅ Done |
| 3.2 | Configure AM to use PingDirectory for config store | ✅ Done (single PD, one configurator POST) |
| 3.3 | Configure AM to use PingDirectory for CTS store | ✅ Done (single PD, one configurator POST) |
| 3.4 | Configure AM to use PingDirectory for identity store | ✅ Done (single PD, one configurator POST) |
| 3.5 | Create realm, configure cookie domain, session settings | ✅ Done (cookie=platform.local, base_dir=/home/forgerock/openam) |
| 3.6 | Test: run script, verify AM admin console accessible | ✅ Done (98 steps, 0 errors, tokenId returned) |

**Reference:** `webinar-pingfed-pingam/src/.../Main.java` → `configurePingAM()`

---

## Phase 4 — Username/Password Journey

**Goal:** Import a simple username/password authentication tree into PingAM,
create test users in PingDirectory, verify login works.

| Step | Task | Status |
|------|------|--------|
| 4.1 | Create test users in PingDirectory (user.1–user.10) | ✅ Done (config/test-users.ldif) |
| 4.2 | Import username/password journey JSON | ✅ Done (built-in `ldapService` tree — no custom import needed) |
| 4.3 | Create `scripts/configure-am-post.sh` (users + ZeroPageLogin + verify) | ✅ Done |
| 4.4 | Test: authenticate as user.1 via PingAM REST API | ✅ Done (tokenId returned, ZeroPageLogin enabled) |

---

## Phase 5 — PingAccess Reverse Proxy Integration

**Goal:** Configure PingAccess to reverse-proxy requests to PingAM. All AM
endpoints accessible through PingAccess on a clean external port.

| Step | Task | Status |
|------|------|--------|
| 5.1 | Configure PA site pointing to PingAM backend | 🔲 Not Started |
| 5.2 | Configure PA application with context `/am` | 🔲 Not Started |
| 5.3 | Test: access PingAM login via PingAccess address | 🔲 Not Started |
| 5.4 | Mount message-xform plugin JAR (no transformation rules yet) | 🔲 Not Started |

---

## Phase 6 — WebAuthn / Passkey Journeys

**Goal:** Import WebAuthn journeys supporting passkeys with identifier-first
and usernameless flows.

| Step | Task | Status |
|------|------|--------|
| 6.1 | Adapt webinar's `WebinarJourneyWebAuthN.journey.json` | 🔲 Not Started |
| 6.2 | Create identifier-first WebAuthn journey | 🔲 Not Started |
| 6.3 | Create usernameless WebAuthn journey | 🔲 Not Started |
| 6.4 | Import journeys via script | 🔲 Not Started |
| 6.5 | Test: register and authenticate with passkey | 🔲 Not Started |

---

## Phase 7 — Message-xform Plugin Wiring

**Goal:** Configure message-xform transformation specs for PingAM's
`/json/authenticate` endpoint to present a cleaner API surface.

| Step | Task | Status |
|------|------|--------|
| 7.1 | Create transformation spec for authentication endpoint | 🔲 Not Started |
| 7.2 | Configure PingAccess rule with the spec | 🔲 Not Started |
| 7.3 | Test: send clean JSON, receive transformed response | 🔲 Not Started |

---

## Phase 8 — E2E Smoke Tests (Karate)

**Goal:** Automated end-to-end tests validating the full stack.

| Step | Task | Status |
|------|------|--------|
| 8.1 | Create Karate test project structure | 🔲 Not Started |
| 8.2 | Test: PingDirectory health check | 🔲 Not Started |
| 8.3 | Test: PingAM login via PingAccess | 🔲 Not Started |
| 8.4 | Test: Transformed API surface | 🔲 Not Started |

---

## Phase 9 — Documentation & Deployment Guide

**Goal:** Comprehensive deployment guide and manifest updates.

| Step | Task | Status |
|------|------|--------|
| 9.1 | Create `docs/operations/platform-deployment-guide.md` | 🔲 Not Started |
| 9.2 | Update `llms.txt` and `knowledge-map.md` | 🔲 Not Started |
| 9.3 | Final review and commit | 🔲 Not Started |

---

## Decision Log

| # | Decision | Rationale | Date |
|---|----------|-----------|------|
| D1 | Use PingDirectory for ALL AM stores | Live testing proved PingAM 8.0.2 works on PingDirectory 11.0 with schema relaxation + etag VA. Eliminates PingDS. | 2026-02-16 |
| D2 | Shell scripts for configuration | Preferred over Java (webinar approach). More accessible, no build step required. | 2026-02-16 |
| D3 | Self-signed TLS with enterprise extension points | Dev-friendly default with clear hooks for custom CA certs, keystores, and trust chains in production. | 2026-02-16 |
| ~~D4~~ | ~~4-container architecture~~ | **SUPERSEDED** — live testing proved 3 containers work. PingDS not needed. | 2026-02-16 |
| D5 | 3-container architecture (PA + AM + PD) | PingDirectory serves config, CTS, AND user store. Two PD tweaks required: `single-structural-objectclass-behavior:accept` + `etag` mirror VA. | 2026-02-16 |
