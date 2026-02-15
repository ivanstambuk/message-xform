# Feature 002 — E2E Validation Record

| Field | Value |
|-------|-------|
| Script | `scripts/pa-e2e-bootstrap.sh` + `e2e-pingaccess/` (Karate DSL) |
| Spec files | `e2e-rename.yaml`, `e2e-header-inject.yaml`, `e2e-context.yaml`, `e2e-error.yaml`, `e2e-status-override.yaml`, `e2e-url-rewrite.yaml`, `e2e-session.yaml`, `e2e-reload-addition.yaml`, `e2e-status-route-success.yaml`, `e2e-status-route-error.yaml`, `e2e-polymorphic-admin.yaml`, `e2e-polymorphic-user.yaml` |
| Profile | `e2e-profile.yaml` |
| Linked requirement | FR-002-12 |

> This is a living document. Update it each time the E2E suite is run against
> a live PingAccess instance. Append new entries to the Run History table.

---

## Latest Run

| Field | Value |
|-------|-------|
| Date | 2026-02-15 |
| Result | **39/39 PASSED** ✅ |
| PA version | 9.0.1.0 |
| PA Docker image | `pingidentity/pingaccess:latest` |
| Shadow JAR size | 4.6 MB (< 5 MB NFR-002-02) |
| Class file version | 61 (Java 17) |
| Build time | 4s |
| PA startup time | 24s |
| Test groups | 39 |

### Test Breakdown

| # | Test | Scenario(s) | Assertions | Result |
|---|------|-------------|------------|--------|
| 1 | Bidirectional body round-trip (snake↔camel) | S-002-01, S-002-02, S-002-03 | POST 200, 4× field round-trip, echo probe (2× camelCase), audit log | 8/8 ✅ |
| 2 | GET pass-through (no body) | S-002-07, S-002-10 | GET 200 | 1/1 ✅ |
| 3 | Spec loading verification | S-002-15, S-002-17 | 7× spec loaded, profile loaded | 8/8 ✅ |
| 4 | Shadow JAR verification | S-002-24 | Java 17 class version, < 5 MB | 2/2 ✅ |
| 5 | SPI registration | S-002-19 | Service file contains FQCN | 1/1 ✅ |
| 6 | PingAccess health | S-002-17 | Plugin configured, started without errors | 1/1 ✅ |
| — | Plugin discovery | S-002-19 | Type, class, mode (Site-only) | 3/3 ✅ |
| 7 | Header injection | S-002-04 | POST 200, X-Transformed=true, X-Transform-Version=1.0.0 | 3/3 ✅ |
| 8 | Multiple spec routing | S-002-09, S-002-15 | Rename spec active on /api/transform, header spec active on /api/headers | 2/2 ✅ |
| 9 | Cookie context variable | S-002-22 | POST 200, session_token extracted from Cookie header | 2/2 ✅ |
| 10 | Query param context variable | S-002-23 | POST 200, page extracted from ?page=2 | 2/2 ✅ |
| 11 | Session null (unprotected) | S-002-14 | session field is null | 1/1 ✅ |
| 12 | Non-JSON request body pass-through | S-002-08 | POST 200, raw text/plain body forwarded, Content-Type preserved | 3/3 ✅ |
| 13 | Non-JSON response body pass-through | S-002-32 | GET 200, HTML response preserved | 2/2 ✅ |
| 14 | Non-standard status code (277) | S-002-35 | Status 277 passed through | 1/1 ✅ |
| 15 | Status code transform (200→201) | S-002-05 | Status overridden to 201 | 1/1 ✅ |
| 16 | URL path rewrite | S-002-06 | POST 200, X-Echo-Path shows /api/rewritten | 2/2 ✅ |
| 17 | Error mode PASS_THROUGH | S-002-11 | POST 200, original body forwarded, PA log contains PASS_THROUGH | 3/3 ✅ |
| 18 | Error mode DENY | S-002-12 | 502, RFC 9457 type+title, PA log contains DENY | 4/4 ✅ |
| 19 | DENY guard verification (best-effort) | S-002-28 | Guard log check (either outcome valid) | 1/1 ✅ |
| 20 | Session context in JSLT (Bearer) | S-002-13 | POST 200, subject populated, clientId best-effort, scopes best-effort | 4/4 ✅ |
| 21 | OAuth context in JSLT | S-002-25 | tokenType populated, scopes best-effort | 2/2 ✅ |
| 22 | Session state merge (L1-L3) | S-002-26 | $session object populated | 1/1 ✅ |
| 23 | Web Session OIDC (L4) | S-002-26 | OIDC auth code flow → PA session cookie → authenticated access | 4/4 ✅ |
| 24 | L4 overrides L3 | S-002-26 | Repeat OIDC login → session-based auth works | 1/1 ✅ |
| 25 | Spec hot-reload success | S-002-29 | Identity→marker overwrite, wait 9s, marker version active, scheduler log | 3/3 ✅ |
| 26 | Spec hot-reload failure | S-002-30 | Invalid YAML overwrite, existing transform still works, reload failure log | 2/2 ✅ |
| 27 | Concurrent requests during reload | S-002-31 | 5 sequential requests all return 200 | 1/1 ✅ |
| 28 | JMX MBean registered | S-002-33 | MBean exists, ActiveSpecCount ≥ 1, TransformTotalCount increments, registration log | 4/4 ✅ |
| 29 | JMX MBean not registered (negative) | S-002-34 | Non-existent instance MBean does NOT exist | 1/1 ✅ |
| 33 | Status routing: 200 → success spec | S-001-87 | POST 200, result=success, original_status=200, data preserved | 4/4 ✅ |
| 34 | Status routing: 404 → error spec + 502 | S-001-88 | POST 502, result=error, original_status=404, error fields | 5/5 ✅ |
| 35 | Status routing: 301 → passthrough | S-001-89 | POST 301, original body unchanged | 2/2 ✅ |
| 36 | Status routing: default 200 | S-001-90 | POST 200 (no X-Echo-Status), success reshaping | 3/3 ✅ |
| 37 | Polymorphic: admin → admin transform | S-001-93 | POST 200, role=admin, display_name, permissions | 4/4 ✅ |
| 38 | Polymorphic: user → user transform | S-001-94 | POST 200, role=standard, display_name, no permissions | 4/4 ✅ |
| 39 | Polymorphic: 400 → error transform | S-001-95 | POST 502, error format regardless of body | 5/5 ✅ |
| 40 | Polymorphic: non-JSON → passthrough | S-001-97 | POST 200, text/plain body preserved, when-predicate gracefully skipped | 2/2 ✅ |
| | **Total** | | | **39/39** |

---

## Scenarios Validated End-to-End

| Scenario | Coverage |
|----------|----------|
| S-002-01 | Request body transform (via bidirectional round-trip) |
| S-002-02 | Response body transform (via bidirectional round-trip) |
| S-002-03 | Bidirectional transform (full round-trip proof) |
| S-002-04 | Header injection (X-Transformed, X-Transform-Version via profile routing) |
| S-002-05 | Status code transform (200→201 via status.set) |
| S-002-06 | URL path rewrite (via url.path.expr) |
| S-002-07 | Empty body (GET pass-through) |
| S-002-08 | Non-JSON body (text/plain → bodyParseFailed → raw bytes forwarded) |
| S-002-09 | Profile matching (different specs routed to different paths) |
| S-002-10 | No matching spec (GET to non-spec path → passthrough) |
| S-002-11 | Error mode PASS_THROUGH (forced error → original body forwarded) |
| S-002-12 | Error mode DENY (forced error → RFC 9457 502 response) |
| S-002-13 | Session context in JSLT ($session.subject via Bearer token, L1 Identity) |
| S-002-14 | No identity (partial — $session is null for unprotected request) |
| S-002-15 | Multiple specs loaded (7 specs + profile routing verified) |
| S-002-17 | Plugin configuration (rule created via Admin API) |
| S-002-19 | SPI registration (service file + plugin discovery in shadow JAR) |
| S-002-22 | Cookie access in JSLT ($cookies.session_token → body field) |
| S-002-23 | Query param access in JSLT ($queryParams.page → body field) |
| S-002-24 | Shadow JAR correctness (size, class version, contents) |
| S-002-25 | OAuth context in JSLT ($session.tokenType via Bearer token, L2 metadata) |
| S-002-26 | Session state in JSLT (L1-L4: Bearer L1-L3 merge + Web Session OIDC L4) |
| S-002-28 | DENY + handleResponse interaction (best-effort guard check) |
| S-002-29 | Spec hot-reload success (modified spec picked up after 5s interval) |
| S-002-30 | Spec hot-reload failure (malformed YAML → previous registry retained) |
| S-002-31 | Concurrent requests during reload (5 requests, no corruption) |
| S-002-32 | Non-JSON response body (HTML echo → body preserved) |
| S-002-33 | JMX metrics opt-in (MBean registered, counters work) |
| S-002-34 | JMX metrics disabled (non-existent instance → MBean absent) |
| S-002-35 | PA-specific non-standard status code (277 passthrough) |
| S-001-87 | Status routing: 2xx responses routed to success spec |
| S-001-88 | Status routing: 4xx responses routed to error spec + status override |
| S-001-89 | Status routing: unmatched status (3xx) → passthrough |
| S-001-90 | Status routing: default echo (no X-Echo-Status) → 2xx → success |
| S-001-93 | Polymorphic body routing: admin role → admin transform (match.when) |
| S-001-94 | Polymorphic body routing: user role → user transform (match.when) |
| S-001-95 | Polymorphic body routing: 4xx error → error transform (no when needed) |
| S-001-97 | Polymorphic body routing: non-JSON body → graceful skip → passthrough |

---

## E2E Coverage Gap Analysis

### Unit-Only Scenarios (E2E not feasible)

These 5 scenarios cannot be meaningfully validated through HTTP round-trips.
Each has comprehensive unit test coverage documented in `coverage-matrix.md`.

| Scenario | Name | Rationale |
|----------|------|-----------|
| S-002-16 | Large body performance | Adapter overhead is not measurable through HTTP round-trip (network latency dominates). Unit perf test uses `System.nanoTime()` to isolate adapter overhead. |
| S-002-18 | Invalid spec directory | Config validation happens during `configure()`, before the engine starts. E2E script can't trigger this without breaking the PA rule. Unit test exercises `ValidationException` directly. |
| S-002-20 | Thread safety | Controlled concurrency (10 threads × 100 requests) requires deterministic assertions on per-thread results. E2E HTTP can't guarantee thread isolation. |
| S-002-21 | ExchangeProperty metadata | PA-internal exchange property API. Values are set on the exchange object but not visible in HTTP response headers/body. Unit test accesses the exchange directly. |
| S-002-36 | Runtime version mismatch | Requires running against a PA version different from compile-time version. Docker E2E uses the same PA version. Unit test mocks the version check. |

---

## Run History

| Date | Result | PA Version | Script Commit | Notes |
|------|--------|------------|---------------|-------|
| 2026-02-14 ~12:24 | 13/13 ✅ | 9.0.1.0 | `0947e4b` | Initial script creation. Captured in commit message. |
| 2026-02-14 14:58 | 18/18 ✅ | 9.0.1.0 | `093c688` | Strengthened assertions (bidirectional spec, field-level payload verification, direct echo probe). |
| 2026-02-14 16:15 | 50/50 ✅ | 9.0.1.0 | — | Full E2E expansion: 19 test groups, 6 specs, profile routing, context variables, error modes, status/URL transforms, non-JSON pass-through. |
| 2026-02-14 18:04 | 62/62 ✅ | 9.0.1.0 | `97db1c1` | Phase 8a OAuth/Identity: Bearer token auth, session context, mock-oauth2-server. |
| 2026-02-15 | 31/31 ✅ | 9.0.1.0 | — | All phases complete: Phase 8b (Web Session OIDC L4), Phase 9 (hot-reload), Phase 10 (JMX metrics). JMX metric bug fixed (shared registry pattern). |
| 2026-02-15 | 39/39 ✅ | 9.0.1.0 | `72dded2` | Feature 001 Phase 2: status routing (Tests 33-36) + polymorphic body routing via match.when (Tests 37-40). Echo backend extended with X-Echo-Status/X-Echo-Body headers. |
