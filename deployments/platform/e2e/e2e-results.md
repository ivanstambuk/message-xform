# Platform E2E — Validation Record

| Field | Value |
|-------|-------|
| Runner | `deployments/platform/e2e/run-e2e.sh` (standalone Karate JAR, no Gradle) |
| Config | `karate-config.js` |
| Stack | PingAccess 9.0 + PingAM 8.0.2 + PingDirectory 11.0 |
| Linked requirement | Phase 8 (E2E Authentication Tests) |

> This is a living document. Update it each time the platform E2E suite is run
> against a live stack. Append new entries to the Run History table.

---

## Latest Run

| Field | Value |
|-------|-------|
| Date  | 2026-02-17 |
| Result | **14/14 PASSED** ✅ |
| PA version | 9.0.1.0 |
| PA Docker image | `pingidentity/pingaccess:latest` |
| AM Docker image | Custom build (WAR on Tomcat 10.1.52) |
| PD Docker image | `pingidentity/pingdirectory:11.0.0.1-latest` |
| Features | 6 (+ 1 helper skipped) |
| Total scenarios | 14 |

### Test Breakdown

| # | Feature | Scenario | Assertions | Result |
|---|---------|----------|------------|--------|
| 1 | `auth-login` | Initiate login → clean field prompts + authId in header | 9 | ✅ |
| 2 | `auth-login` | Full login flow → authenticated + session cookie | 8 | ✅ |
| 3 | `auth-login` | Invalid credentials → 401 | 1 | ✅ |
| 4 | `auth-logout` | Auth → logout → session invalid | 3 | ✅ |
| 5 | `auth-passkey` | Full registration + authentication (fresh user) | 10 | ✅ |
| 6 | `auth-passkey` | Auth for user with registered device | 5 | ✅ |
| 7 | `auth-passkey` | Unsupported device → graceful fallback | 1 | ✅ |
| 8 | `auth-passkey-usernameless` | Full usernameless registration + authentication | 12 | ✅ |
| 9 | `auth-passkey-usernameless` | Usernameless auth for user with registered device | 5 | ✅ |
| 10 | `clean-url-login` | Clean URL initiate → transformed field prompts | 9 | ✅ |
| 11 | `clean-url-login` | Clean URL full login → session cookie | 8 | ✅ |
| 12 | `clean-url-login` | Clean URL invalid credentials → 401 | 1 | ✅ |
| 13 | `clean-url-passkey` | Clean URL passkey identifier-first → username prompt | 6 | ✅ |
| 14 | `clean-url-passkey` | Clean URL passkey usernameless → WebAuthn challenge | 7 | ✅ |
| | **Total** | | | **14/14** |

---

## Test Coverage by Feature

### Authentication (raw AM paths through PA)

| Feature File | Scenarios | What it Tests |
|-------------|-----------|---------------|
| `auth-login.feature` | 3 | Username/password login via `/am/json/authenticate`: initiate (field prompts), submit (session cookie), bad creds (401) |
| `auth-logout.feature` | 1 | Session invalidation via `/am/json/sessions/?_action=logout` |
| `auth-passkey.feature` | 3 | WebAuthn identifier-first via raw AM: full reg+auth, device-exists auth, unsupported fallback |
| `auth-passkey-usernameless.feature` | 2 | WebAuthn usernameless via raw AM: full reg+auth (UV+residentKey), discoverable credential entry point |

### Clean URL Routing (request-side URL rewriting)

| Feature File | Scenarios | What it Tests |
|-------------|-----------|---------------|
| `clean-url-login.feature` | 3 | `/api/v1/auth/login` → AM authenticate: initiate, full login with cookie, bad creds |
| `clean-url-passkey.feature` | 2 | `/api/v1/auth/passkey` → WebAuthnJourney NameCallback; `/api/v1/auth/passkey/usernameless` → UsernamelessJourney WebAuthn challenge |

### Helpers (not standalone tests)

| File | Purpose |
|------|---------|
| `helpers/webauthn.js` | Pure JDK FIDO2 authenticator emulator (EC P-256 / ES256) |
| `helpers/delete-device.feature` | AM Admin API device cleanup (called by passkey tests) |
| `helpers/basic-auth.js` | HTTP basic auth helper |

---

## Transform Specs Validated End-to-End

| Spec | Direction | Validated By |
|------|-----------|-------------|
| `am-auth-response-v2@1.0.0` | Response | Tests 1–3, 10–12 |
| `am-webauthn-response@1.0.0` | Response | Tests 5–6, 8–9, 14 |
| `am-strip-internal@1.0.0` | Response | Tests 1–3, 10–12 |
| `am-auth-login-url@1.0.0` | Request | Tests 10–12 |
| `am-passkey-url@1.0.0` | Request | Test 13 |
| `am-passkey-usernameless-url@1.0.0` | Request | Test 14 |

## Profile Validated

| Profile | Version | Spec Count | URL Rewrite Specs | Response Specs |
|---------|---------|------------|-------------------|---------------|
| `platform-am` | 4.0.0 | 6 | 3 (request-side) | 3 (response-side) |

---

## Bugs Found During E2E

| Bug | Root Cause | Fix |
|-----|-----------|-----|
| Profile v4.0.0 failed to load — spec `am-auth-url-rewrite@1.0.0` not found | Profile referenced `am-auth-url-rewrite` but spec `id` field was `am-auth-login-url` (filename ≠ id mismatch) | Fixed profile to `am-auth-login-url@1.0.0` |
| `configure-pa-api.sh` rule attachment silently failed | PA Admin API requires `{"type": "Rule", "id": N}` objects in policy buckets, script was appending bare integers | Fixed to use object format |

---

## Run History

| Date | Result | PA Version | Commit | Notes |
|------|--------|------------|--------|-------|
| 2026-02-17 (prev sessions) | 9/9 ✅ | 9.0.1.0 | various | Auth tests only (raw AM paths, no clean URLs). Clean URL specs existed but were never live-tested. |
| 2026-02-17 15:33 | 14/14 ✅ | 9.0.1.0 | — | First verified clean URL run. Fixed profile ID mismatch + PA rule attachment bug. Added 5 new clean URL scenarios. |
