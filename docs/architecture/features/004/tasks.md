# Feature 004 — Standalone HTTP Proxy Mode — Tasks

_Status:_ Not Started
_Last updated:_ 2026-02-08

**Governing spec:** `docs/architecture/features/004/spec.md`
**Implementation plan:** `docs/architecture/features/004/plan.md`

> Keep this checklist aligned with the feature plan increments. Stage tests
> before implementation, record verification commands beside each task, and
> prefer bite-sized entries (≤90 minutes).
>
> When referencing requirements, keep feature IDs (`FR-`), non-functional IDs
> (`NFR-`), and scenario IDs (`S-004-`) in parentheses after the task title.
>
> When new high- or medium-impact questions arise during execution, add them to
> `docs/architecture/open-questions.md` instead of informal notes, and treat a
> task as fully resolved only once the governing spec sections and, when
> required, ADRs under `docs/decisions/` reflect the clarified behaviour.

## Task Checklist

Tasks are ordered by dependency. Each task references the spec requirement it
implements and **sequences tests before code** (Rule 12 — TDD cadence).

---

### Phase 1 — Foundation: Core API Change + Project Scaffold

#### I1 — Core engine API: TransformContext injection

- [ ] **T-004-01** — Add 3-arg `transform()` overload to TransformEngine (Q-042)
  _Intent:_ The standalone adapter needs to inject gateway-specific context
  (cookies, query params) into the engine. Add
  `TransformEngine.transform(Message, Direction, TransformContext)` as a new
  public overload. The existing 2-arg method continues to work unchanged.
  _Test first:_ Write `TransformContextInjectionTest`:
  - Call 3-arg `transform()` with a `TransformContext` containing cookies and
    query params → `$cookies` and `$queryParams` available in JSLT expressions.
  - Call 2-arg `transform()` → backward-compatible, `$cookies` and `$queryParams`
    are empty maps (not null).
  - Null `TransformContext` → `NullPointerException` (fail-fast contract).
  _Implement:_ Add 3-arg public method. Refactor `transformInternal` to accept
  `TransformContext` parameter. Update 2-arg method to build context internally
  and delegate to 3-arg method.
  _Verify:_ New test passes. **All existing Feature 001 tests pass unchanged.**
  _Verification commands:_
  - `./gradlew :core:test --tests "*TransformContextInjectionTest*"`
  - `./gradlew :core:test`
  - `./gradlew spotlessApply check`

#### I2 — Gradle module scaffold

- [ ] **T-004-02** — Create `adapter-standalone` Gradle submodule
  _Intent:_ Establish the new module with correct dependencies: `core` (project
  dependency), Javalin 6, Logback. Shadow plugin for fat JAR.
  _Implement:_
  1. Add `include("adapter-standalone")` to `settings.gradle.kts`.
  2. Create `adapter-standalone/build.gradle.kts` with:
     - `implementation(project(":core"))` — core engine
     - Javalin 6 (brings Jetty 12 transitively)
     - Logback Classic (SLF4J binding)
     - Shadow plugin (for fat JAR, but configure later in Phase 8)
  3. Add Javalin, Logback, Shadow versions to `gradle/libs.versions.toml`.
  4. Create package: `io.messagexform.standalone`.
  5. Create empty `StandaloneMain.java` with `public static void main(String[])`.
  _Verify:_ Module compiles. Dependencies resolve.
  _Verification commands:_
  - `./gradlew :adapter-standalone:compileJava`
  - `./gradlew :adapter-standalone:dependencies --configuration compileClasspath`
  - `./gradlew spotlessApply check`

- [ ] **T-004-03** — Verify zero gateway-specific dependencies in adapter-standalone
  _Intent:_ The standalone adapter depends on `core` + Javalin + Logback. It
  MUST NOT depend on any other gateway SDK (PingAccess, PingGateway, Kong, etc.).
  _Test first:_ Write `StandaloneDependencyTest` — inspect dependency tree,
  assert only expected artifacts appear.
  _Implement:_ N/A — verification-only task.
  _Verify:_ `StandaloneDependencyTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*StandaloneDependencyTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 2 — Configuration & Bootstrap

#### I3 — Configuration model + YAML loading

- [ ] **T-004-04** — ProxyConfig record hierarchy (DO-004-01/02/03/04, CFG-004-01..41)
  _Intent:_ Define the immutable configuration model. `ProxyConfig` is the root,
  containing `BackendConfig`, `TlsConfig`, `PoolConfig` as nested records.
  _Test first:_ Write `ProxyConfigTest`:
  - Construct `ProxyConfig` with minimal fields → defaults applied correctly:
    `proxy.host` → `0.0.0.0`, `proxy.port` → `9090`, `backend.scheme` → `http`,
    `backend.port` → `80` (for http) / `443` (for https).
  - Construct with all fields → all accessible.
  - Immutability: config records are immutable after construction.
  _Implement:_ Create `ProxyConfig`, `BackendConfig`, `TlsConfig`, `PoolConfig`
  records in `io.messagexform.standalone.config`.
  _Verify:_ `ProxyConfigTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ProxyConfigTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-05** — Create config test fixtures (FX-004-01/02/03)
  _Intent:_ Create the YAML config fixtures used by all configuration tests.
  _Implement:_ Create in `adapter-standalone/src/test/resources/config/`:
  - `minimal-config.yaml` (FX-004-01) — only `backend.host` + `backend.port`.
  - `full-config.yaml` (FX-004-02) — all 41 config keys populated.
  - `tls-config.yaml` (FX-004-03) — inbound + outbound TLS configuration.
  _Verify:_ Files exist and are valid YAML.
  _Verification commands:_
  - N/A — file creation only.

- [ ] **T-004-06** — YAML config loader (FR-004-10)
  _Intent:_ Parse YAML config file into `ProxyConfig`. Load from default path
  (`message-xform-proxy.yaml`) or custom path via `--config` CLI argument.
  _Test first:_ Write `ConfigLoaderTest`:
  - Load `minimal-config.yaml` → `ProxyConfig` with correct backend fields +
    all defaults (S-004-24, S-004-28).
  - Load `full-config.yaml` → all fields populated correctly.
  - Missing config file → startup error with usage message (S-004-26).
  - `--config /path/to/file` → loads from specified path.
  _Implement:_ Create `ConfigLoader` class using Jackson YAML.
  _Verify:_ `ConfigLoaderTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ConfigLoaderTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-07** — Environment variable overlay (FR-004-11)
  _Intent:_ Every config key overridable via environment variable. Env vars
  take precedence over YAML values. Whitespace-only env vars treated as unset.
  _Test first:_ Write `EnvVarOverlayTest`:
  - `BACKEND_HOST=override` overrides YAML `backend.host: original` (S-004-25).
  - `PROXY_PORT=8080` overrides YAML `proxy.port: 9090`.
  - Empty string env var → YAML value used.
  - Whitespace-only env var → YAML value used (FR-004-11 precision).
  - All 41 env var mappings tested (at least representative subset).
  _Implement:_ Add env var overlay layer to `ConfigLoader`.
  _Verify:_ `EnvVarOverlayTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*EnvVarOverlayTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-08** — Config validation (FR-004-12)
  _Intent:_ Validate mandatory fields and value constraints at startup.
  _Test first:_ Write `ConfigValidationTest`:
  - Missing `backend.host` → startup fails with descriptive error (S-004-27).
  - Invalid `backend.scheme` (not `http` or `https`) → error.
  - Invalid `proxy.tls.client-auth` (not `none`/`want`/`need`) → error.
  - Invalid `logging.level` (not in TRACE/DEBUG/INFO/WARN/ERROR) → error.
  - `backend.port` auto-derived from scheme when omitted.
  _Implement:_ Add validation logic to `ConfigLoader` or `ProxyConfig.validate()`.
  _Verify:_ `ConfigValidationTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ConfigValidationTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 3 — Core Proxy: Handler + Upstream Client

#### I4 — UpstreamClient

- [ ] **T-004-09** — UpstreamClient: basic forwarding (IMPL-004-03, FR-004-01)
  _Intent:_ Implement the JDK `HttpClient`-based upstream forwarder. Forward
  requests and return responses with status/headers/body intact.
  _Test first:_ Write `UpstreamClientTest` with a mock HTTP backend
  (WireMock or similar):
  - `GET /api/users` → forwarded to backend → response 200 with JSON body
    returned intact.
  - `POST /api/data` with JSON body → body forwarded correctly.
  - Response headers from backend returned to caller.
  _Implement:_ Create `UpstreamClient` class using `java.net.http.HttpClient`.
  _Verify:_ `UpstreamClientTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*UpstreamClientTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-10** — UpstreamClient: HTTP/1.1 enforcement (FR-004-33, S-004-52)
  _Intent:_ Force HTTP/1.1 for all upstream connections.
  _Test first:_ Write additional `UpstreamClientTest` case:
  - Verify `HttpClient` configured with `HttpClient.Version.HTTP_1_1`.
  - Upstream request uses HTTP/1.1 regardless of client protocol version.
  _Implement:_ Configure `HttpClient.newBuilder().version(HTTP_1_1)`.
  _Verify:_ Test passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*UpstreamClientTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-11** — UpstreamClient: Content-Length recalculation (FR-004-34, S-004-53/54)
  _Intent:_ After body transformation changes the body size, recalculate
  `Content-Length` header for both upstream requests and client responses.
  _Test first:_ Write `ContentLengthTest`:
  - Request: client sends 100 bytes → transform adds 50 bytes → upstream
    receives `Content-Length: 150`.
  - Response: backend returns 200 bytes → transform removes 50 bytes → client
    receives `Content-Length: 150`.
  - Never blindly copy original `Content-Length`.
  _Implement:_ Recalculate in `UpstreamClient.forward()` and in `ProxyHandler`
  response path.
  _Verify:_ `ContentLengthTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ContentLengthTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-12** — UpstreamClient: hop-by-hop header stripping (FR-004-04)
  _Intent:_ Strip hop-by-hop headers (`Connection`, `Transfer-Encoding`,
  `Keep-Alive`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailer`,
  `Upgrade`) in both request and response directions per RFC 7230 §6.1.
  _Test first:_ Write `HopByHopHeaderTest`:
  - Request with `Connection: keep-alive`, `Transfer-Encoding: chunked` →
    stripped before forwarding to backend.
  - Response with `Connection: close` → stripped before returning to client.
  - Non-hop-by-hop headers preserved (e.g., `Content-Type`, `Authorization`).
  _Implement:_ Add hop-by-hop filter to `UpstreamClient`.
  _Verify:_ `HopByHopHeaderTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*HopByHopHeaderTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-13** — UpstreamClient: backend error handling (FR-004-24/25, S-004-18/19/20)
  _Intent:_ Handle backend connection failures and timeouts.
  _Test first:_ Write `UpstreamErrorTest`:
  - Backend unreachable (host does not resolve) → appropriate exception thrown.
  - Backend connection refused (port not listening) → appropriate exception.
  - Backend timeout (no response within `read-timeout-ms`) → appropriate exception.
  _Implement:_ Catch `ConnectException`, `HttpTimeoutException`, etc. in
  `UpstreamClient` and wrap in domain-specific exceptions.
  _Verify:_ `UpstreamErrorTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*UpstreamErrorTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-14** — UpstreamClient: connection pool configuration (FR-004-18)
  _Intent:_ Configure connection pool via JVM system properties.
  _Test first:_ Write `ConnectionPoolConfigTest`:
  - Verify system properties set: `jdk.httpclient.connectionPoolSize`,
    `jdk.httpclient.keepalive.timeout`.
  - Defaults: `max-connections: 100`, `keep-alive: true`, `idle-timeout-ms: 60000`.
  _Implement:_ Set system properties at `UpstreamClient` construction based on
  `PoolConfig`.
  _Verify:_ `ConnectionPoolConfigTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ConnectionPoolConfigTest*"`
  - `./gradlew spotlessApply check`

#### I5 — StandaloneAdapter + ProxyHandler

- [ ] **T-004-15** — StandaloneAdapter.wrapRequest (FR-004-06, FR-004-07, FR-004-09)
  _Intent:_ Implement `GatewayAdapter<Context>.wrapRequest()` for Javalin.
  Extracts body, headers, path, method, query string from `Context`. Headers
  normalized to lowercase. Creates deep copy (ADR-0013).
  _Test first:_ Write `StandaloneAdapterTest.wrapRequest`:
  - POST with JSON body → `Message.body()` is correct `JsonNode`.
  - Headers extracted with lowercase keys (FR-004-09).
  - `headersAll` multi-value map populated correctly.
  - Request path and method extracted.
  - Query string extracted (raw, without leading `?`).
  - No body (GET) → `Message.body()` is `NullNode`.
  - Deep copy: mutation to `Message` does not affect original `Context`.
  _Implement:_ Create `StandaloneAdapter` implementing `GatewayAdapter<Context>`.
  _Verify:_ `StandaloneAdapterTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*StandaloneAdapterTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-16** — StandaloneAdapter: cookie extraction into TransformContext (FR-004-37, S-004-67/68/69)
  _Intent:_ Parse cookies from the `Cookie` header via `ctx.cookieMap()` and
  build a `TransformContext` with cookies populated. This enables `$cookies`
  binding in JSLT.
  _Test first:_ Write `CookieExtractionTest`:
  - `Cookie: session=abc123; lang=en` → `TransformContext.cookies()` contains
    `{session: "abc123", lang: "en"}` (S-004-67).
  - No `Cookie` header → cookies is empty map (not null) (S-004-68).
  - URL-encoded cookie value → decoded (S-004-69).
  _Implement:_ Add cookie extraction to `StandaloneAdapter.wrapRequest()`.
  Build `TransformContext` with cookies.
  _Verify:_ `CookieExtractionTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*CookieExtractionTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-17** — StandaloneAdapter: query param extraction into TransformContext (FR-004-39, S-004-74/75/76/77)
  _Intent:_ Parse query parameters from the URL via `ctx.queryParamMap()` and
  build a `TransformContext` with queryParams populated. First value only for
  multi-value params (consistent with single-value `$headers`).
  _Test first:_ Write `QueryParamExtractionTest`:
  - `GET /api?page=2&sort=name` → `TransformContext.queryParams()` contains
    `{page: "2", sort: "name"}` (S-004-74).
  - No query string → queryParams is empty map (S-004-75).
  - Multi-value `tag=a&tag=b` → first value `"a"` (S-004-76).
  - URL-encoded `name=hello%20world` → decoded `"hello world"` (S-004-77).
  _Implement:_ Add query param extraction to `StandaloneAdapter.wrapRequest()`.
  _Verify:_ `QueryParamExtractionTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*QueryParamExtractionTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-18** — StandaloneAdapter.wrapResponse (FR-004-06a, FR-004-06b, FR-004-07)
  _Intent:_ Implement `wrapResponse()` — reads upstream response data from
  Javalin `Context` (after `ProxyHandler` populates it). Includes original
  request path and method for response-direction profile matching.
  _Test first:_ Write `StandaloneAdapterTest.wrapResponse`:
  - Response with JSON body → `Message.body()` is correct `JsonNode`.
  - Status code from upstream preserved.
  - Headers extracted with lowercase keys.
  - `requestPath` and `requestMethod` from original request are included
    (FR-004-06b).
  - `queryString` is null for responses.
  - No body (204) → `Message.body()` is `NullNode` (S-004-64).
  _Implement:_ Implement `StandaloneAdapter.wrapResponse()`.
  _Verify:_ `StandaloneAdapterTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*StandaloneAdapterTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-19** — StandaloneAdapter.applyChanges (FR-004-08)
  _Intent:_ Write the transformed `Message` fields back to Javalin response
  context. Called only for RESPONSE SUCCESS.
  _Test first:_ Write `StandaloneAdapterTest.applyChanges`:
  - Transformed body → `ctx.result()` updated.
  - Transformed headers → `ctx.header()` updated.
  - Transformed status code → `ctx.status()` updated.
  - Null body → empty response body.
  _Implement:_ Implement `StandaloneAdapter.applyChanges()`.
  _Verify:_ `StandaloneAdapterTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*StandaloneAdapterTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-20** — ProxyHandler: passthrough cycle (FR-004-01, S-004-01/02/03/04/05/06)
  _Intent:_ Implement the basic proxy cycle for passthrough requests — no
  matching profile, request forwarded unmodified, response returned unmodified.
  _Test first:_ Write `ProxyHandlerPassthroughTest` (integration test with mock
  backend):
  - `GET /api/users` with no matching profile → forwarded unmodified → response
    returned unmodified (S-004-01).
  - `POST /api/data` with JSON body → body forwarded intact (S-004-02).
  - All seven HTTP methods proxied correctly (S-004-03).
  - Headers forwarded (hop-by-hop stripped) (S-004-04).
  - Query string forwarded intact (S-004-05).
  - Full path forwarded (S-004-06).
  _Implement:_ Create `ProxyHandler` (IMPL-004-02). Start Javalin server
  with `ProxyHandler` as the default handler. Wire `UpstreamClient`.
  _Verify:_ `ProxyHandlerPassthroughTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ProxyHandlerPassthroughTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-21** — ProxyHandler: request transformation (FR-004-02, S-004-07/08/09/10)
  _Intent:_ When a profile matches, apply request transformation before
  forwarding to the backend.
  _Test first:_ Write `RequestTransformTest` (integration):
  - Profile matches `POST /api/orders` → JSLT transforms body → backend
    receives transformed body (S-004-07).
  - Profile adds/removes/renames request headers (S-004-08).
  - Transform error → `502 Bad Gateway` RFC 9457 error, request NOT forwarded
    (S-004-09).
  - Request with no body (`GET`) matches profile → transform receives `NullNode`
    body → expression evaluates on `NullNode` (S-004-10).
  _Implement:_ Wire `TransformEngine.transform(message, REQUEST, context)` into
  `ProxyHandler`. Dispatch on `TransformResult`.
  _Verify:_ `RequestTransformTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*RequestTransformTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-22** — ProxyHandler: response transformation (FR-004-03, S-004-11/12/13/14)
  _Intent:_ Apply response transformation before returning to client.
  _Test first:_ Write `ResponseTransformTest` (integration):
  - Backend returns JSON → JSLT transforms response body → client receives
    transformed body (S-004-11).
  - Profile adds/removes response headers (S-004-12).
  - Profile overrides response status code (S-004-13).
  - Response transform error → `502 Bad Gateway` (S-004-14).
  _Implement:_ Wire response transform path. Call `applyChanges` for
  RESPONSE SUCCESS.
  _Verify:_ `ResponseTransformTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ResponseTransformTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-23** — ProxyHandler: bidirectional transformation (S-004-15/16/17)
  _Intent:_ Both request and response transformed in the same request cycle.
  _Test first:_ Write `BidirectionalProxyTest` (integration):
  - Request body transformed + response body transformed using same spec
    (S-004-15).
  - Request transformed + response passthrough (S-004-16).
  - Request passthrough + response transformed (S-004-17).
  _Implement:_ Already handled by ProxyHandler cycle; verify correctness.
  _Verify:_ `BidirectionalProxyTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*BidirectionalProxyTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-24** — ProxyHandler: TransformResult dispatch table (FR-004-35, S-004-60..64)
  _Intent:_ Verify the full dispatch table from the spec: SUCCESS, PASSTHROUGH,
  ERROR for both REQUEST and RESPONSE directions.
  _Test first:_ Write `DispatchTableTest` (integration):
  - REQUEST SUCCESS → `UpstreamClient` receives transformed `Message`; no
    `applyChanges` call (S-004-60).
  - RESPONSE SUCCESS → `applyChanges` called → client receives transformed
    response (S-004-61).
  - REQUEST ERROR → client receives error, NOT forwarded to backend (S-004-62).
  - REQUEST PASSTHROUGH → raw bytes forwarded, no JSON parse (S-004-63).
  - RESPONSE with 204 backend → `NullNode` body → profile matching proceeds
    (S-004-64).
  _Implement:_ Verify dispatch logic in `ProxyHandler`.
  _Verify:_ `DispatchTableTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*DispatchTableTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-25** — ProxyHandler: X-Request-ID generation/echo (FR-004-38, S-004-71/72/73)
  _Intent:_ Generate UUID for missing `X-Request-ID` or echo it if present.
  Include in response headers and structured log entries.
  _Test first:_ Write `RequestIdTest` (integration):
  - Request without `X-Request-ID` → response includes `X-Request-ID: <uuid>`
    (S-004-71).
  - Request with `X-Request-ID: abc-123` → response includes
    `X-Request-ID: abc-123` (S-004-72).
  - Error response still includes `X-Request-ID` (S-004-73).
  _Implement:_ Add request ID extraction/generation at the top of
  `ProxyHandler.handle()`. Add to MDC for structured logging.
  _Verify:_ `RequestIdTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*RequestIdTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 4 — Error Handling + Body Size + Forwarded Headers

#### I6 — Error handling, body size, X-Forwarded-* headers

- [ ] **T-004-26** — RFC 9457 error responses (FR-004-23, S-004-09/14)
  _Intent:_ All proxy errors produce RFC 9457 Problem Details JSON.
  _Test first:_ Write `Rfc9457ErrorTest` (integration):
  - Transform error → `502 Bad Gateway` with `{"type": "...", "title":
    "Transform Error", "status": 502, "detail": "..."}`.
  - Verify `Content-Type: application/problem+json`.
  - Verify `X-Request-ID` included in error responses.
  _Implement:_ Create `ProblemDetailBuilder` or reuse Feature 001's
  `ErrorResponseBuilder`. Register Javalin exception handlers.
  _Verify:_ `Rfc9457ErrorTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*Rfc9457ErrorTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-27** — Backend error responses (FR-004-24/25, S-004-18/19/20)
  _Intent:_ Backend connectivity failures produce structured error responses.
  _Test first:_ Write `BackendErrorTest` (integration):
  - Backend unreachable → `502 Bad Gateway` RFC 9457 (S-004-18).
  - Backend timeout → `504 Gateway Timeout` RFC 9457 (S-004-19).
  - Backend connection refused → `502 Bad Gateway` (S-004-20).
  _Implement:_ Catch upstream exceptions in `ProxyHandler`, wrap in RFC 9457.
  _Verify:_ `BackendErrorTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*BackendErrorTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-28** — Non-JSON body rejection (FR-004-26, S-004-21/55)
  _Intent:_ When a profile matches by path/method but the body is non-JSON,
  return `400 Bad Request`.
  _Test first:_ Write `NonJsonBodyTest` (integration):
  - `POST /api/orders` with `Content-Type: text/xml` matching profile →
    `400 Bad Request` RFC 9457 (S-004-55).
  - `POST /api/orders` with invalid JSON body → `400 Bad Request` (S-004-21).
  - No matching profile → passthrough regardless of content type.
  _Implement:_ Add JSON parse validation in `ProxyHandler` before transform.
  _Verify:_ `NonJsonBodyTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*NonJsonBodyTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-29** — Request body size enforcement (FR-004-13, S-004-22)
  _Intent:_ Reject requests exceeding `proxy.max-body-bytes` with `413 Payload
  Too Large`. Enforce at Jetty I/O layer for chunked-encoded requests.
  _Test first:_ Write `RequestBodySizeTest` (integration):
  - Body within limit → accepted and forwarded.
  - Body exceeding limit → `413 Payload Too Large` RFC 9457 (S-004-22).
  - Chunked request exceeding limit → rejected mid-stream.
  - Custom limit via `proxy.max-body-bytes` config key.
  _Implement:_ Configure `HttpConfiguration.setMaxRequestContentSize()` on
  Jetty. Register custom error handler for Jetty's default 413 response.
  _Verify:_ `RequestBodySizeTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*RequestBodySizeTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-30** — Response body size enforcement (FR-004-13, S-004-56)
  _Intent:_ Backend responses exceeding `proxy.max-body-bytes` produce `502`.
  _Test first:_ Write `ResponseBodySizeTest` (integration):
  - Backend response within limit → accepted.
  - Backend response exceeding limit → `502 Bad Gateway` RFC 9457 (S-004-56).
  _Implement:_ Check response body size after reading in `UpstreamClient`.
  _Verify:_ `ResponseBodySizeTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ResponseBodySizeTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-31** — X-Forwarded-* headers (FR-004-36, S-004-57/58/59)
  _Intent:_ Add `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host`
  when enabled. Append to existing `X-Forwarded-For`.
  _Test first:_ Write `ForwardedHeadersTest` (integration):
  - Default (enabled) → upstream receives `X-Forwarded-For` (client IP),
    `X-Forwarded-Proto`, `X-Forwarded-Host` (S-004-57).
  - Disabled → no forwarded headers added (S-004-58).
  - Existing `X-Forwarded-For` → client IP appended (S-004-59).
  _Implement:_ Add forwarded header injection in `ProxyHandler` before
  upstream forwarding.
  _Verify:_ `ForwardedHeadersTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ForwardedHeadersTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-32** — Unknown HTTP method rejection (FR-004-05, S-004-23)
  _Intent:_ Reject unknown HTTP methods with `405 Method Not Allowed`.
  _Test first:_ Write `HttpMethodTest` (integration):
  - `PROPFIND /api/users` → `405 Method Not Allowed`.
  - All seven valid methods → proxied correctly.
  _Implement:_ Add method whitelist check in `ProxyHandler`.
  _Verify:_ `HttpMethodTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*HttpMethodTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 5 — Health, Readiness, Hot Reload, Admin

#### I7 — Health + readiness endpoints

- [ ] **T-004-33** — Health endpoint (FR-004-21, S-004-34)
  _Intent:_ `GET /health` returns `200 {"status": "UP"}` when server is running.
  _Test first:_ Write `HealthEndpointTest` (integration):
  - `GET /health` → `200 {"status": "UP"}` (S-004-34).
  - Custom health path via `health.path` config.
  _Implement:_ Register Javalin handler for health endpoint.
  _Verify:_ `HealthEndpointTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*HealthEndpointTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-34** — Readiness endpoint (FR-004-22, S-004-35/36/37)
  _Intent:_ `GET /ready` checks engine and backend status.
  _Test first:_ Write `ReadinessEndpointTest` (integration):
  - Engine loaded + backend reachable → `200 {"status": "READY", "engine":
    "loaded", "backend": "reachable"}` (S-004-35).
  - Before engine loads → `503 {"status": "NOT_READY"}` (S-004-36).
  - Backend unreachable → `503 {"status": "NOT_READY", "reason":
    "backend_unreachable"}` (S-004-37).
  - Custom ready path via `health.ready-path` config.
  _Implement:_ Register Javalin handler. Backend check via TCP connect with
  `backend.connect-timeout-ms` timeout.
  _Verify:_ `ReadinessEndpointTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ReadinessEndpointTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-35** — Health/readiness not subject to transforms (S-004-38, S-004-70)
  _Intent:_ Admin and health endpoints must NOT be subject to profile matching,
  even if a wildcard profile would match.
  _Test first:_ Write `EndpointPriorityTest` (integration):
  - Profile with `path: /*` exists → `GET /health` still returns health
    response (S-004-38).
  - Profile with `path: /health` exists → health endpoint still wins
    (S-004-70).
  - Profile with `path: /admin/reload` exists → admin endpoint still wins.
  _Implement:_ Check for admin/health paths before entering transform pipeline.
  _Verify:_ `EndpointPriorityTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*EndpointPriorityTest*"`
  - `./gradlew spotlessApply check`

#### I8 — FileWatcher + admin reload

- [ ] **T-004-36** — FileWatcher: spec file change detection (FR-004-19, S-004-29)
  _Intent:_ `WatchService`-based hot reload trigger with configurable debounce.
  _Test first:_ Write `FileWatcherTest`:
  - Save new spec file to watched directory → watcher detects → callback fired
    (S-004-29).
  - Debounce: rapid saves → only one callback after debounce period (S-004-32).
  - Disabled via `reload.enabled: false` → no watching.
  _Implement:_ Create `FileWatcher` (IMPL-004-04) using `java.nio.file.WatchService`.
  _Verify:_ `FileWatcherTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*FileWatcherTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-37** — Admin reload endpoint (FR-004-20, S-004-30/31)
  _Intent:_ `POST /admin/reload` triggers engine reload manually.
  _Test first:_ Write `AdminReloadTest` (integration):
  - `POST /admin/reload` → engine reloads → `200 {"status": "reloaded",
    "specs": N, "profile": "id"}` (S-004-30).
  - Reload with broken spec → `500 Internal Server Error` with RFC 9457 body
    → previous registry preserved (S-004-31).
  - Admin endpoint not subject to profile matching.
  _Implement:_ Register Javalin POST handler for admin reload path
  (configurable via `admin.reload-path`).
  _Verify:_ `AdminReloadTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*AdminReloadTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-38** — Hot reload integration: FileWatcher → engine reload (FR-004-19, S-004-30)
  _Intent:_ End-to-end: file change → watcher fires → `TransformEngine.reload()`
  → new spec available for subsequent requests.
  _Test first:_ Write `HotReloadIntegrationTest` (integration):
  - Start proxy with spec A → transform works → write spec B to directory →
    watcher detects → subsequent requests use spec B (S-004-30).
  - Reload failure (broken spec B) → old spec A still works (S-004-31).
  _Implement:_ Wire `FileWatcher` callback to `TransformEngine.reload()`.
  _Verify:_ `HotReloadIntegrationTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*HotReloadIntegrationTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-39** — Zero-downtime reload under traffic (NFR-004-05, S-004-33/66)
  _Intent:_ In-flight requests complete with the old registry; new requests
  use the new registry. No mixed results.
  _Test first:_ Write `ZeroDowntimeReloadTest` (integration):
  - Start 10 concurrent requests → trigger reload mid-flight → in-flight
    requests complete with old registry → new requests use new registry
    (S-004-66).
  - No request failures during reload (S-004-33).
  _Implement:_ Relies on `AtomicReference<TransformRegistry>` in core engine.
  _Verify:_ `ZeroDowntimeReloadTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ZeroDowntimeReloadTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 6 — TLS

#### I9 — Inbound + outbound TLS

- [ ] **T-004-40** — Generate self-signed test certificates (FX-004-06/07/08)
  _Intent:_ Create TLS test fixtures: server keystore, client keystore, CA
  truststore in PKCS12 format.
  _Implement:_ Use `keytool` commands to generate:
  - `server.p12` — server cert + key (FX-004-06).
  - `client.p12` — client cert + key for mTLS (FX-004-07).
  - `truststore.p12` — CA trust entries (FX-004-08).
  Place in `adapter-standalone/src/test/resources/tls/`.
  _Verify:_ Files exist, keystores are loadable.
  _Verification commands:_
  - N/A — file creation only.

- [ ] **T-004-41** — Inbound TLS: HTTPS server (FR-004-14, S-004-39)
  _Intent:_ Proxy serves HTTPS when `proxy.tls.enabled: true`.
  _Test first:_ Write `InboundTlsTest` (integration):
  - `proxy.tls.enabled: true` + valid keystore → client connects via HTTPS
    (S-004-39).
  - HTTP connection to HTTPS port → connection rejected.
  _Implement:_ Configure Jetty `SslContextFactory.Server` with keystore from
  config. Create HTTPS-only `ServerConnector`.
  _Verify:_ `InboundTlsTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*InboundTlsTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-42** — Inbound mTLS: client certificate verification (FR-004-15, S-004-40)
  _Intent:_ When `proxy.tls.client-auth: need`, clients must present valid cert.
  _Test first:_ Write `InboundMtlsTest` (integration):
  - `client-auth: need` + client presents valid cert → connection accepted
    (S-004-40).
  - `client-auth: need` + no client cert → TLS handshake rejected.
  - `client-auth: want` + no client cert → connection accepted (S-004-40 variant).
  _Implement:_ Configure `SslContextFactory.Server.setNeedClientAuth()` /
  `setWantClientAuth()`. Set truststore.
  _Verify:_ `InboundMtlsTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*InboundMtlsTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-43** — Outbound TLS: HTTPS to backend (FR-004-16, S-004-41)
  _Intent:_ Proxy validates backend cert against configured truststore.
  _Test first:_ Write `OutboundTlsTest` (integration with HTTPS mock backend):
  - `backend.scheme: https` → proxy connects via TLS → validates backend cert
    (S-004-41).
  - Backend cert not in truststore → `502 Bad Gateway`.
  - `verify-hostname: false` → hostname validation skipped.
  _Implement:_ Build `SSLContext` for `HttpClient` with truststore from config.
  _Verify:_ `OutboundTlsTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*OutboundTlsTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-44** — Outbound mTLS: client cert to backend (FR-004-17, S-004-42)
  _Intent:_ Proxy presents client certificate when `backend.tls.keystore` set.
  _Test first:_ Write `OutboundMtlsTest` (integration with mTLS mock backend):
  - `backend.tls.keystore` configured → proxy presents client cert → backend
    accepts (S-004-42).
  _Implement:_ Add key material to `SSLContext` for `HttpClient`.
  _Verify:_ `OutboundMtlsTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*OutboundMtlsTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-45** — TLS config validation at startup (S-004-43)
  _Intent:_ Invalid keystore paths, wrong passwords, or missing files produce
  descriptive startup errors.
  _Test first:_ Write `TlsConfigValidationTest`:
  - Invalid keystore path → startup fails with descriptive error (S-004-43).
  - Wrong keystore password → startup fails.
  - Missing truststore for mTLS → startup fails.
  _Implement:_ Add TLS validation to startup sequence.
  _Verify:_ `TlsConfigValidationTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*TlsConfigValidationTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 7 — Startup, Shutdown, Logging

#### I10 — Startup sequence + graceful shutdown + structured logging

- [ ] **T-004-46** — StandaloneMain: startup sequence (FR-004-27, IMPL-004-05, S-004-44)
  _Intent:_ Implement the full startup sequence: load config → register engines
  → load specs/profiles → init HttpClient → start Javalin → start FileWatcher.
  Log structured startup summary.
  _Test first:_ Write `StartupSequenceTest` (integration):
  - Valid config + specs + profile → server starts → logs summary with port,
    backend, spec count, registered engines (S-004-44).
  - Startup time < 3 seconds with ≤ 50 specs (NFR-004-01 — soft check).
  _Implement:_ Create `StandaloneMain` (IMPL-004-05). Orchestrate startup.
  _Verify:_ `StartupSequenceTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*StartupSequenceTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-47** — Startup failure handling (S-004-45)
  _Intent:_ Invalid config or broken specs produce a clean startup failure.
  _Test first:_ Write `StartupFailureTest`:
  - Invalid config → exit with non-zero status and descriptive error.
  - Spec with bad JSLT → `TransformLoadException` → exit non-zero (S-004-45).
  - Zero specs → valid startup, all requests passthrough (FR-004-27 note).
  _Implement:_ Add try/catch in `StandaloneMain.main()`.
  _Verify:_ `StartupFailureTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*StartupFailureTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-48** — Graceful shutdown (FR-004-28, S-004-46/47)
  _Intent:_ SIGTERM/SIGINT triggers graceful shutdown: stop accepting, drain
  in-flight, close clients, stop watcher, exit 0.
  _Test first:_ Write `GracefulShutdownTest` (integration):
  - SIGTERM → server stops accepting → in-flight requests complete → exit 0
    (S-004-46).
  - Long-running request during shutdown → completes before exit (S-004-47).
  - Drain timeout exceeded → force close (configurable via
    `proxy.shutdown.drain-timeout-ms`).
  _Implement:_ Register JVM shutdown hook. Configure Javalin/Jetty graceful
  shutdown with drain timeout.
  _Verify:_ `GracefulShutdownTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*GracefulShutdownTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-49** — Logback configuration: structured JSON logging (NFR-004-07, CFG-004-37/38)
  _Intent:_ Configure Logback for structured JSON logging with configurable
  format (`json` or `text`) and level.
  _Test first:_ Write `StructuredLoggingTest` (integration):
  - JSON format → log entries are valid JSON containing timestamp, level, thread,
    message.
  - Text format → human-readable log lines.
  - Log level configurable via `logging.level` config key.
  _Implement:_ Create `logback.xml` with JSON encoder (logstash-logback-encoder
  or Logback's built-in `JsonLayout`). Support runtime format switching.
  _Verify:_ `StructuredLoggingTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*StructuredLoggingTest*"`
  - `./gradlew spotlessApply check`

- [ ] **T-004-50** — Structured log fields in ProxyHandler (NFR-004-07)
  _Intent:_ Every proxied request produces a structured log entry with:
  request ID, HTTP method, request path, response status, total proxy latency,
  upstream latency, transform result type, backend host.
  _Test first:_ Write `ProxyLogFieldsTest` (integration):
  - Send request → capture log output → verify JSON contains all required
    fields per NFR-004-07.
  - Passthrough request → includes `transform_result: PASSTHROUGH`.
  - Transform request → includes `transform_result: SUCCESS`.
  - Error → includes `transform_result: ERROR`.
  _Implement:_ Add MDC fields in `ProxyHandler`. Use `System.nanoTime()` for
  latency measurement.
  _Verify:_ `ProxyLogFieldsTest` passes.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*ProxyLogFieldsTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 8 — Docker Packaging + Integration Test Sweep

#### I11 — Dockerfile + shadow JAR

- [ ] **T-004-51** — Shadow plugin + fat JAR (FR-004-30)
  _Intent:_ Add Shadow plugin to produce a single executable JAR.
  _Implement:_
  1. Add Shadow plugin to `adapter-standalone/build.gradle.kts`.
  2. Configure main class in JAR manifest.
  3. `./gradlew :adapter-standalone:shadowJar` → produces fat JAR.
  4. `java -jar proxy.jar --config minimal-config.yaml` → starts proxy.
  _Verify:_ Fat JAR starts the proxy.
  _Verification commands:_
  - `./gradlew :adapter-standalone:shadowJar`
  - `ls -la adapter-standalone/build/libs/*-all.jar`

- [ ] **T-004-52** — Dockerfile: multi-stage build (FR-004-29, FX-004-09)
  _Intent:_ Create multi-stage `Dockerfile` — JDK 21 build + JRE Alpine runtime.
  _Implement:_ Create `adapter-standalone/Dockerfile`:
  - Stage 1: `eclipse-temurin:21-jdk-alpine` — build with Gradle.
  - Stage 2: `eclipse-temurin:21-jre-alpine` — copy shadow JAR, expose port,
    set entrypoint.
  - Volume mount points for `/specs` and `/profiles`.
  _Verify:_ `docker build -t message-xform-proxy .` succeeds.
  _Verification commands:_
  - `docker build -t message-xform-proxy -f adapter-standalone/Dockerfile .`

- [ ] **T-004-53** — Docker image size verification (NFR-004-04, S-004-48)
  _Intent:_ Docker image must be < 150 MB compressed.
  _Test:_
  - `docker images message-xform-proxy --format '{{.Size}}'` → < 150 MB.
  _Verify:_ Image size assertion.
  _Verification commands:_
  - `docker images message-xform-proxy`

- [ ] **T-004-54** — Docker smoke test (S-004-49/50/51)
  _Intent:_ Container starts, serves health check, loads specs from volume mount.
  _Test:_
  - `docker run` with env vars → container starts (S-004-49).
  - `GET /health` returns 200 from container.
  - Volume mount for specs/profiles works (S-004-50).
  - Shadow JAR starts cleanly (S-004-51).
  _Verify:_ Docker run + health check.
  _Verification commands:_
  - `docker run -d --name proxy-test -p 9090:9090 -e BACKEND_HOST=httpbin.org message-xform-proxy`
  - `curl http://localhost:9090/health`
  - `docker stop proxy-test && docker rm proxy-test`

#### I12 — Full integration test sweep + scenario coverage matrix

- [ ] **T-004-55** — Create test fixtures (FX-004-04/05)
  _Intent:_ Create integration test fixtures for transform specs and profiles.
  _Implement:_ Create in `adapter-standalone/src/test/resources/`:
  - `specs/test-transform.yaml` (FX-004-04) — simple JSLT spec.
  - `profiles/test-profile.yaml` (FX-004-05) — profile matching test routes.
  _Verify:_ Files exist, valid YAML, parseable by core engine.
  _Verification commands:_
  - N/A — file creation only.

- [ ] **T-004-56** — Full integration test sweep (all 77 scenarios)
  _Intent:_ Verify all scenarios pass. Fix any gaps found during sweep.
  _Test:_ Run full test suite. Map results to scenario coverage.
  _Implement:_ Fix any failing scenarios.
  _Verify:_ All integration tests green.
  _Verification commands:_
  - `./gradlew :adapter-standalone:test`
  - `./gradlew spotlessApply check`

- [ ] **T-004-57** — Create scenarios.md with coverage matrix
  _Intent:_ Map each scenario (S-004-01 through S-004-77) to its passing test
  class and method. Create the coverage matrix table.
  _Implement:_ Create `docs/architecture/features/004/scenarios.md`.
  _Verify:_ Every scenario has a test class reference.
  _Verification commands:_
  - N/A — documentation task.

- [ ] **T-004-58** — NFR verification
  _Intent:_ Verify all non-functional requirements.
  _Test:_
  - NFR-004-01: Startup time < 3s — benchmark.
  - NFR-004-02: Passthrough overhead < 5ms p95 — benchmark.
  - NFR-004-03: Heap < 256 MB under load — JFR/VisualVM.
  - NFR-004-06: 1000 concurrent connections — load test (S-004-65).
  _Implement:_ Create `NfrVerificationTest` (opt-in benchmark).
  _Verify:_ All NFR targets met (soft-assert, log warning if borderline).
  _Verification commands:_
  - `./gradlew :adapter-standalone:test --tests "*NfrVerificationTest*"`

- [ ] **T-004-59** — Drift gate report
  _Intent:_ Execute the full drift gate checklist. Verify spec ↔ code alignment
  for every FR and NFR.
  _Implement:_ Fill in the Drift Report section in `plan.md`.
  _Verify:_ All checklist items pass. Report committed.
  _Verification commands:_
  - `./gradlew spotlessApply check`
  - `./gradlew :adapter-standalone:test`

- [ ] **T-004-60** — Update knowledge-map.md
  _Intent:_ Update `docs/architecture/knowledge-map.md` to reflect the
  standalone adapter's active development status and module relationships.
  _Implement:_ Add `adapter-standalone` module to the knowledge map diagram.
  _Verify:_ Knowledge map correctly represents the new module.
  _Verification commands:_
  - N/A — documentation task.

---

## Verification Log

Track long-running or shared commands with timestamps to avoid duplicate work.

_To be populated during implementation._

## Completion Criteria

- [ ] All 60 tasks checked off
- [ ] Quality gate passes (`./gradlew spotlessApply check`)
- [ ] All 77 scenarios verified — mapped to test classes in coverage matrix
- [ ] Coverage matrix in `scenarios.md` has no uncovered FRs/NFRs
- [ ] Implementation Drift Gate report attached to plan
- [ ] Open questions resolved and removed from `open-questions.md`
- [ ] Docker image builds and passes smoke test
- [ ] Documentation synced: roadmap status → ✅ Complete

## Notes / TODOs

- **Javalin version:** Pin exact Javalin 6 version in version catalog. Check
  Jetty 12 transitive version for TLS API compatibility.
- **WireMock vs embedded server:** Decide on mock backend strategy in T-004-09.
  WireMock is heavier but more expressive. A lightweight embedded HTTP server
  (Jetty or `com.sun.net.httpserver`) may suffice.
- **TLS test certs:** Generated once in T-004-40, reused across all TLS tests.
  Consider adding cert generation to a Gradle task for reproducibility.
- **Docker in CI:** Docker build/test tasks (T-004-52..54) require Docker daemon.
  Guard with `assumeTrue(isDockerAvailable())` for environments without Docker.
- **env var testing:** T-004-07 tests env var overlay. Consider using
  `ProcessBuilder` with env vars or a custom `System.getenv()` abstraction
  for testability.
