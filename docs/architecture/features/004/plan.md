# Feature 004 — Standalone HTTP Proxy Mode — Implementation Plan

_Linked specification:_ `docs/architecture/features/004/spec.md`
_Linked tasks:_ `docs/architecture/features/004/tasks.md`
_Status:_ In Progress (Phase 2 — I3)
_Last updated:_ 2026-02-08T22:24+01:00

> Guardrail: Keep this plan traceable back to the governing spec. Reference
> FR/NFR/Scenario IDs from `spec.md` where relevant, log any new high- or
> medium-impact questions in `docs/architecture/open-questions.md`, and assume
> clarifications are resolved only when the spec's normative sections and,
> where applicable, ADRs under `docs/decisions/` have been updated.

## Vision & Success Criteria

Build the Standalone HTTP Proxy Mode as a Gradle submodule (`adapter-standalone`)
that wraps the core `TransformEngine` in a production-ready HTTP reverse proxy.
When complete, a user can: start the proxy with a YAML config file, load
transform specs and profiles from directories, and have HTTP requests
transparently intercepted, transformed via JSLT, forwarded to a backend, and
responses transformed on the return path.

**Success criteria:**
- All 77 scenarios (S-004-01 through S-004-77) pass as integration tests.
- Proxy adds < 5ms p95 overhead for passthrough requests (NFR-004-02).
- Startup time < 3 seconds with ≤ 50 specs (NFR-004-01).
- Memory footprint < 256 MB heap under typical load (NFR-004-03).
- Docker image < 150 MB compressed (NFR-004-04).
- Hot reload is zero-downtime (NFR-004-05).
- 1000 concurrent connections handled without thread exhaustion (NFR-004-06).
- Core engine `TransformEngine.transform(Message, Direction, TransformContext)`
  overload works (Q-042 resolution).

## Scope Alignment

- **In scope:** All FRs (FR-004-01 through FR-004-39), all NFRs (NFR-004-01
  through NFR-004-07), all domain objects (DO-004-01 through DO-004-04),
  adapter implementations (IMPL-004-01 through IMPL-004-05), all configuration
  keys (CFG-004-01 through CFG-004-41), all environment variable mappings,
  all test fixtures (FX-004-01 through FX-004-09), Docker packaging, and
  the core engine API addition (3-arg `transform()` overload, Q-042).
- **Not in scope:** Multi-backend routing, backend authentication, WebSocket
  proxying, HTTP/2 upstream, rate limiting, service discovery, metrics endpoint,
  admin endpoint authentication (all documented as Non-Goals in spec).

## Prerequisites

- [x] Feature 001 (core engine) complete — all 84 scenarios passing
- [x] Feature spec `Status: Ready` — spec reviewed, all questions resolved
- [ ] All high-/medium-impact open questions resolved — ✅ none open (Q-042, Q-043 resolved)
- [x] Gradle project initialized (Feature 009 co-created with Feature 001)

## Dependencies & Interfaces

| Dependency | Notes |
|------------|-------|
| `core` module (Feature 001) | `TransformEngine`, `Message`, `TransformResult`, `TransformContext`, `GatewayAdapter` SPI |
| `io.javalin:javalin:6.x` | HTTP server with Jetty 11, virtual threads (ADR-0029) |
| `org.eclipse.jetty:jetty-server:11.x` | Embedded in Javalin — TLS configuration via Jetty API |
| JDK `java.net.http.HttpClient` | Upstream HTTP client (built-in, zero extra deps) |
| JDK `java.nio.file.WatchService` | File-system watching for hot reload |
| `com.fasterxml.jackson` (Jackson) | YAML config parsing, JSON body handling (already in core) |
| `org.slf4j:slf4j-api` + `ch.qos.logback:logback-classic` | Structured logging (JSON format) |
| `com.github.johnrengelman.shadow` | Gradle Shadow plugin for fat JAR |
| JUnit 5 + AssertJ + WireMock/MockWebServer | Test infrastructure |

## Assumptions & Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Javalin 6 API instability | **Medium** — Breaking changes between RC and stable | Pin exact Javalin version. Check release notes before upgrading. |
| JDK HttpClient connection pool is JVM-global | **Low** — Pool config via system properties, not per-instance | Document limitation (already in spec FR-004-18 note). If finer control needed, swap to Apache HttpClient 5 behind `UpstreamClient`. |
| Jetty 11 TLS API configuration | **Low** — Jetty 11 `SslContextFactory` is well-documented | Research Jetty 11 `ServerConnector` + `SslContextFactory` API before Phase 6. Create integration test with self-signed certs early. |
| Virtual threads may have platform-specific gotchas | **Low** — Well-tested on JDK 21+ | Enable via `Javalin.create { it.useVirtualThreads = true }`. Verify under load in Phase 7. |
| Core engine API change (Q-042) may affect existing tests | **Low** — Additive overload, old API preserved | Implement as first task (Phase 1). Run full Feature 001 test suite after change. |

## Implementation Drift Gate

Describe how the drift gate will be executed after each phase: run all tests,
verify scenario coverage matrix, confirm spec ↔ code alignment for each FR/NFR.

### Drift Gate Checklist (for agents)

- [ ] Spec/plan/tasks updated to the current date.
- [ ] `docs/architecture/open-questions.md` has no `Open` entries for Feature 004.
- [ ] Verification commands have been run and logged.
- [ ] For each FR (FR-004-01 through FR-004-39), confirm tests exist and pass.
- [ ] For each NFR (NFR-004-01 through NFR-004-07), confirm tests or evidence exist.
- [ ] Scenario coverage matrix in `scenarios.md` cross-referenced with tests.
- [ ] Any drift logged as open question or fixed directly.

### Drift Report

_To be completed after implementation._

## Increment Map

### Phase 1 — Foundation: Core API Change + Project Scaffold (≤90 min)

> **Core engine change first.** The 3-arg `transform()` overload (Q-042) is
> a prerequisite for all adapter work. Do it first, verify Feature 001 tests
> still pass, then set up the Gradle module.

1. **I1 — Core engine API: TransformContext injection** (≤45 min) ✅ DONE
   - _Goal:_ Add `TransformEngine.transform(Message, Direction, TransformContext)`
     overload. Refactor `transformInternal` to accept a `TransformContext` parameter
     instead of building its own. The existing 2-arg method delegates to the 3-arg
     method with an internally-built empty context (backward-compatible).
   - _Preconditions:_ Feature 001 complete.
   - _Steps:_
     1. Add `transform(Message, Direction, TransformContext)` public method.
     2. Refactor `transformInternal` to accept `TransformContext` parameter.
     3. Update 2-arg `transform(Message, Direction)` to build context and delegate.
     4. Run full Feature 001 test suite — zero regressions.
   - _Requirements covered:_ Q-042 resolution. Enables FR-004-37, FR-004-39.
   - _Commands:_ `./gradlew :core:test`, `./gradlew spotlessApply check`
   - _Exit:_ 3-arg overload works. All Feature 001 tests pass unchanged.
   - _Result:_ 6 new tests (TransformContextInjectionTest), all Feature 001 tests GREEN. Commit `16dc2eb`.

2. **I2 — Gradle module scaffold** (≤45 min) ✅ DONE
   - _Goal:_ Create `adapter-standalone` Gradle submodule with dependencies.
   - _Preconditions:_ I1 complete.
   - _Steps:_
     1. Add `include("adapter-standalone")` to `settings.gradle.kts`.
     2. Create `adapter-standalone/build.gradle.kts` with dependencies:
        `core` (project dependency), Javalin 6, Logback, Shadow plugin.
     3. Add Javalin + Logback to version catalog (`gradle/libs.versions.toml`).
     4. Create package structure: `io.messagexform.standalone`.
     5. Create `StandaloneMain.java` stub with `public static void main(String[])`.
     6. Verify `./gradlew :adapter-standalone:compileJava` succeeds.
   - _Requirements covered:_ Project structure. Enables all subsequent increments.
   - _Commands:_ `./gradlew :adapter-standalone:compileJava`, `./gradlew spotlessApply check`
   - _Exit:_ Module compiles. Dependencies resolve. Main class exists.
   - _Result:_ Javalin 6.7.0 (Jetty 11), Shadow 9.3.1, SnakeYAML 2.3 added. Dependency hygiene verified (2 tests). Commits `8cd869f`, `97657e9`.

### Phase 2 — Configuration & Bootstrap (≤90 min)

3. **I3 — Configuration model + YAML loading** (≤90 min) 🔧 IN PROGRESS
   - _Goal:_ Implement `ProxyConfig`, `BackendTlsConfig`, `TlsConfig`, `PoolConfig`
     domain objects and YAML configuration loading with environment variable overlay.
   - _Preconditions:_ I2 complete.
   - _Steps:_
     1. ✅ **Test first:** Write test: parse minimal YAML config → `ProxyConfig`
        with correct defaults (FX-004-01 pattern).
     2. ✅ Implement `ProxyConfig` record hierarchy (DO-004-01 through DO-004-04).
     3. ✅ **Test first:** Write test: parse full YAML config → all fields populated
        (FX-004-02 pattern).
     4. ⬜ **Test first:** Write test: env var overrides YAML value
        (`BACKEND_HOST=override` → `config.backend().host()` returns `"override"`).
     5. ⬜ **Test first:** Write test: empty/whitespace-only env var → YAML value used.
     6. ⬜ Implement env var overlay (FR-004-11) — all 41 config keys.
     7. ⬜ **Test first:** Write test: missing `backend.host` → startup fails with
        descriptive error (FR-004-12 validation path).
     8. ✅ **Test first:** Write test: `--config /path/to/config.yaml` CLI argument.
     9. ✅ Implement CLI argument parsing (FR-004-10).
   - _Requirements covered:_ FR-004-10, FR-004-11, FR-004-12, DO-004-01/02/03/04,
     CFG-004-01 through CFG-004-41, S-004-39 through S-004-44.
   - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
   - _Exit:_ Config loads from YAML. Env vars override. Validation works.
   - _Result (partial):_ T-004-04 (config records, 8 tests), T-004-05 (3 fixtures), T-004-06 (config loader, 8 tests) done. T-004-07 (env var overlay) and T-004-08 (validation) remain.

### Phase 3 — Core Proxy: Handler + Upstream Client (≤2 × 90 min)

4. **I4 — UpstreamClient** (≤90 min)
   - _Goal:_ Implement the JDK `HttpClient`-based upstream forwarder.
   - _Preconditions:_ I3 complete (config model exists for timeouts, pool settings).
   - _Steps:_
     1. **Test first:** Write test: forward GET request to mock backend → response
        returned with status/headers/body intact.
     2. Implement `UpstreamClient` (IMPL-004-03) using JDK `HttpClient`.
     3. **Test first:** Write test: enforce HTTP/1.1 (FR-004-33).
     4. **Test first:** Write test: recalculate `Content-Length` after body change
        (FR-004-34) — verify header reflects actual byte length.
     5. **Test first:** Write test: strip hop-by-hop headers from request and
        response (FR-004-04).
     6. **Test first:** Write test: backend unreachable → throw appropriate
        exception (enables FR-004-24).
     7. **Test first:** Write test: backend timeout → throw appropriate exception
        (enables FR-004-25).
     8. Implement connection pool configuration via system properties (FR-004-18).
   - _Requirements covered:_ FR-004-04, FR-004-18, FR-004-33, FR-004-34, IMPL-004-03.
   - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
   - _Exit:_ UpstreamClient forwards requests, strips hop-by-hop, enforces HTTP/1.1.

5. **I5 — StandaloneAdapter + ProxyHandler** (≤90 min)
   - _Goal:_ Implement `StandaloneAdapter` (GatewayAdapter for Javalin Context)
     and `ProxyHandler` (the main HTTP handler that orchestrates intercept →
     transform → forward → transform → return).
   - _Preconditions:_ I4 complete, I1 complete (3-arg transform available).
   - _Steps:_
     1. **Test first:** Write test: `wrapRequest` converts Javalin `Context` →
        `Message` with body, headers, path, method, query string (FR-004-06a).
     2. Implement `StandaloneAdapter.wrapRequest()` (IMPL-004-01).
     3. **Test first:** Write test: `wrapRequest` parses cookies from `Cookie`
        header and builds `TransformContext` with cookies populated (FR-004-37).
     4. **Test first:** Write test: `wrapRequest` parses query params and builds
        `TransformContext` with queryParams populated (FR-004-39).
     5. Implement cookie and query param extraction into `TransformContext`.
     6. **Test first:** Write test: `wrapResponse` converts upstream response →
        `Message` (FR-004-06a).
     7. Implement `StandaloneAdapter.wrapResponse()`.
     8. **Test first:** Write integration test: full request/response cycle through
        `ProxyHandler` — passthrough (no matching profile) → response unchanged
        (S-004-01, S-004-02).
     9. Implement `ProxyHandler` (IMPL-004-02) — the orchestration handler:
        a. Extract or generate X-Request-ID (FR-004-38).
        b. Check admin/health endpoint priority (FR-004-21/22 priority note).
        c. `wrapRequest` → `transform(msg, REQUEST, ctx)` → dispatch on result →
           `UpstreamClient.forward()` → populate context → `wrapResponse` →
           `transform(msg, RESPONSE, ctx)` → dispatch on result → write response.
     10. **Test first:** Write test: `TransformResult` dispatch table — SUCCESS,
         ERROR, PASSTHROUGH for both directions (FR-004-35).
     11. Implement dispatch logic per FR-004-35.
   - _Requirements covered:_ FR-004-01 through FR-004-06a, FR-004-35, FR-004-37,
     FR-004-38, FR-004-39, IMPL-004-01, IMPL-004-02, S-004-01 through S-004-10.
   - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
   - _Exit:_ Full proxy cycle works. Passthrough and transform paths verified.

### Phase 4 — Error Handling + Body Size + Forwarded Headers (≤90 min)

6. **I6 — Error handling, body size, X-Forwarded-* headers** (≤90 min)
   - _Goal:_ Implement RFC 9457 error responses, body size enforcement, forwarded
     headers, and `X-Request-ID` generation for error paths.
   - _Preconditions:_ I5 complete (ProxyHandler works).
   - _Steps:_
     1. **Test first:** Write test: transform error → `502 Bad Gateway` RFC 9457
        body (FR-004-23, S-004-09, S-004-14).
     2. **Test first:** Write test: backend unreachable → `502 Bad Gateway`
        (FR-004-24, S-004-23).
     3. **Test first:** Write test: backend timeout → `504 Gateway Timeout`
        (FR-004-25, S-004-24).
     4. **Test first:** Write test: non-JSON body on matched route → `400 Bad
        Request` (FR-004-26, S-004-25).
     5. Implement error handler that wraps all errors in RFC 9457 JSON.
     6. **Test first:** Write test: request body exceeds limit → `413 Payload
        Too Large` (FR-004-13, S-004-26 through S-004-28).
     7. Implement Jetty-level `HttpConfiguration.setMaxRequestContentSize()`.
     8. **Test first:** Write test: response body exceeds limit → `502 Bad
        Gateway` (FR-004-13).
     9. Implement response body size check in `UpstreamClient`.
     10. **Test first:** Write test: `X-Forwarded-For/Proto/Host` added when
         enabled (FR-004-36, S-004-59 through S-004-62).
     11. Implement forwarded headers in `ProxyHandler`.
     12. **Test first:** Write test: unknown HTTP method → `405 Method Not Allowed`
         (FR-004-05, S-004-03).
   - _Requirements covered:_ FR-004-05, FR-004-13, FR-004-23 through FR-004-26,
     FR-004-36, S-004-09, S-004-14, S-004-23 through S-004-28, S-004-59 through
     S-004-62.
   - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
   - _Exit:_ All error paths produce RFC 9457 responses. Body size enforced. Headers forwarded.

### Phase 5 — Health, Readiness, Hot Reload, Admin (≤2 × 90 min)

7. **I7 — Health + readiness endpoints** (≤90 min)
   - _Goal:_ Implement `/health` and `/ready` endpoints.
   - _Preconditions:_ I5 complete (Javalin server running).
   - _Steps:_
     1. **Test first:** Write test: `GET /health` → `200 {\"status\": \"UP\"}`
        (FR-004-21, S-004-34).
     2. **Test first:** Write test: `GET /ready` with loaded engine and reachable
        backend → `200 {\"status\": \"READY\", ...}` (FR-004-22, S-004-35).
     3. **Test first:** Write test: `GET /ready` before engine loads → `503`
        (S-004-36).
     4. **Test first:** Write test: `GET /ready` with unreachable backend → `503`
        (S-004-37).
     5. **Test first:** Write test: health/ready not subject to profile matching
        (S-004-38, S-004-70).
     6. Implement health/readiness handlers with configurable paths
        (CFG-004-34/35/36).
   - _Requirements covered:_ FR-004-21, FR-004-22, S-004-34 through S-004-38,
     S-004-70.
   - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
   - _Exit:_ Health and readiness endpoints work. Not subject to transforms.

8. **I8 — FileWatcher + admin reload** (≤90 min)
   - _Goal:_ Implement file-system watching for hot reload and `POST /admin/reload`.
   - _Preconditions:_ I5 complete (engine loaded), I7 ideally complete.
   - _Steps:_
     1. **Test first:** Write test: modify spec file → watcher detects →
        `engine.reload()` called (FR-004-19, S-004-29, S-004-30).
     2. Implement `FileWatcher` (IMPL-004-04) using `WatchService` with
        configurable debounce (CFG-004-31/32/33).
     3. **Test first:** Write test: `POST /admin/reload` → engine reloads →
        `200 {\"status\": \"reloaded\", \"specs\": N}` (FR-004-20, S-004-31).
     4. **Test first:** Write test: reload with broken spec → previous registry
        preserved → `500` with error details (S-004-32, S-004-33).
     5. **Test first:** Write test: admin endpoint not subject to profile matching.
     6. **Test first:** Write test: concurrent reload during traffic → in-flight
        requests complete with old registry (NFR-004-05, S-004-66).
     7. Implement admin reload handler with configurable path (CFG-004-41).
   - _Requirements covered:_ FR-004-19, FR-004-20, NFR-004-05, S-004-29 through
     S-004-33, S-004-66.
   - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
   - _Exit:_ File watcher triggers reload. Admin endpoint works. Fail-safe reload.

### Phase 6 — TLS (≤90 min)

9. **I9 — Inbound + outbound TLS** (≤90 min)
   - _Goal:_ Implement inbound HTTPS, inbound mTLS, outbound TLS, and outbound mTLS.
   - _Preconditions:_ I5 complete (server running), I4 complete (UpstreamClient).
   - _Steps:_
     1. Generate self-signed test certs (FX-004-06/07/08): server keystore,
        client keystore, CA truststore.
     2. **Test first:** Write test: `proxy.tls.enabled: true` → Jetty serves HTTPS
        (FR-004-14, S-004-45).
     3. Implement Jetty `SslContextFactory.Server` configuration.
     4. **Test first:** Write test: `proxy.tls.client-auth: need` → client cert
        required (FR-004-15, S-004-46, S-004-47).
     5. Implement mTLS with truststore configuration.
     6. **Test first:** Write test: `backend.scheme: https` → upstream TLS
        (FR-004-16, S-004-48).
     7. Implement `SSLContext` for `HttpClient` with truststore.
     8. **Test first:** Write test: `backend.tls.keystore` → outbound mTLS
        (FR-004-17, S-004-49).
     9. Implement outbound mTLS with client keystore.
     10. **Test first:** Write test: invalid keystore → startup fails with
         descriptive error (S-004-50 through S-004-53).
     11. Implement TLS config validation at startup.
   - _Requirements covered:_ FR-004-14 through FR-004-17, CFG-004-03 through
     CFG-004-10, CFG-004-20 through CFG-004-26, S-004-45 through S-004-58.
   - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
   - _Exit:_ All four TLS modes work. Validation errors caught at startup.

### Phase 7 — Startup, Shutdown, Logging (≤90 min)

10. **I10 — Startup sequence + graceful shutdown + structured logging** (≤90 min)
    - _Goal:_ Implement the full startup sequence (FR-004-27), graceful shutdown
      (FR-004-28), and structured JSON logging (NFR-004-07).
    - _Preconditions:_ I3, I5, I7, I8 complete.
    - _Steps:_
      1. **Test first:** Write test: `StandaloneMain.main()` with valid config →
         server starts, logs startup summary (FR-004-27, S-004-63).
      2. Implement `StandaloneMain` (IMPL-004-05) — orchestrate startup sequence:
         load config → register engines → load specs/profiles → init HttpClient →
         start Javalin → start FileWatcher.
      3. **Test first:** Write test: invalid config → exit with non-zero status
         and descriptive error (S-004-64).
      4. **Test first:** Write test: zero specs → valid startup, all requests
         passthrough (S-004-39).
      5. **Test first:** Write test: SIGTERM → graceful drain → exit 0 (FR-004-28).
      6. Implement shutdown hook with configurable drain timeout (CFG-004-39).
      7. Configure Logback for structured JSON logging (CFG-004-37/38).
      8. **Test first:** Write test: log entries include request ID, method, path,
         status, latencies, backend host, transform result type (NFR-004-07).
      9. Implement MDC-based structured log fields in `ProxyHandler`.
    - _Requirements covered:_ FR-004-27, FR-004-28, NFR-004-07, CFG-004-37/38/39,
      S-004-63, S-004-64, S-004-71 through S-004-73.
    - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
    - _Exit:_ Server starts and stops cleanly. Structured logs produced.

### Phase 8 — Docker Packaging + Integration Test Sweep (≤2 × 90 min)

11. **I11 — Dockerfile + shadow JAR** (≤90 min)
    - _Goal:_ Produce a Docker image via multi-stage build with shadow JAR.
    - _Preconditions:_ I10 complete (full startup works).
    - _Steps:_
      1. Add Shadow plugin to `adapter-standalone/build.gradle.kts`.
      2. Create `adapter-standalone/Dockerfile` — multi-stage: JDK 21 build +
         `eclipse-temurin:21-jre-alpine` runtime (FR-004-29, FX-004-09).
      3. **Test:** `./gradlew :adapter-standalone:shadowJar` → single fat JAR.
      4. **Test:** `docker build -t message-xform-proxy .` → image builds.
      5. **Test:** Image size < 150 MB compressed (NFR-004-04).
      6. **Test:** `docker run` → container starts → `GET /health` returns 200.
      7. **Test:** Volume mount for specs/profiles works (FR-004-31).
      8. Verify `java -jar proxy.jar` starts proxy (FR-004-30).
    - _Requirements covered:_ FR-004-29 through FR-004-32, NFR-004-04, FX-004-09.
    - _Commands:_ `./gradlew :adapter-standalone:shadowJar`, `docker build`, `docker run`
    - _Exit:_ Docker image builds. Container starts. Health check passes.

12. **I12 — Full integration test sweep + scenario coverage matrix** (≤90 min)
    - _Goal:_ Ensure all 77 scenarios pass. Fill in coverage matrix.
    - _Preconditions:_ I11 complete.
    - _Steps:_
      1. Run full integration test suite across all scenario categories.
      2. Fix any failing scenarios (implementation gaps or scenario errors).
      3. Create `scenarios.md` with coverage matrix: map each scenario to its
         passing test class and method.
      4. Verify NFRs:
         - NFR-004-01: Startup time benchmark.
         - NFR-004-02: Passthrough overhead benchmark.
         - NFR-004-03: Heap usage under load.
         - NFR-004-06: 1000 concurrent connections test (S-004-65).
      5. Run drift gate checklist.
    - _Requirements covered:_ All FRs + NFRs verified.
    - _Commands:_ `./gradlew :adapter-standalone:test`, `./gradlew spotlessApply check`
    - _Exit:_ All scenarios green. Coverage matrix complete. Drift gate clean.

## Scenario Tracking

| Scenario ID | Increment / Task reference | Notes |
|-------------|---------------------------|-------|
| S-004-01 through S-004-06 | I5 (ProxyHandler basic proxy) | Passthrough, method/header/path/query forwarding |
| S-004-07 through S-004-10 | I5 (request transform) | Request body/header transform, errors, no-body |
| S-004-11 through S-004-14 | I5 (response transform) | Response body/header/status transform, errors |
| S-004-15, S-004-16 | I5 (bidirectional) | Request + response transform in same request |
| S-004-17 through S-004-22 | I5 (profiles + dispatch) | Profile matching, wildcard, chaining, no-match |
| S-004-23 through S-004-25 | I6 (error handling) | Backend errors, non-JSON body |
| S-004-26 through S-004-28 | I6 (body size) | Body size limits |
| S-004-29 through S-004-33 | I8 (hot reload) | FileWatcher, admin reload, fail-safe |
| S-004-34 through S-004-38 | I7 (health/readiness) | Health, readiness, not-transformed |
| S-004-39 through S-004-44 | I3 (config) | Config loading, defaults, env vars, errors |
| S-004-45 through S-004-58 | I9 (TLS) | Inbound/outbound TLS/mTLS, errors |
| S-004-59 through S-004-62 | I6 (forwarded headers) | X-Forwarded-* headers |
| S-004-63, S-004-64 | I10 (startup/shutdown) | Startup sequence, config error |
| S-004-65, S-004-66 | I8, I12 (concurrency) | 1000 concurrent, reload under traffic |
| S-004-67 through S-004-69 | I5 (cookies) | Cookie binding in JSLT |
| S-004-70 | I7 (collision) | Wildcard profile vs admin/health |
| S-004-71 through S-004-73 | I5, I6, I10 (request ID) | X-Request-ID generation/echo/error |
| S-004-74 through S-004-77 | I5 (query params) | $queryParams binding in JSLT |

## Analysis Gate

Run `docs/operations/analysis-gate-checklist.md` at Phase 3 completion (basic
proxy working) and Phase 8 completion (all scenarios pass).

- _Phase 3 gate:_ After I5, verify core proxy cycle works end-to-end,
  passthrough and transform paths correct, ~30% of scenarios pass.
- _Phase 8 gate:_ After I12, full scenario sweep, drift gate, coverage matrix.

## Exit Criteria

- [ ] All 12 increments (Phase 1–8) completed and checked off
- [ ] Quality gate passes (`./gradlew spotlessApply check`)
- [ ] All 77 scenarios verified — mapped to test classes in coverage matrix
- [ ] Coverage matrix in `scenarios.md` has no uncovered FRs/NFRs
- [ ] Implementation Drift Gate report attached
- [ ] Open questions resolved and removed from `open-questions.md`
- [ ] Docker image builds and passes smoke test
- [ ] Documentation synced: roadmap status → ✅ Complete

## Intent Log

Record key prompts, decisions, and validation commands per increment:

- **I1:** _Pending — core API change for TransformContext injection._
- **I2:** …

## Follow-ups / Backlog

- **Feature 009: Performance Infrastructure** — NFR-004-02 (passthrough overhead)
  and NFR-004-06 (1000 concurrent) are integration-level benchmarks. Consider
  automating via ADR-0028 Layer 2 framework.
- **Admin endpoint authentication** — `/admin/reload` has no auth in v1.
  Future scope: API key, mTLS-only, or Kubernetes NetworkPolicy.
- **Metrics endpoint** — Prometheus/Micrometer integration for p95 latency,
  request count, error rate, upstream latency. Currently covered by structured
  JSON logging.
- **HTTP/2 upstream** — Outbound connections use HTTP/1.1 in v1. If needed,
  JDK HttpClient supports HTTP/2 natively — minimal code change.
- **Multi-backend routing** — Each proxy instance targets one backend. Multi-backend
  requires product-level routing logic (e.g., path-based dispatch to different
  upstreams).
