# Platform Deployment — Implementation Plan

> Phased plan for standing up PingAccess + PingAM + PingDirectory as a
> 3-container docker-compose deployment with message-xform plugin integration.
> PingDirectory serves as the unified LDAP backend for ALL PingAM needs.

| Field        | Value                    |
|--------------|--------------------------|
| Created      | 2026-02-16               |
| Status       | ✅ All phases complete |
| Current Step | All phases done — 8/8 E2E test groups passing |

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
| **5** | [PingAccess reverse proxy integration](#phase-5--pingaccess-reverse-proxy-integration) | ✅ Done |
| **6** | [WebAuthn / Passkey journeys](#phase-6--webauthn--passkey-journeys) | ✅ Done (identifier-first + usernameless) |
| **7** | [Message-xform plugin wiring](#phase-7--message-xform-plugin-wiring) | ✅ Done |
| **8** | [E2E smoke tests (Karate)](#phase-8--e2e-smoke-tests-karate) | ✅ Done (8/8 steps, all pass) |
| **9** | [Documentation & deployment guide](#phase-9--documentation--deployment-guide) | ✅ Done |

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

**Goal:** Verify the built-in `ldapService` authentication tree works,
create test users in PingDirectory, verify callback-based login works.

| Step | Task | Status |
|------|------|--------|
| 4.1 | Create test users in PingDirectory (user.1–user.10) | ✅ Done (config/test-users.ldif) |
| 4.2 | Verify built-in `ldapService` tree (no custom import needed) | ✅ Done |
| 4.3 | Create `scripts/configure-am-post.sh` (users + callback auth verify) | ✅ Done |
| 4.4 | Test: authenticate as user.1 via callback-based REST API | ✅ Done |
| 4.5 | Switch from ZeroPageLogin to callback-based auth (security hardening) | ✅ Done |

---

## Phase 5 — PingAccess Reverse Proxy Integration

**Goal:** Configure PingAccess to reverse-proxy requests to PingAM. All AM
endpoints accessible through PingAccess on a clean external port.

| Step | Task | Status |
|------|------|--------|
| 5.1 | Configure PA site pointing to PingAM backend | ✅ Done (am.platform.local:8080, HTTP) |
| 5.2 | Configure PA application with context `/am` | ✅ Done (Web type, unprotected, enabled) |
| 5.3 | Test: access PingAM login via PingAccess address | ✅ Done (callback auth via PA returns tokenId) |
| 5.4 | Mount message-xform plugin JAR (no transformation rules yet) | 🔲 Deferred to Phase 7 |

---

## Phase 6 — WebAuthn / Passkey Journeys

**Goal:** Import WebAuthn journeys supporting passkeys with identifier-first
and usernameless flows.

| Step | Task | Status |
|------|------|--------|
| 6.1 | Adapt webinar's `WebinarJourneyWebAuthN.journey.json` | ✅ Done (RP→localhost, origins→https://localhost:13000) |
| 6.2 | Create identifier-first WebAuthn journey | ✅ Done (`WebAuthnJourney.journey.json` — UsernameCollector→PasswordCollector→DataStore→Registration→Auth) |
| 6.3 | Create usernameless WebAuthn journey | ✅ Done (`UsernamelessJourney.journey.json` — starts at WebAuthn Auth, error→UsernameCollector fallback, UV=REQUIRED, residentKey=REQUIRED) |
| 6.4 | Import journeys via script | ✅ Done (`import-webauthn-journey.sh` — REST API, not frodo) |
| 6.5 | Test: register and authenticate with passkey | ✅ Verified to headless limit (FIDO2 JS callbacks returned, browser ceremony requires authenticator) |

---

## Phase 7 — Message-xform Plugin Wiring

**Goal:** Configure message-xform transformation specs for PingAM's
`/json/authenticate` endpoint to present a cleaner API surface.

| Step | Task | Status |
|------|------|--------|
| 7.1 | Build shadow JAR | ✅ Done (`adapter-pingaccess-0.1.0-SNAPSHOT.jar`, 4.7MB) |
| 7.2 | Mount JAR + specs + profiles into PA container | ✅ Done (docker-compose volumes) |
| 7.3 | Create `am-auth-response` spec (body transform) | ✅ Done (tokenId→token, +authenticated, successUrl→redirectUrl) |
| 7.4 | Create `am-header-inject` spec (header injection) | ✅ Done (X-Auth-Provider, X-Transform-Engine) |
| 7.5 | Create `platform-am` profile (routes specs to AM paths) | ✅ Done |
| 7.6 | Create `configure-pa-plugin.sh` (rule + policy binding) | ✅ Done |
| 7.7 | Test: body transform on auth success | ✅ Verified (token/authenticated/redirectUrl/realm) |
| 7.8 | Test: header injection on all AM responses | ✅ Verified (x-auth-provider: PingAM) |
| 7.9 | Test: callback passthrough (JSLT guard) | ✅ Verified (non-success responses pass through) |
| 7.10 | Test: hot-reload (30s interval in logs) | ✅ Verified |

---

## Phase 8 — E2E Authentication Tests (Karate)

**Goal:** Comprehensive end-to-end tests covering all authentication flows
(username/password, passkey identifier-first, passkey usernameless) with a
clean REST API surface. Tests run via standalone Karate runner in
`deployments/platform/e2e/`. Uses `openauth-sim` for FIDO2 ceremony emulation.

### Architecture Overview

```
                       ┌──────────────────────────────────┐
                       │        Karate E2E Runner          │
                       │  deployments/platform/e2e/        │
                       │  (standalone JAR / script)         │
                       └──────────┬───────────────────────┘
                                  │  HTTP
                                  ▼
  ┌─────────────────────────────────────────────────────────┐
  │              PingAccess (port 3000)                      │
  │  + message-xform plugin (body transforms + header inject)│
  │  Routes:                                                 │
  │    /api/v1/auth/login       → AM ldapService             │
  │    /api/v1/auth/passkey     → AM WebAuthnJourney         │
  │    /api/v1/auth/passkey/usernameless → AM Usernameless   │
  │    /api/v1/auth/logout      → AM /sessions/?_action=...  │
  └─────────────────┬───────────────────────────────────────┘
                    │  HTTP
                    ▼
  ┌─────────────────────────────────────────────────────────┐
  │               PingAM (port 8080)                         │
  │  Journeys: ldapService, WebAuthnJourney, Usernameless    │
  │  OAuth2/OIDC provider (future)                           │
  └─────────────────┬───────────────────────────────────────┘
                    │  LDAPS
                    ▼
  ┌─────────────────────────────────────────────────────────┐
  │            PingDirectory (port 1636)                     │
  │  Users: user.1–user.10                                   │
  └─────────────────────────────────────────────────────────┘

  ┌───────────────────────────────────────────────────────────┐
  │  openauth-sim (sibling project, REST API)                  │
  │  /api/v1/webauthn/attest   — generate attestation          │
  │  /api/v1/webauthn/evaluate — generate assertion            │
  │  Used by Karate to emulate FIDO2 ceremony programmatically │
  └───────────────────────────────────────────────────────────┘
```

### API Surface

All endpoints are fronted by PingAccess. Message-xform transforms both
request (clean JSON → AM callbacks) and response (AM callbacks → clean JSON)
at each step. The client **never** sees PingAM's raw callback format.

| Endpoint | Method | AM Backend Journey | Description |
|----------|--------|--------------------|-------------|
| `/api/v1/auth/login` | POST | `ldapService` | Username/password authentication |
| `/api/v1/auth/passkey` | POST | `WebAuthnJourney` | Passkey — identifier-first (username provided) |
| `/api/v1/auth/passkey/usernameless` | POST | `UsernamelessJourney` | Passkey — usernameless (discoverable credential) |
| `/api/v1/auth/logout` | POST | `/sessions/?_action=logout` | Session invalidation |

### Transformed JSON — Login Flow

**Step 1 — Initiate (empty POST):**
```
POST /api/v1/auth/login  →  {}
```
Response (transformed from AM's NameCallback + PasswordCallback):
```json
{
  "fields": [
    { "name": "username", "type": "text", "prompt": "User Name" },
    { "name": "password", "type": "password", "prompt": "Password" }
  ]
}
```
Response header: `X-Auth-Session: eyJ0...` (the AM authId JWT)

**Step 2 — Submit credentials:**
```
POST /api/v1/auth/login
X-Auth-Session: eyJ0...
Body: { "username": "user.1", "password": "Password1" }
```
Request transform: converts clean JSON + header → AM callback format (authId + callbacks[]).
Response transform: strips AM tokenId from body, injects as cookie.
```json
{
  "authenticated": true,
  "realm": "/"
}
```
Response headers:
- `Set-Cookie: iPlanetDirectoryPro=AQIC5w...; Path=/; HttpOnly; Secure`
- `X-Auth-Provider: PingAM`

**Session token strategy (D14):** The AM SSO token (`tokenId`) is **not**
returned in the response body. Instead, message-xform injects it into a
`Set-Cookie` header. Subsequent requests carry this cookie automatically.
The client never sees or handles the raw AM token.

### Transformed JSON — Passkey Flow (Identifier-First)

**Step 1 — Initiate with username:**
```
POST /api/v1/auth/passkey
Body: { "username": "user.1" }
```
Response (transformed from WebAuthnAuthenticationNode challenge):
```json
{
  "challenge": "<base64url-encoded challenge>",
  "rpId": "localhost",
  "allowCredentials": [ ... ],
  "userVerification": "preferred"
}
```
Response header: `X-Auth-Session: eyJ0...`

**Step 2 — Submit WebAuthn assertion (generated by openauth-sim):**
```
POST /api/v1/auth/passkey
X-Auth-Session: eyJ0...
Body: { "assertion": "<base64url-encoded assertion response>" }
```
Response: same as login success (authenticated + cookie).

### Transformed JSON — Passkey Flow (Usernameless)

**Step 1 — Initiate (empty POST):**
```
POST /api/v1/auth/passkey/usernameless  →  {}
```
Response (WebAuthn challenge, no allowCredentials — requires discoverable credential):
```json
{
  "challenge": "<base64url-encoded challenge>",
  "rpId": "localhost",
  "userVerification": "required"
}
```
Response header: `X-Auth-Session: eyJ0...`

**Step 2** — Same as identifier-first step 2.

### Passkey Design Notes

- **Default passkey flow is usernameless** — this is the modern standard
  (discoverable credentials, no username prompt)
- **Identifier-first is the secondary variant** — username provided, server
  sends `allowCredentials` list targeting that user's registered devices
- Existing `WebAuthnJourney` serves as the identifier-first flow (starts with
  `UsernameCollectorNode` → `WebAuthnAuthenticationNode`)
- New `UsernamelessJourney` needed: starts directly at
  `WebAuthnAuthenticationNode` with `requiresResidentKey=true`, no username node
- Two separate PA endpoints (not body-based routing) for simplicity (D13)
- `openauth-sim` REST API generates attestations and assertions
  programmatically, enabling full FIDO2 ceremony emulation without a browser

### Steps

| Step | Task | Status |
|------|------|--------|
| 8.1 | Create `deployments/platform/e2e/` directory structure | ✅ Done (flat layout, `.gitignore` for JAR) |
| 8.2 | Create standalone Karate runner (`run-e2e.sh` + auto-download JAR) | ✅ Done (no Gradle submodule — D10) |
| 8.3 | Create `karate-config.js` with platform env vars | ✅ Done (corrected ports: PA=13000, AM=18080) |
| 8.4 | Create `UsernamelessJourney` + generic import script | ✅ Done (journey JSON + `import-journey.sh`, `requiresResidentKey=true`, UV=REQUIRED) |
| 8.5 | Create message-xform specs for login flow | ✅ Done (`am-auth-response-v2` + `am-strip-internal`, profile chaining ADR-0008, JSLT validated) |
| 8.6 | Create message-xform specs for passkey flows | ✅ Done (`am-webauthn-response` — JSLT `capture()` regex extracts challenge/rpId/UV/timeout from AM JS; profile `match.when` body predicate routes WebAuthn vs login callbacks per ADR-0036; raw challenge bytes passthrough for client-side ArrayBuffer construction) |
| 8.7 | Create message-xform spec for session cookie injection (D14) | ✅ Done (merged into `am-auth-response-v2` dynamic header expr) |
| 8.8 | Configure PA applications/sites for auth endpoints | ✅ Done (PA `/api` application + 3 request-side URL rewrite specs: `am-auth-url-rewrite`, `am-passkey-url`, `am-passkey-usernameless-url`; profile v4.0.0 maps `/api/v1/auth/*` → `/am/json/authenticate` with journey selection; `configure-pa-api.sh` creates PA app + attaches transform rule) |
| 8.9 | E2E test: username/password login (happy path) | ✅ Done (3 scenarios: initiate, submit+cookie, bad-creds=401) |
| 8.10 | E2E test: passkey identifier-first (register + authenticate) | ✅ Done (3 scenarios in `auth-passkey.feature`: full reg+auth, device-exists auth, unsupported fallback) |
| 8.11 | E2E test: passkey usernameless (register + authenticate) | ✅ Done (2 scenarios: full reg+auth with UV+residentKey, discoverable credential entry point check) |
| 8.12 | E2E test: logout | ✅ Done (1 scenario: auth→logout→validate=false) |

### Prerequisites

- Platform stack running (`docker compose up`)
- `openauth-sim` running or accessible (for passkey tests — see D11)
- Test users provisioned (user.1–user.10 in PingDirectory)
- Phase 6.3 complete (usernameless journey imported)
- PD cert imported into AM's JVM truststore (lost on container recreation — see [PingAM Ops Guide](../../docs/operations/pingam-operations-guide.md#ssl-certificate-trust))

### Implementation Notes for Steps 8.10/8.11 (Passkey E2E)

Karate is HTTP-level only — it cannot execute browser JS or interact with FIDO2
authenticators. The passkey E2E tests require a **WebAuthn authenticator simulator**
that exposes a REST API for generating FIDO2 attestations and assertions.

**Decision D11 (REVISED):** Use embedded `webauthn.js` Karate helper with Java crypto
(`java.security.KeyPairGenerator`, `java.security.Signature`, `javax.crypto.Mac`).
No external dependency on `openauth-sim`. The helper generates attestations and
assertions programmatically using ES256 (P-256 ECDSA) keys.

**FIDO2 ceremony in Karate (pseudocode for 8.10):**
```
1. POST /am/json/authenticate?authIndexType=service&authIndexValue=WebAuthnJourney
   → Get NameCallback (username prompt)
2. Submit username → Get WebAuthnRegistrationNode callbacks:
   - HiddenValueCallback (challenge, rpId, user info, pubKeyCredParams)
   - MetadataCallback (WebAuthn registration metadata)
3. Parse challenge data from HiddenValueCallback.value
4. POST to openauth-sim /api/v1/webauthn/attest with rpId + challenge
   → Get attestationObject + clientDataJSON
5. Submit attestation back to AM via callback
   → Registration success
6. Start auth journey with same user
   → Get WebAuthnAuthenticationNode callbacks (challenge + allowCredentials)
7. POST to openauth-sim /api/v1/webauthn/evaluate with challenge
   → Get authenticatorData + signature + clientDataJSON
8. Submit assertion back to AM
   → Authentication success (tokenId)
```

**Key gotchas to remember:**
- Clear Karate cookie jar (`* configure cookies = null`) between AM-direct and
  PA-proxied calls to avoid domain mismatch (platform.local vs localhost:3000)
- Custom headers from message-xform are lowercase (`x-auth-session`, not `X-Auth-Session`)
- Standard HTTP headers preserve original casing (`Set-Cookie`, not `set-cookie`)
- WebAuthn callbacks in AM use `HiddenValueCallback` with JSON-encoded challenge data
- The `authId` JWT in the `X-Auth-Session` response header must be echoed back in
  the request body (request direction is NOT transformed — D9)

---

## Phase 9 — Documentation & Deployment Guide

**Goal:** Comprehensive deployment guide and manifest updates.

| Step | Task | Status |
|------|------|--------|
| 9.1 | Create `docs/operations/platform-deployment-guide.md` | ✅ Done (1042 lines) |
| 9.2 | Update PingAM ops guide with REST API gotchas + WebAuthn sections | ✅ Done (§8b + §10 + §10b) |
| 9.3 | Update `llms.txt` and `knowledge-map.md` | ✅ Done (platform E2E section added to `llms.txt`; `knowledge-map.md` updated with passkey tests + helpers) |
| 9.4 | Final review and commit | ✅ Done (added §13 E2E Test Suite + passkey troubleshooting to platform deployment guide) |

---

## Decision Log

| # | Decision | Rationale | Date |
|---|----------|-----------|------|
| D1 | Use PingDirectory for ALL AM stores | Live testing proved PingAM 8.0.2 works on PingDirectory 11.0 with schema relaxation + etag VA. Eliminates PingDS. | 2026-02-16 |
| D2 | Shell scripts for configuration | Preferred over Java (webinar approach). More accessible, no build step required. | 2026-02-16 |
| D3 | Self-signed TLS with enterprise extension points | Dev-friendly default with clear hooks for custom CA certs, keystores, and trust chains in production. | 2026-02-16 |
| ~~D4~~ | ~~4-container architecture~~ | **SUPERSEDED** — live testing proved 3 containers work. PingDS not needed. | 2026-02-16 |
| D5 | 3-container architecture (PA + AM + PD) | PingDirectory serves config, CTS, AND user store. Two PD tweaks required: `single-structural-objectclass-behavior:accept` + `etag` mirror VA. | 2026-02-16 |
| D6 | REST API import over frodo CLI | frodo v3 has argument parsing issues; custom bash script using AM REST API gives full control over Host header, error handling, and import order. | 2026-02-16 |
| D7 | Callback auth everywhere (no ZeroPageLogin) | AM 8.0 defaults ZPL to disabled for Login CSRF protection. Callback pattern handles all journey types including WebAuthn. | 2026-02-16 |
| D8 | Docker Compose v2 | Installed `docker-compose-v2` (2.37.1) — the v1 `docker-compose` (1.29.2) has `ContainerConfig` KeyError bugs with modern images. Use `docker compose` (space). | 2026-02-16 |
| D9 | Response-only transforms for AM auth | Request-side transforms break AM's callback protocol (authId JWT must be echoed verbatim). Response-only body cleanup + header injection is the practical pattern. | 2026-02-16 |
| D10 | Platform E2E tests in `deployments/platform/e2e/` — standalone Karate JAR | **No Gradle submodule.** Run via `java -jar karate.jar` or `run-e2e.sh`. Platform stack is manually managed (`docker compose`), Gradle Docker lifecycle makes no sense. Clean separation from adapter E2E tests (`e2e-pingaccess/`). | 2026-02-16 |
| D11 | Embedded `webauthn.js` as FIDO2 authenticator emulator | **REVISED** — instead of external `openauth-sim`, built an embedded Karate helper (`helpers/webauthn.js`) using Java crypto (ES256 P-256 ECDSA). Zero external dependencies. Generates attestations + assertions programmatically. Key insight: AM stores `user.id` as `Uint8Array.from(base64(username))`, so usernameless assertion `userHandle` must be the base64 of the username. | 2026-02-17 |
| D12 | Two passkey varieties (identifier-first + usernameless) | Identifier-first = existing `WebAuthnJourney` (username → allowCredentials). Usernameless = new `UsernamelessJourney` (discoverable credential, no username). Usernameless is the modern default. | 2026-02-16 |
| D13 | Single PA Application (`/api`) + request-side URL rewriting | One PA Application at `/api`, message-xform request-side specs rewrite `/api/v1/auth/login` → `/am/json/authenticate`, `/api/v1/auth/passkey` → `+ WebAuthnJourney`, `/api/v1/auth/passkey/usernameless` → `+ UsernamelessJourney`. Body passes through unchanged (identity transform). Response-side transforms see the rewritten path and match normally. `configure-pa-api.sh` creates the PA app. | 2026-02-17 |
| D14 | AM SSO token hidden via cookie injection (hybrid session) | Message-xform strips `tokenId` from response body, injects it into `Set-Cookie: iPlanetDirectoryPro=...` header. Client never sees raw AM token. Full PA Web Sessions (OIDC auth code flow) deferred — requires AM OAuth2 provider + PA OIDC config. Option 3 (pragmatic). | 2026-02-16 |
| D15 | `authId` in response header, not body | `X-Auth-Session` response header carries AM's `authId` JWT. Keeps the response body clean (fields only). Client echoes this header on subsequent callback submissions. Message-xform header transforms handle the mapping. | 2026-02-16 |
| ~~D16~~ | ~~PingFederate `pi.flow` equivalent~~ | **NOT AVAILABLE** — PingAM does not support `response_mode=pi.flow`. Supported modes: `fragment`, `jwt`, `form_post`, `query` variants only. Hybrid session (D14) is the pragmatic alternative. | 2026-02-16 |
