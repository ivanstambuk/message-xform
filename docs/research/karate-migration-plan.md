# Karate E2E Migration Plan

> **Date**: 2026-02-14
> **Status**: Phases 0-4 Complete — pending live integration verification (P5)
> **Source**: `scripts/pa-e2e-test.sh` (1,702 lines, 76 KB)
> **Target**: `e2e-pingaccess/` Gradle subproject + `scripts/pa-e2e-bootstrap.sh`
> **Decision basis**: `docs/research/e2e-test-framework-research.md`

---

## Architecture

```
scripts/pa-e2e-bootstrap.sh                          ← thin shell: build, docker lifecycle, preflight (RQ-21, RQ-22)
e2e-pingaccess/
├── build.gradle.kts                                 ← Gradle subproject (karate-junit5 dep, Spotless excluded)
└── src/test/java/
    ├── karate-config.js                             ← global config: ports, credentials, env vars (RQ-14)
    ├── logback-test.xml                             ← Karate logging config
    └── e2e/
        ├── PingAccessE2ETest.java                   ← JUnit 5 runner (→ ./gradlew :e2e-pingaccess:test)
        ├── helpers/
        │   ├── basic-auth.js                        ← PA Admin API Basic auth header helper
        │   ├── follow-redirect.feature              ← single-hop redirect follower (RQ-17)
        │   └── oidc-login-form.feature              ← interactive OIDC login form POST
        ├── setup/
        │   ├── pa-provision.feature                 ← full PA provisioning: sites, apps, rules, OAuth (RQ-05, RQ-18)
        │   ├── create-atv.feature                   ← Access Token Validator creation
        │   ├── create-session-app.feature            ← protected session app with ATV
        │   ├── create-virtualhost.feature            ← localhost:3000 fallback VH
        │   └── create-web-session.feature            ← Web Session + Web App for OIDC (RQ-03)
        ├── phase1-plugin/
        │   └── plugin-discovery.feature             ← Tests 1-6: plugin, JAR, SPI (RQ-09, RQ-19, RQ-20)
        ├── phase3-routing/
        │   └── profile-routing.feature              ← Tests 7-8: header injection, multi-spec (RQ-01)
        ├── phase4-context/
        │   └── context-variables.feature            ← Tests 9-11: cookies, queryParams, session null (RQ-06)
        ├── phase5-edge/
        │   └── body-status-edge.feature             ← Tests 12-16: non-JSON, status, URL rewrite (RQ-01)
        ├── phase6-error/
        │   └── error-modes.feature                  ← Tests 17-19: PASS_THROUGH, DENY, guard (RQ-07, RQ-09)
        ├── phase8-oauth/
        │   └── oauth-identity.feature               ← Tests 20-22: session, OAuth, state (RQ-03, RQ-15)
        └── phase8b-websession/
            └── web-session-oidc.feature             ← Tests 23-24: OIDC login flow, L4 (RQ-17, RQ-15)
```

---

## Tasks

### Phase 0 — Gradle and project scaffold
- [x] P0-1: Create `e2e-pingaccess` Gradle subproject with Karate dependency
- [x] P0-2: Add `karate-config.js` with all configuration variables
- [x] P0-3: Create JUnit 5 runner `PingAccessE2ETest.java`
- [x] P0-4: Create `logback-test.xml` for Karate logging
- [x] P0-5: Update `settings.gradle.kts` to include `e2e-pingaccess`

### Phase 1 — Thin bootstrap shell script
- [x] P1-1: Create `scripts/pa-e2e-bootstrap.sh` (preflight, build, docker lifecycle)
- [x] P1-2: Bootstrap starts containers, waits, then delegates to `./gradlew :e2e-pingaccess:test`
- [x] P1-3: Bootstrap runs cleanup on EXIT trap

### Phase 2 — Reusable helper features
- [x] P2-1: `helpers/pa-api.feature` — parameterized PA Admin API caller
- [x] P2-2: `helpers/engine-request.feature` — engine request with header/body/status capture
- [x] P2-3: `helpers/obtain-token.feature` — OAuth token acquisition

### Phase 3 — Setup features (PA provisioning)
- [x] P3-1: `setup/wait-for-pa.feature` — retry loop for PA readiness
- [x] P3-2: `setup/pa-provision.feature` — virtual host, site, rules, apps, resources
- [x] P3-3: `setup/pa-provision-oauth.feature` — third-party service, ATV, session app, web session

### Phase 4 — Test features (port all 24 tests)
- [x] P4-1: `phase1-plugin/plugin-discovery.feature` — Tests 1-6
- [x] P4-2: `phase3-routing/profile-routing.feature` — Tests 7-8
- [x] P4-3: `phase4-context/context-variables.feature` — Tests 9-11
- [x] P4-4: `phase5-edge/body-status-edge.feature` — Tests 12-16
- [x] P4-5: `phase6-error/error-modes.feature` — Tests 17-19
- [x] P4-6: `phase8-oauth/oauth-identity.feature` — Tests 20-22
- [x] P4-7: `phase8b-websession/web-session-oidc.feature` — Tests 23-24

### Phase 5 — Integration and verification
- [ ] P5-1: Run full Karate suite against live PA and verify all tests pass
- [ ] P5-2: Compare Karate test count with original script (44+ assertions)
- [ ] P5-3: Verify JUnit XML report generation
- [ ] P5-4: Update `docs/research/e2e-test-framework-research.md` status to "Migrated"

---

## Requirement Traceability

| RQ | Covered by |
|----|------------|
| RQ-01 | All test features use HTTP + `karate.exec()` |
| RQ-02 | Bootstrap shell script `trap cleanup EXIT` |
| RQ-03 | `pa-provision-oauth.feature` + `oauth-identity.feature` conditional logic |
| RQ-04 | `wait-for-pa.feature` retry loop |
| RQ-05 | `pa-provision.feature` extracts IDs via `response.id` and chains |
| RQ-06 | `engine-request.feature` + `karate-config.js` Host header |
| RQ-07 | `error-modes.feature` soft-assert via `karate.match()` + try/catch |
| RQ-08 | Karate's built-in continue-on-failure + JUnit report |
| RQ-09 | `plugin-discovery.feature` uses `karate.exec()` for logs/JAR |
| RQ-10 | Multiple `.feature` files organized by phase |
| RQ-11 | JUnit 5 runner produces standard XML reports |
| RQ-12 | Same Docker-based local dev model |
| RQ-13 | `karate-config.js` + `Background` + `callonce` |
| RQ-14 | scenario vars / feature `callonce` / `karate-config.js` / `karate.env` |
| RQ-15 | Karate built-in cookie jar |
| RQ-16 | `configure headers` + JS functions for mutation |
| RQ-17 | `web-session-oidc.feature` multi-step redirect with `configure followRedirects = false` |
| RQ-18 | Karate's JS engine for array filtering and JSON manipulation |
| RQ-19 | `karate.exec()` for host-local `javap`, `stat`, `unzip` |
| RQ-20 | JS arithmetic in Karate assertions |
| RQ-21 | Echo server remains in bootstrap shell script |
| RQ-22 | Preflight checks remain in bootstrap shell script |
