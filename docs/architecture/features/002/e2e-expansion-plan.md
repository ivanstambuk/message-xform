# E2E Test Expansion Plan — Feature 002

_Created:_ 2026-02-14
_Status:_ Draft
_Linked:_ `e2e-results.md`, FR-002-12, `scripts/pa-e2e-test.sh`

## Objective

Expand the E2E test suite from smoke-level (8 scenarios) to comprehensive
coverage of all scenarios that exercise **live PA behavior** — things mocks
cannot fully substitute.

**Current state:** 18/18 assertions across 8 scenarios.
**Target state:** ~13 additional scenarios validated E2E → 21 total.
**Projected assertions:** ~44 total (18 existing + ~26 new).

---

## Architecture Decisions

### A1: Profile-based routing (replaces "first spec wins" fallback)

The engine's no-profile fallback picks a single arbitrary spec for all
requests (line 462, `TransformEngine.java`). To test multiple specs,
header injection, and error paths via separate routes, the E2E setup must
switch to **profile-based routing**.

- Create `e2e/pingaccess/profiles/e2e-profile.yaml`
- Update the PA rule config: `profilesDir=/profiles`, `activeProfile=e2e-profile`
- Mount the profiles directory into the PA container
- Each spec gets its own path prefix in the profile

### A2: Two PA rules for error mode testing

Error mode is a per-rule configuration. Testing both `PASS_THROUGH` and
`DENY` requires two separate PA rule instances:

- **Rule 1 (existing):** `errorMode=PASS_THROUGH` — applied to the main app
- **Rule 2 (new):** `errorMode=DENY` — applied to a second app (`/deny-api`)

Both apps point to the same echo backend site and use the same profile.

> **Path routing note:** The DENY app has `contextRoot=/deny-api`, so request
> paths arrive as `/deny-api/...`. The profile must include entries for
> **both** `/api/error/**` and `/deny-api/error/**` to route the error spec.
> This was identified during the adversarial review (BUG 3).

### A3: Enhanced echo backend

The echo backend needs path-specific behavior:

- `/html/*` → return `<html>...<h1>Test Page</h1>...</html>` with
  `Content-Type: text/html` (non-JSON response test)
- `/status/{code}` → return the specified HTTP status code
- Default → echo raw request body (current behavior) but **mirror the
  request's Content-Type in the response** instead of hardcoding
  `application/json` (fixes unrealistic `text/plain` pass-through test)

### A4: `engine_request()` helper enhancements

The current helper only captures `$ENGINE_HTTP_CODE` and `$ENGINE_BODY`.
New tests require:

1. **Response header capture** → `$ENGINE_HEADERS` (needed for header
   injection P3-01 and URL rewrite P5-05)
2. **Extra header support** → optional trailing arguments passed as
   `-H` flags (needed for cookie tests P4-01 and custom Content-Type P5-01)
3. **Content-Type override** → when caller passes a `Content-Type`
   extra header, don't add the default `application/json`

---

## New E2E Spec Files

| File | Purpose | Key feature |
|------|---------|-------------|
| `e2e-context.yaml` | Context variable test | JSLT reads `$cookies.session_token`, `$queryParams.page`, `$session` → embeds in output |
| `e2e-error.yaml` | Error mode test | `error("forced-test-error")` — compiles but always fails at runtime |
| `e2e-status-override.yaml` | Status code transform | `status.set: 201` — remaps 200 → 201 |
| `e2e-url-rewrite.yaml` | URL rewrite | `url.path.expr` rewrites request path |

## E2E Profile

```yaml
# e2e/pingaccess/profiles/e2e-profile.yaml
profile: e2e-profile
version: "1.0.0"
description: "Routes E2E specs to dedicated path prefixes"

transforms:
  # ── Bidirectional rename (existing Test 1) ──
  - spec: e2e-rename@1.0.0
    direction: request
    match:
      path: "/api/transform/**"
      method: POST

  - spec: e2e-rename@1.0.0
    direction: response
    match:
      path: "/api/transform/**"
      method: POST

  # ── Header injection (Test 7) ──
  - spec: e2e-header-inject@1.0.0
    direction: request
    match:
      path: "/api/headers/**"
      method: POST

  # ── Context variables — cookies, query params, session (Tests 9–10) ──
  - spec: e2e-context@1.0.0
    direction: request
    match:
      path: "/api/context/**"
      method: POST

  # ── Error mode — PASS_THROUGH app (Test 16) ──
  - spec: e2e-error@1.0.0
    direction: request
    match:
      path: "/api/error/**"
      method: POST

  # ── Error mode — DENY app (Tests 17–18) ──
  # Same spec, different path prefix (the DENY app uses contextRoot=/deny-api)
  - spec: e2e-error@1.0.0
    direction: request
    match:
      path: "/deny-api/error/**"
      method: POST

  # ── Status code override (Test 14) ──
  - spec: e2e-status-override@1.0.0
    direction: response
    match:
      path: "/api/status-test/**"

  # ── URL rewrite (Test 15) ──
  - spec: e2e-url-rewrite@1.0.0
    direction: request
    match:
      path: "/api/rewrite/**"
      method: POST

  # ── Non-JSON response pass-through (Test 12) ──
  # Routes through e2e-rename on response direction so the adapter's
  # wrapResponse/handleResponse code path runs (detects non-JSON → skip body JSLT).
  # Without this entry, the profile returns PASSTHROUGH and the adapter code
  # path is never exercised (identified during adversarial review, BUG 4).
  - spec: e2e-rename@1.0.0
    direction: response
    match:
      path: "/api/html/**"

  # ── Non-standard status code pass-through (Test 13) ──
  # Same rationale as above — routes through a response spec so the adapter
  # actually processes the 277 status instead of just PASSTHROUGHing.
  - spec: e2e-rename@1.0.0
    direction: response
    match:
      path: "/api/status/**"
```

---

## Phases

### Phase 1 — Infrastructure (specs, profile, echo backend)

Create all new artefacts. No test assertions yet — purely additive.

| ID | Task | Status |
|----|------|:------:|
| P1-01 | Create `e2e/pingaccess/specs/e2e-context.yaml` — JSLT reads `$cookies.session_token`, `$queryParams.page`, and `$session` → embeds all three in output JSON. Adding `$session` partially covers S-002-14 (unauth → session is null). | ⬜ |
| P1-02 | Create `e2e/pingaccess/specs/e2e-error.yaml` — `error("forced-test-error")` (copies pattern from `adapter-standalone` test-specs `bad-transform.yaml`) | ⬜ |
| P1-03 | Create `e2e/pingaccess/specs/e2e-status-override.yaml` — `status.set: 201` with passthrough body (`.`) | ⬜ |
| P1-04 | Create `e2e/pingaccess/specs/e2e-url-rewrite.yaml` — `url.path.expr: '"/api/rewritten"'` with passthrough body | ⬜ |
| P1-05 | Create `e2e/pingaccess/profiles/e2e-profile.yaml` — full profile with all path routing entries (including `/deny-api/error/**`, `/api/html/**`, `/api/status/**` entries identified during review) | ⬜ |
| P1-06 | Enhance echo backend: (a) return `text/html` body for paths containing `/html/`, (b) return custom status codes for `/status/{code}`, (c) mirror request Content-Type in response instead of hardcoding `application/json` | ⬜ |

**Verification:** All spec files are valid YAML. Echo backend handles
special paths correctly (manually tested via `curl` during live PA run).

### Phase 2 — Script infrastructure + profile activation

Update the script helpers and PA configuration to support profile routing
and new test patterns. Re-run existing tests as regression gate.

| ID | Task | Status |
|----|------|:------:|
| P2-01 | Upgrade `engine_request()` helper: (a) capture response headers to `$ENGINE_HEADERS` via `-D` dump-header flag, (b) accept optional extra header arguments after body, (c) skip default `Content-Type: application/json` when caller provides their own | ⬜ |
| P2-02 | Add `PROFILES_DIR` variable, mount `-v "$PROFILES_DIR:/profiles"` in PA container | ⬜ |
| P2-03 | Update rule creation JSON: `"profilesDir": "/profiles"`, `"activeProfile": "e2e-profile"` | ⬜ |
| P2-04 | Run existing 18 assertions — all must still pass (profile routes `/api/transform/**` → `e2e-rename` with both directions, same as before) | ⬜ |
| P2-05 | Add log assertion: PA logs show `"Loaded profile: e2e-profile"` | ⬜ |

**Exit criteria:** Existing 18/18 pass + profile loaded. No regressions.

### Phase 3 — Profile routing & header injection tests

Tests that depend on the profile routing different specs to different paths.

| ID | Task | Scenario(s) | Est. assertions | Status |
|----|------|-------------|:---------------:|:------:|
| P3-01 | Test 7: **Header injection** — POST to `/api/headers/test` with JSON body `{"key":"value"}`. Assert: (a) `X-Echo-Req-X-Transformed: true` present in response headers, (b) `X-Echo-Req-X-Transform-Version: 1.0.0` present, (c) response body is `{"key":"value"}` (`.` JSLT passthrough). Uses `$ENGINE_HEADERS` for header verification. | S-002-04 | 3 | ⬜ |
| P3-02 | Test 8: **Multiple spec routing** — POST to `/api/transform/test` verifies rename spec fires (snake→camel in echo probe). POST to `/api/headers/test` verifies header-inject spec fires. Two different specs routed by profile to different paths. | S-002-09, S-002-15 | 2 | ⬜ |

### Phase 4 — Context variable tests

Tests that verify JSLT access to `$cookies`, `$queryParams`, and
`$session` — using the `e2e-context` spec routed via profile to
`/api/context/**`.

| ID | Task | Scenario(s) | Est. assertions | Status |
|----|------|-------------|:---------------:|:------:|
| P4-01 | Test 9: **Cookies in JSLT** — POST to `/api/context/test` with extra header `Cookie: session_token=abc123; lang=en`. Assert: response body has `session_token` = `abc123` and `page` is null/absent. Uses extra header support in `engine_request()`. | S-002-22 | 2 | ⬜ |
| P4-02 | Test 10: **Query params in JSLT** — POST to `/api/context/test?page=2&limit=10`. Assert: response body has `page` = `2`. | S-002-23 | 2 | ⬜ |
| P4-03 | Test 11: **Session is null for unprotected requests** — Use the same response from P4-01 or P4-02. Assert: `session` field is null (no identity attached). Partial coverage of S-002-14 (unauthenticated path). | S-002-14 (partial) | 1 | ⬜ |

### Phase 5 — Body & status edge cases

Tests for non-JSON bodies, non-standard status codes, status code
transforms, and URL rewriting.

| ID | Task | Scenario(s) | Est. assertions | Status |
|----|------|-------------|:---------------:|:------:|
| P5-01 | Test 12: **Non-JSON body pass-through** — POST to `/api/transform/test` with `Content-Type: text/plain` and body `Hello, world!`. Adapter detects non-JSON → sets `bodyParseFailed` → skips body JSLT → forwards raw bytes. Assert: (a) response status 200, (b) response body is `Hello, world!`, (c) `X-Echo-Req-Content-Type` contains `text/plain`. Uses Content-Type override in `engine_request()`. | S-002-08 | 3 | ⬜ |
| P5-02 | Test 13: **Non-JSON response body** — GET to `/api/html/page`. Echo returns `text/html` response. Profile routes response through `e2e-rename` → adapter detects non-JSON → `bodyParseFailed` → skips body JSLT → original HTML forwarded. Assert: (a) response body contains `<html>`, (b) response status 200. | S-002-32 | 2 | ⬜ |
| P5-03 | Test 14: **Non-standard status code pass-through** — GET to `/api/status/277`. Echo returns HTTP 277 (PA's `ALLOWED` code). Profile routes response through `e2e-rename` → adapter processes the response but doesn't remap the status. Assert: response status is 277. **Risk:** PA engine may reject non-standard codes — if so, document as known limitation. | S-002-35 | 1 | ⬜ |
| P5-04 | Test 15: **Status code transform** — GET to `/api/status-test/ok`. Profile routes response to `e2e-status-override` which has `status.set: 201`. Echo returns 200. Assert: response status is 201. | S-002-05 | 1 | ⬜ |
| P5-05 | Test 16: **URL rewrite** — POST to `/api/rewrite/test` with body `{"target": "/api/rewritten"}`. Profile routes to `e2e-url-rewrite` which rewrites path. Assert: `X-Echo-Path` response header shows `/api/rewritten`. Uses `$ENGINE_HEADERS`. | S-002-06 | 1 | ⬜ |

### Phase 6 — Error mode tests

Requires creating a second PA application + rule with `errorMode=DENY`.
The existing rule has `errorMode=PASS_THROUGH`.

| ID | Task | Scenario(s) | Est. assertions | Status |
|----|------|-------------|:---------------:|:------:|
| P6-01 | PA configuration: create second rule (`errorMode=DENY`, same profile), create second app (`contextRoot=/deny-api`), configure its resource with the DENY rule. Both apps point to the same echo site. | — | — | ⬜ |
| P6-02 | Test 17: **Error mode PASS_THROUGH** — POST to `/api/error/test` with JSON body `{"key":"value"}`. Profile routes to `e2e-error` → JSLT `error("forced-test-error")` fires → `errorMode=PASS_THROUGH` → original body forwarded. Assert: (a) response status 200, (b) response body contains `key` field (round-tripped through echo), (c) PA log contains `"Request transform error (PASS_THROUGH)"`. | S-002-11 | 3 | ⬜ |
| P6-03 | Test 18: **Error mode DENY** — POST to `/deny-api/error/test` with JSON body. Same profile routing → same error → `errorMode=DENY` → RFC 9457 response via `ResponseBuilder`. Assert: (a) response status 502, (b) response body contains `"type"` (RFC 9457 structure), (c) response body contains `"title"`, (d) PA log contains `"Request transform error (DENY)"`. | S-002-12 | 4 | ⬜ |
| P6-04 | Test 19: **DENY guard verification (best-effort)** — After P6-03, check PA log for `"Response processing skipped — request was DENIED"`. If present → DENY guard was exercised. If absent → PA skips `handleResponse()` after `Outcome.RETURN` (document as known PA SDK behavior). **Note:** this is best-effort because PA's pipeline behavior determines whether `handleResponse()` is called. Either outcome is valid — the unit test verifies the guard logic independently. | S-002-28 (best-effort) | 1 | ⬜ |

### Phase 7 — Documentation & backlog

| ID | Task | Status |
|----|------|:------:|
| P7-01 | Run full E2E suite, capture output, update `e2e-results.md` with new run results (total assertions, scenario coverage) | ⬜ |
| P7-02 | Update `coverage-matrix.md` — add "E2E" column showing which scenarios have E2E vs unit-only coverage | ⬜ |
| P7-03 | Add OAuth/identity E2E scenarios to backlog in `plan.md`: S-002-13 (session context), S-002-25 (OAuth context), S-002-26 (session state). Note: consider mock OAuth server (e.g., lightweight Python OIDC stub) as alternative to PingFederate/PingAM | ⬜ |
| P7-04 | Document E2E coverage gap rationale for unit-only scenarios in `e2e-results.md`: S-002-16 (perf — adapter overhead not measurable through HTTP), S-002-18 (config validation — internal), S-002-20 (thread safety — controlled concurrency), S-002-21 (ExchangeProperty — PA internal), S-002-36 (version guard — needs different PA version) | ⬜ |
| P7-05 | Commit all changes: specs, profile, echo backend, test script, documentation | ⬜ |

---

## E2E Coverage Summary (projected after completion)

| Category | Scenarios | Count |
|----------|-----------|:-----:|
| ✅ E2E validated | S-002-01/02/03/04/05/06/07/08/09/10/11/12/14(partial)/15/17/19/22/23/24/28(best-effort)/32/35 | 22 |
| 🔶 Needs OAuth/IdP (backlogged) | S-002-13/25/26 | 3 |
| 🔶 Complex E2E (backlogged) | S-002-27 (prior rule), S-002-29/30/31 (hot reload), S-002-33/34 (JMX) | 6 |
| ❌ Unit-only (with rationale) | S-002-16 (perf), S-002-18 (config), S-002-20 (threads), S-002-21 (ExchProp), S-002-36 (version guard) | 5 |
| **Total** | | **36** |

### Assertion Count Projection

| Phase | New assertions | Running total |
|:-----:|:--------------:|:-------------:|
| Existing | 18 | 18 |
| Phase 2 | 1 (profile log) | 19 |
| Phase 3 | 5 | 24 |
| Phase 4 | 5 | 29 |
| Phase 5 | 8 | 37 |
| Phase 6 | 8 | 45 |
| **Total** | **27** | **45** |

---

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| Profile routing changes break existing tests | **High** — regression | Phase 2 runs existing 18 assertions as gate |
| Echo backend enhancement introduces instability | **Low** | Python handler changes isolated to path-specific branches |
| Second PA app/rule increases setup complexity | **Medium** | Encapsulate in reusable `create_deny_app()` function |
| Non-standard status codes (277) rejected by PA engine | **Medium** | PA SDK's `HttpStatus.forCode()` handles arbitrary codes; if rejected, document as known limitation and remove test |
| PA skips `handleResponse()` after `Outcome.RETURN` | **Low** — DENY guard test inconclusive | Best-effort log check; unit test provides definitive coverage |
| JSLT `$cookies`/`$queryParams` binding fails in PA runtime | **Low** | Same engine code path as unit tests; PA's cookie parsing is the only new variable |

---

## Adversarial Review Log

Issues found during adversarial review pass (2026-02-14):

| ID | Severity | Issue | Resolution |
|----|----------|-------|------------|
| BUG-1 | 🔴 | `engine_request()` can't capture response headers — needed for header injection (P3-01) and URL rewrite (P5-05) | Added P2-01: upgrade helper with `$ENGINE_HEADERS` |
| BUG-2 | 🔴 | `engine_request()` hardcodes `Content-Type: application/json` — blocks non-JSON body test (P5-01) | Added P2-01: extra header support with Content-Type override |
| BUG-3 | 🔴 | DENY app path `/deny-api/error/test` doesn't match profile entry `/api/error/**` — DENY test would falsely pass | Added `/deny-api/error/**` entry to profile |
| BUG-4 | 🔴 | Missing profile entries for `/api/html/**` and `/api/status/**` — adapter code path never exercised | Added response-direction entries routing through `e2e-rename` |
| GAP-5 | 🟡 | S-002-28 (DENY guard) depends on PA calling `handleResponse()` after `Outcome.RETURN` | Downgraded to best-effort log check; noted unit test provides definitive coverage |
| GAP-6 | 🟡 | Echo backend always returns `Content-Type: application/json` regardless of request type | Added P1-06(c): mirror request Content-Type |
| IMP-7 | 🟢 | S-002-14 (unauth) partially coverable by checking `$session` is null | Added `$session` to context spec output; added P4-03 test |
| IMP-8 | 🟢 | No projected assertion counts in plan | Added assertion count projection table |
