# Feature 004 – Standalone HTTP Proxy Mode

| Field | Value |
|-------|-------|
| Status | Ready |
| Last updated | 2026-02-08 |
| Owners | Ivan |
| Linked plan | `docs/architecture/features/004/plan.md` |
| Linked tasks | `docs/architecture/features/004/tasks.md` |
| Roadmap entry | #4 – Standalone HTTP Proxy Mode |
| Depends on | Feature 001 (core engine; minor API addition — Q-042) |
| Research | `docs/research/standalone-proxy-http-server.md` |
| Decisions | ADR-0029 (Javalin 6 / Jetty 11) |

> Guardrail: This specification is the single normative source of truth for the feature.
> Track questions in `docs/architecture/open-questions.md`, encode resolved answers
> here, and use ADRs under `docs/decisions/` for architectural clarifications.

## Overview

The message-xform engine running as an independent **HTTP reverse proxy** without any
gateway. It intercepts HTTP request/response pairs, applies matching transform profiles
via the `TransformEngine`, and forwards traffic to a configured backend. The proxy
implements the `GatewayAdapter` SPI and serves as the **first concrete adapter**
and **reference implementation** for all subsequent gateway integrations.

The standalone proxy is packaged as a **Java application** (shadow JAR) and a
**Docker image**, enabling three deployment models:

1. **Local development & testing** — run transforms against live HTTP traffic
   without deploying to a gateway.
2. **Kubernetes sidecar** — deploy alongside a backend pod, intercepting and
   transforming traffic transparently.
3. **Standalone service** — run as an independent Kubernetes Deployment with its
   own Service, fronting any backend.

**Affected modules:** `adapter-standalone` (new Gradle submodule), `core` (minor
API addition: `TransformEngine.transform(Message, Direction, TransformContext)`
overload — Q-042).

## Goals

- G-004-01 – Implement a fully functional HTTP reverse proxy using Javalin 6
  (Jetty 11) with Java 21 virtual threads for concurrency (ADR-0029).
- G-004-02 – Implement `GatewayAdapter<Context>` — the first concrete adapter
  implementation — serving as a reference for all subsequent gateway adapters.
- G-004-03 – Apply request and response transformations via `TransformEngine`,
  forwarding transformed requests to the configured backend and returning
  transformed responses to the client.
- G-004-04 – Provide a comprehensive YAML configuration with environment variable
  overrides for all operational knobs (no rebuild required for tuning).
- G-004-05 – Support TLS for both inbound (client → proxy) and outbound
  (proxy → backend) connections, configurable via YAML.
- G-004-06 – Trigger hot-reload of specs and profiles via file-system watching
  (`WatchService`) and an admin HTTP endpoint.
- G-004-07 – Expose health and readiness endpoints for Kubernetes probes.
- G-004-08 – Package the proxy as a Docker image suitable for Kubernetes
  sidecar and standalone service deployments.

## Non-Goals

- **Multi-backend routing.** Each proxy instance forwards to exactly one backend
  (ADR-0029, Q-030). Multiple backends require multiple proxy instances.
- **Backend authentication (v1).** API key, JWT, OAuth token injection are out of
  scope for v1. The config schema includes a reserved `auth` block for future use.
- **WebSocket proxying.** Feature 004 handles HTTP request/response pairs only.
- **HTTP/2 upstream.** Outbound connections use HTTP/1.1 in v1.
- **Rate limiting / circuit breaker.** Use external tools (Envoy, Istio, NGINX).
- **Service discovery.** Backend host/port is static configuration. Use Kubernetes
  DNS for dynamic resolution.
- **Metrics endpoint (v1).** Prometheus/Micrometer integration is future scope.
  Structured JSON logging provides observability in v1.
- **Admin endpoint authentication (v1).** The `/admin/reload` endpoint has no
  access control in v1. It accepts no payload — it only triggers a reload of
  specs already on disk. Use Kubernetes NetworkPolicy to restrict access in
  production. The config schema reserves an `admin` block for future auth.

---

## Functional Requirements

### Proxy Core

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-01 | The proxy MUST receive HTTP requests on a configurable host and port, forward them to the configured backend, and return the backend's response to the client. | Client sends `GET /api/users` → proxy forwards to `backend:8080/api/users` → backend returns 200 → proxy returns 200 to client. | Startup fails if `proxy.port` is already in use or `backend.host` is missing. | Backend unreachable → proxy returns `502 Bad Gateway` with RFC 9457 error body. | Core proxy behaviour. |
| FR-004-02 | The proxy MUST apply **request transformation** via `TransformEngine.transform(message, REQUEST)` before forwarding to the backend. | Profile matches `POST /api/orders` → JSLT transforms request body → transformed body sent to backend. | Non-matching request → `PASSTHROUGH` → original request forwarded unmodified. | Transform error → proxy returns error response to client (FR-004-23), request is NOT forwarded. | GatewayAdapter SPI, Feature 001. |
| FR-004-03 | The proxy MUST apply **response transformation** via `TransformEngine.transform(message, RESPONSE)` before returning the backend's response to the client. | Profile matches response → JSLT transforms response body → transformed body returned to client. | Non-matching response → `PASSTHROUGH` → original response returned unmodified. | Transform error → proxy returns error response to client (FR-004-23). | GatewayAdapter SPI, Feature 001. |
| FR-004-04 | The proxy MUST forward the HTTP method, path, query string, and headers from the (potentially transformed) request to the backend. | `PUT /api/users/123?fields=name` with `Authorization: Bearer xxx` → all forwarded to backend. | Hop-by-hop headers (`Connection`, `Transfer-Encoding`, etc.) MUST be stripped per RFC 7230 §6.1 in both request and response directions. | n/a | HTTP proxy semantics. |
| FR-004-05 | The proxy MUST support `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, and `OPTIONS` HTTP methods. | All seven methods are proxied without modification (unless a URL rewrite transform changes the method). | Unknown method → proxy returns `405 Method Not Allowed`. | n/a | HTTP/1.1a standard. |

> **Rationale for rejecting unknown methods (FR-004-05):** A general-purpose reverse
> proxy would forward any method. This proxy is purpose-built for JSON body
> transformation, and non-standard methods (e.g., WebDAV `PROPFIND`) are unlikely
> to carry transformable payloads. Restricting to seven well-known methods keeps the
> proxy's surface area predictable. If future adapters need to support additional
> methods, this can be relaxed without breaking existing behaviour.

### GatewayAdapter Implementation

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-06 | The proxy MUST implement `GatewayAdapter<Context>` where `Context` is Javalin's request context, providing `wrapRequest`, `wrapResponse`, and `applyChanges`. | `wrapRequest(ctx)` extracts: body → `JsonNode`, `headers` (single-value, lowercase keys), `headersAll` (multi-value, lowercase keys), `contentType`, `requestPath`, `requestMethod`, `queryString` (raw, without leading `?`), and `statusCode` (null for requests). Returns a fully populated `Message`. | Request with no body → `Message.body()` is `NullNode` (per `Message` contract — body is never null). Response with no body (e.g., `204 No Content`) → `Message.body()` is `NullNode`. | JSON parse error on request body → proxy returns `400 Bad Request` with RFC 9457 error. | SPI-001-04/05/06, ADR-0013. |
| FR-004-06a | Before calling `wrapResponse(ctx)`, `ProxyHandler` MUST populate the Javalin `Context` with the upstream backend's response data: body (`ctx.result()`), headers (`ctx.header()`), and status code (`ctx.status()`). This ensures `wrapResponse` reads from `Context` uniformly, mirroring how real gateway adapters (PingAccess, PingGateway) naturally have the response in their native object. | `UpstreamClient.forward()` returns `HttpResponse` → ProxyHandler writes body, headers, status into `ctx` → `wrapResponse(ctx)` reads from `ctx`. | n/a | n/a | Q-034 resolution. |
| FR-004-06b | `wrapResponse` MUST include the original request's path (`ctx.path()`) and method (`ctx.method()`) in the returned `Message`, since profile matching for response transforms operates on request criteria (Feature 001 API-001-01). `wrapResponse` MUST populate: body → `JsonNode`, `headers` (single-value, lowercase keys), `headersAll` (multi-value, lowercase keys), `contentType`, `statusCode`, `requestPath` (from original request), `requestMethod` (from original request). `queryString` is set to `null` for responses. | `wrapResponse(ctx)` → `Message` with `requestPath = ctx.path()`, `requestMethod = ctx.method()`, upstream body/headers/status. | n/a | n/a | Feature 001 API-001-01. |
| FR-004-07 | `wrapRequest` and `wrapResponse` MUST create **deep copies** of the native message data, consistent with copy-on-wrap semantics (ADR-0013). | Mutations to the `Message` returned by `wrapRequest` do not affect the original Javalin context until `applyChanges` is called. | n/a | n/a | ADR-0013. |
| FR-004-08 | `applyChanges` MUST write the transformed `Message` fields (body, headers, status code) back to the Javalin response context. **`applyChanges` is called only for RESPONSE SUCCESS** — request transforms flow directly to `UpstreamClient.forward(result.message())` without calling `applyChanges` (see FR-004-35 dispatch table). | Transformed body, headers, and status code are written to `ctx.result()`, `ctx.header()`, `ctx.status()`. | If the transformed `Message.body()` is null, `applyChanges` MUST set an empty response body. | n/a | SPI-001-06. |
| FR-004-09 | Header names MUST be normalized to lowercase in `wrapRequest` and `wrapResponse`, consistent with core engine conventions. This applies to both `headers` (single-value) and `headersAll` (multi-value) maps. | `Content-Type: application/json` → `content-type: application/json` in `Message.headers()` and `Message.headersAll()`. | n/a | n/a | Feature 001 spec. |

### Configuration

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-10 | The proxy MUST load configuration from a YAML file (`message-xform-proxy.yaml` by default) at startup. | Proxy reads `./message-xform-proxy.yaml` → starts with configured settings. | Config file path overridable via `--config` CLI argument. | Config file not found → proxy exits with error and usage message. | Research §5. |
| FR-004-11 | Every configuration key MUST be overridable via an environment variable. Env vars take precedence over YAML values. | `BACKEND_HOST=my-service` overrides `backend.host: localhost` in YAML. | Env var with empty string or whitespace-only value → treated as "unset", YAML value used. An env var is considered "set" if and only if it is defined AND its trimmed value is non-empty. | n/a | Docker/K8s deployment. |
| FR-004-12 | The backend MUST be configured via a structured object with `scheme`, `host`, and `port` fields (not a raw URL string). | `backend.scheme: https`, `backend.host: api.example.com`, `backend.port: 443` → proxy connects to `https://api.example.com:443`. | Missing `backend.host` → startup fails with descriptive error. `backend.scheme` defaults to `http`. `backend.port` defaults to `80` (http) or `443` (https). | Invalid scheme (not `http` or `https`) → startup fails. | Q-030 resolution. |

### Body Size Enforcement

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-13 | The proxy MUST enforce a configurable maximum body size (`proxy.max-body-bytes`, default 10 MB) in **both directions**. Requests exceeding this limit MUST receive `413 Payload Too Large` without forwarding to the backend. Backend responses exceeding this limit MUST result in `502 Bad Gateway` returned to the client (the response is too large to buffer and transform safely). **Implementation:** For inbound requests, the limit MUST be enforced at the Jetty I/O layer via `HttpConfiguration.setMaxRequestContentSize()` so that chunked-encoded requests are rejected mid-stream without fully buffering the body. Jetty's default `413` error response MUST be wrapped in an RFC 9457 problem detail body via a custom Javalin error handler. For backend responses, the limit is checked after the response body is read (since the proxy controls the `HttpClient` read buffer). | `POST /api/data` with 5 MB body → accepted and forwarded. Backend response 3 MB → accepted and returned. | `proxy.max-body-bytes: 1048576` → 1 MB limit applied to both directions. | Request 15 MB → `413 Payload Too Large` (rejected mid-stream for chunked). Response 15 MB → `502 Bad Gateway`. | Q-031, Q-037, Q-039, Q-040 resolution, ADR-0018. |

### TLS

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-14 | The proxy MUST support **inbound TLS** (serving HTTPS) when `proxy.tls.enabled: true`. | Client connects via HTTPS → Jetty terminates TLS using configured keystore. | `proxy.tls.keystore` must exist and be readable. | Invalid keystore or password → startup fails with descriptive error. | Q-032 resolution. |
| FR-004-15 | The proxy MUST support **inbound mutual TLS** (client certificate verification) when `proxy.tls.client-auth: need`. | Client presents valid certificate from configured truststore → connection accepted. | `proxy.tls.client-auth: want` → client cert requested but not required. | Client presents no cert or invalid cert with `client-auth: need` → TLS handshake rejected. | Q-032 resolution. |
| FR-004-16 | The proxy MUST support **outbound TLS** (connecting to HTTPS backend) when `backend.scheme: https`, validating the backend's certificate against a configured truststore. | Proxy connects to `https://backend:8443` → validates cert against `backend.tls.truststore`. | `backend.tls.verify-hostname: true` (default) → hostname verification enabled. | Backend cert not in truststore or hostname mismatch → connection rejected, client receives `502 Bad Gateway`. | Q-032 resolution. |
| FR-004-17 | The proxy MUST support **outbound mutual TLS** (presenting a client certificate to the backend) when `backend.tls.keystore` is configured. | Proxy presents client cert from `backend.tls.keystore` → backend accepts. | Keystore must exist and be readable. | Invalid keystore → startup fails with descriptive error. | Q-032 resolution. |

### Connection Pool

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-18 | The proxy MUST support configurable upstream connection pool settings: `backend.pool.max-connections`, `backend.pool.keep-alive`, `backend.pool.idle-timeout-ms`. | Pool reuses connections for successive requests to the same backend. | Default: `max-connections: 100`, `keep-alive: true`, `idle-timeout-ms: 60000`. | Pool exhausted → request queued or rejected depending on JDK HttpClient behaviour; logged as warning. | Research §5, Connection Pool Notes. |

> **JDK HttpClient pool limitation:** The JDK `HttpClient` exposes pool tuning
> via JVM-global system properties (`jdk.httpclient.connectionPoolSize`,
> `jdk.httpclient.keepalive.timeout`), not per-instance configuration. The
> `backend.pool.*` config values map to these system properties at startup.
> If finer-grained per-instance pool control is needed, `UpstreamClient` can
> be backed by Apache HttpClient 5 without changing the config schema.

### Hot Reload

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-19 | The proxy MUST watch `engine.specs-dir` and `engine.profiles-dir` for file changes using `java.nio.file.WatchService` (when `reload.enabled: true`) and trigger `TransformEngine.reload(specPaths, profilePath)`. On each reload, the proxy scans `engine.specs-dir` for all `*.yaml`/`*.yml` files and resolves the active profile from `engine.profile` (or `engine.profiles-dir`). | Save a new spec YAML → watcher detects → `reload()` → new spec available for subsequent requests. | Debounce (default 500ms) prevents rapid successive reloads during editing. | Reload fails (invalid YAML, schema error) → previous registry stays active, error logged. | NFR-001-05, Research §6. |
| FR-004-20 | The proxy MUST expose `POST /admin/reload` to trigger a reload. The handler scans `engine.specs-dir` for `*.yaml`/`*.yml` files and resolves the active profile (same logic as `FileWatcher`), then calls `TransformEngine.reload(specPaths, profilePath)`. | `POST /admin/reload` → engine reloads → `200 OK` with `{"status": "reloaded", "specs": N, "profile": "id"}`. | n/a | Reload fails → `500 Internal Server Error` with RFC 9457 error body (consistent with ADR-0022). Admin endpoints are NOT subject to transform matching. | Research §6. |

### Health & Readiness

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-21 | The proxy MUST expose `GET /health` (liveness probe). | Returns `200 OK` with `{"status": "UP"}` when the JVM and HTTP server are running. | Health endpoint is NOT subject to transform matching. | Server not accepting connections → probe fails (Kubernetes restarts pod). | K8s liveness probe. |
| FR-004-22 | The proxy MUST expose `GET /ready` (readiness probe). | Returns `200 OK` with `{"status": "READY", "engine": "loaded", "backend": "reachable"}` when engine has loaded specs AND backend is reachable (verified via TCP connect to `backend.host:backend.port` with `backend.connect-timeout-ms` timeout). | During startup (before engine loads) → `503 Service Unavailable`. After failed reload → still `200` (old registry is still active). | Backend unreachable → `503 Service Unavailable` with `{"status": "NOT_READY", "reason": "backend_unreachable"}`. | K8s readiness probe. |

> **Endpoint routing priority:** Admin (`/admin/reload`) and health/readiness
> (`/health`, `/ready`) endpoints MUST take precedence over profile-matched
> transform routes. If a profile happens to match the same path, the
> admin/health handler wins — the request is never subject to transformation.

### Error Handling

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-23 | Transform errors MUST return an RFC 9457 problem detail response to the client, consistent with Feature 001 error handling (ADR-0022). | Request transform fails → `502 Bad Gateway` with `{"type": "...", "title": "Transform Error", "status": 502, "detail": "..."}`. | n/a | n/a | ADR-0022, ADR-0024. |
| FR-004-24 | Backend connection failures MUST return `502 Bad Gateway` with a descriptive error body. | Backend host unreachable or connection refused → `502` with `{"type": "...", "title": "Backend Unreachable", "status": 502}`. | n/a | n/a | HTTP proxy semantics. |
| FR-004-25 | Backend response timeout MUST return `504 Gateway Timeout` with a descriptive error body. | Backend does not respond within `backend.read-timeout-ms` → `504`. | n/a | n/a | HTTP proxy semantics. |
| FR-004-26 | Malformed or non-JSON request body on a profile-matched route MUST return `400 Bad Request` with a descriptive RFC 9457 error body. If a profile matches by path/method, the proxy assumes JSON is expected — non-JSON content is a client error. Routes with **no** matching profile are passed through without body parsing. | POST with non-JSON body to a transform-matched route → `400 Bad Request`. | No matching profile → passthrough (no parse attempt). | n/a | ADR-0011, Q-036 resolution. |

### Startup & Shutdown

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-27 | On startup, the proxy MUST: (1) load config, (2) register expression engines (JSLT is registered by default; v1 does not support runtime engine registration — additional engines are future scope), (3) load and compile all specs, (4) load active profile, (5) initialize the HTTP client, (6) start the HTTP server, (7) start the file watcher. | Proxy starts in <3s (NFR-004-01), logs structured startup summary with port, backend, spec count, registered engines. | Config validation errors → exit with non-zero status and descriptive message. | Invalid spec YAML → startup fails with `TransformLoadException` (ADR-0024). Zero specs (empty `specs-dir`) is a valid configuration — all requests pass through. | ADR-0025. |
| FR-004-28 | On shutdown (SIGTERM/SIGINT), the proxy MUST: (1) stop accepting new connections, (2) wait for in-flight requests to complete (graceful drain, configurable via `proxy.shutdown.drain-timeout-ms`, default 30s), (3) close the HTTP client, (4) stop the file watcher, (5) exit. | Ctrl-C → graceful shutdown within drain timeout → exit 0. | In-flight requests complete normally during shutdown. | Drain timeout exceeded → force close remaining connections → exit 0. | Production deployment. |

### Docker & Kubernetes

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-29 | The project MUST produce a Docker image via multi-stage Dockerfile: JDK build stage + JRE Alpine runtime stage (base: `eclipse-temurin:21-jre-alpine`). | `docker build -t message-xform-proxy .` → image <150 MB. | n/a | Build failure → CI fails. | Research §5. |
| FR-004-30 | The Docker image MUST use the shadow JAR (single fat JAR containing `core` + `adapter-standalone` + all dependencies). | `java -jar proxy.jar` starts the proxy with no classpath setup. | n/a | n/a | Packaging. |
| FR-004-31 | The Docker image MUST expose volume mount points for spec and profile directories (`/specs`, `/profiles`). | `docker run -v ./my-specs:/specs -e BACKEND_HOST=api message-xform-proxy` → loads specs from mounted volume. | n/a | Empty `/specs` directory → engine loads with zero specs, all requests passthrough. | K8s deployment. |
| FR-004-32 | The Docker image MUST support Kubernetes ConfigMap mounting for spec and profile delivery. | K8s ConfigMap mounted at `/specs` → proxy loads specs from ConfigMap data. | n/a | n/a | K8s deployment. |

### Upstream Protocol & Headers

| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-004-33 | The proxy MUST force HTTP/1.1 for upstream connections (via `HttpClient.Version.HTTP_1_1`). | Proxy sends `GET /` via HTTP/1.1. | n/a | n/a | Non-Goal: HTTP/2 upstream. |
| FR-004-34 | The proxy MUST recalculate the `Content-Length` header based on the *transformed* body size for **both** upstream requests and client responses. It MUST NOT blindly copy the `Content-Length` from the original message in either direction. | Request: client sends 100 bytes → spec adds 50 bytes → upstream receives `Content-Length: 150`. Response: backend returns 200 bytes → spec removes 50 bytes → client receives `Content-Length: 150`. | n/a | n/a | HTTP framing correctness. |
| FR-004-36 | When `proxy.forwarded-headers.enabled` is `true` (default), the proxy MUST add `X-Forwarded-For` (client IP), `X-Forwarded-Proto` (`http` or `https` based on inbound scheme), and `X-Forwarded-Host` (original `Host` header value) to upstream requests. If these headers already exist (e.g., from an upstream proxy), the proxy MUST **append** to `X-Forwarded-For` and preserve existing `X-Forwarded-Proto`/`X-Forwarded-Host` values. When disabled, no forwarded headers are added or modified. | Client `10.0.0.5` via HTTPS → backend receives `X-Forwarded-For: 10.0.0.5`, `X-Forwarded-Proto: https`, `X-Forwarded-Host: api.example.com`. | `proxy.forwarded-headers.enabled: false` → no headers added. | n/a | Q-038 resolution, RFC 7239. |
| FR-004-37 | `wrapRequest` MUST parse cookies from the `Cookie` header (via Javalin's `ctx.cookieMap()`) and pass them to `TransformContext` as the `cookies` parameter. This enables `$cookies` binding in JSLT expressions (Feature 001 DO-001-07). `wrapResponse` does NOT populate cookies (cookies are a request-direction concept). | Request with `Cookie: session=abc123; lang=en` → `$cookies.session` evaluates to `"abc123"`, `$cookies.lang` evaluates to `"en"`. | Request with no `Cookie` header → `$cookies` is an empty map (not null). | n/a | Q-041 resolution, DO-001-07. |
| FR-004-38 | When the inbound request does NOT contain an `X-Request-ID` header, `ProxyHandler` MUST generate a random UUID and inject it as an `X-Request-ID` response header. If the header IS present, the proxy MUST echo the original value back in the response. In both cases the request ID is included in structured log entries (NFR-004-07). | Request without `X-Request-ID` → proxy generates UUID, logs with it, returns `X-Request-ID: <uuid>` in response. | Request with `X-Request-ID: abc-123` → proxy logs with `abc-123`, returns `X-Request-ID: abc-123` in response. | n/a | Observability best practice. |
| FR-004-39 | `wrapRequest` MUST parse query parameters from the URL (via Javalin's `ctx.queryParamMap()`) and pass them to `TransformContext` as the `queryParams` parameter. This enables `$queryParams` binding in JSLT expressions (Feature 001 DO-001-07). For multi-value query params, only the **first value** is used (consistent with single-value `$headers` semantics). `wrapResponse` does NOT populate query params (query params are a request-direction concept). | Request `GET /api?page=2&sort=name` → `$queryParams.page` evaluates to `"2"`, `$queryParams.sort` evaluates to `"name"`. | Request with no query string → `$queryParams` is an empty map (not null). | n/a | Q-043 resolution, DO-001-07. |

### TransformResult Dispatch

| ID | Requirement | Source |
|----|-------------|--------|
| FR-004-35 | `ProxyHandler` MUST dispatch on the `TransformResult` returned by `TransformEngine.transform()` as follows: | Q-033 resolution, FR-004-02/03/23. |

| Direction | `TransformResult` state | Proxy behaviour |
|-----------|------------------------|-----------------|
| REQUEST   | `SUCCESS`              | Forward `result.message()` to backend via `UpstreamClient`. |
| REQUEST   | `PASSTHROUGH`          | Forward the original (unwrapped) request to the backend unmodified. |
| REQUEST   | `ERROR`                | Return `result.errorResponse()` with `result.errorStatusCode()` to the client immediately. Do **NOT** forward to backend. |
| RESPONSE  | `SUCCESS`              | Return `result.message()` to client via `applyChanges(msg, ctx)`. |
| RESPONSE  | `PASSTHROUGH`          | Return the original backend response to the client unmodified. |
| RESPONSE  | `ERROR`                | Return `result.errorResponse()` with `result.errorStatusCode()` to the client. |

> **`PASSTHROUGH` forwarding:** On `PASSTHROUGH`, `ProxyHandler` forwards the
> raw data from the Javalin `Context` directly (request body bytes, original
> headers, original path/query), bypassing `Message` serialization. This avoids
> a needless parse→serialize round-trip for non-matching requests.

> **`applyChanges` call site:** `applyChanges(msg, ctx)` is called ONLY for
> RESPONSE SUCCESS — it writes the transformed body, headers, and status back
> to the Javalin context, which Javalin then sends to the client. For REQUEST
> SUCCESS, `ProxyHandler` reads from the transformed `Message` directly and
> passes it to `UpstreamClient.forward()`. This is an intentional deviation
> from the symmetric SPI pattern: the Javalin `Context` is the *response*
> writer, so writing a transformed *request* back to it makes no sense.

> **Cookie binding (Q-041):** `$cookies` context variable IS populated in v1.
> `wrapRequest` parses the `Cookie` header via `ctx.cookieMap()` and builds
> a `TransformContext` with cookies populated. The adapter passes this context
> to `TransformEngine.transform(message, direction, context)` (Q-042 resolution).
> See FR-004-37. `wrapResponse` does not populate cookies (cookies are a
> request-direction concept).

> **TransformContext injection (Q-042 resolution):** Feature 004 requires a new
> `TransformEngine.transform(Message, Direction, TransformContext)` overload.
> The adapter builds a `TransformContext` with adapter-specific data (cookies
> via `ctx.cookieMap()`, query params via `ctx.queryParamMap()`) and passes it
> to the engine. The engine uses this context instead of constructing its own.
> The existing 2-arg `transform(Message, Direction)` remains backward-compatible
> — it builds an empty context internally. This is a minor, additive core change
> (~10 lines) that does not break any existing tests or API contracts.
> Both `$cookies` (FR-004-37) and `$queryParams` (FR-004-39, Q-043 resolution)
> are populated via this mechanism.

---

## Non-Functional Requirements

| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-004-01 | Proxy startup time MUST be < 3 seconds (from `main()` to first request accepted) with ≤ 50 loaded specs. | Fast container startup for K8s scaling. Hot-reload mitigates slow startup, but initial startup should be fast. | Benchmark: measure time from `main()` entry to first successful HTTP response. | Javalin + Jetty startup, spec loading, JSLT compilation. | Production readiness. |
| NFR-004-02 | Proxy overhead (excluding transformation time) MUST be < 5 ms p95 for passthrough requests. This measures the proxy's own latency contribution: request parsing, forwarding, response assembly. | The proxy must not become a bottleneck. Transform latency is measured separately by NFR-001-03. | Benchmark: send passthrough requests through proxy vs direct to backend → measure delta. | Javalin + JDK HttpClient. | Performance. |
| NFR-004-03 | Memory footprint MUST remain < 256 MB JVM heap for typical workloads (≤ 50 specs, ≤ 100 concurrent requests, 10 KB average payload). | Container resource limits in K8s. Must fit in modest pod resource requests. | Benchmark: sustained load → monitor JVM heap usage via VisualVM or JFR. | JVM ergonomics. | K8s deployment. |
| NFR-004-04 | Docker image size MUST be < 150 MB (compressed). | Fast pull times in K8s, efficient layer caching. | `docker images` → check compressed size. | JRE Alpine base image + shadow JAR. | Docker packaging. |
| NFR-004-05 | Hot reload MUST be zero-downtime: in-flight requests MUST complete with the previous registry while new requests use the updated registry. | No request failures during config changes. | Integration test: send request during reload → verify consistent response. | NFR-001-05, `AtomicReference` swap. | Production operations. |
| NFR-004-06 | The proxy MUST handle at least 1000 concurrent connections without thread exhaustion, leveraging virtual threads. | Sidecar and standalone deployments may serve high-concurrency workloads. | Load test with 1000 concurrent connections → verify no errors. | Java 21 virtual threads, Javalin `useVirtualThreads`. | Scalability. |
| NFR-004-07 | All structured log entries MUST include: timestamp, level, thread name, request ID (from `X-Request-ID` header if present), HTTP method, request path, response status, total proxy latency (ms), upstream latency (ms), transform result type (`SUCCESS`/`PASSTHROUGH`/`ERROR`), and backend host. | Production troubleshooting requires correlated, structured logs. | Log output inspection during integration tests. | SLF4J + Logback, NFR-001-08/10. | Observability. |

---

## Branch & Scenario Matrix

> Full scenario definitions and coverage matrix will be maintained in
> `scenarios.md` once created. This summary lists representative scenarios.

### Category 1 — Basic Proxy

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-01 | **Passthrough GET:** `GET /api/users` with no matching profile → forwarded unmodified, response returned unmodified. |
| S-004-02 | **Passthrough POST with body:** `POST /api/data` with JSON body, no matching profile → body forwarded unmodified. |
| S-004-03 | **Method forwarding:** Each of GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS proxied correctly. |
| S-004-04 | **Header forwarding:** Request headers forwarded to backend (hop-by-hop stripped). Response headers returned to client (hop-by-hop stripped in both directions, FR-004-04). |
| S-004-05 | **Query string forwarding:** `GET /api/users?page=2&size=10` → query string forwarded intact. |
| S-004-06 | **Path forwarding:** `GET /api/v1/nested/resource/123` → full path forwarded to backend. |

### Category 2 — Request Transformation

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-07 | **Request body transform:** Profile matches `POST /api/orders` → JSLT transforms request body → backend receives transformed body. |
| S-004-08 | **Request header transform:** Profile adds/removes/renames request headers before forwarding. |
| S-004-09 | **Request transform error:** JSLT evaluation fails → client receives `502` RFC 9457 error. Request NOT forwarded. |
| S-004-10 | **Request with no body:** `GET /api/users` matches a profile with request transform → transform receives `NullNode` body → expression evaluates on `NullNode` → `SUCCESS` with expression output (not `PASSTHROUGH` — profile matched). |

### Category 3 — Response Transformation

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-11 | **Response body transform:** Backend returns JSON → JSLT transforms response body → client receives transformed body. |
| S-004-12 | **Response header transform:** Profile adds/removes response headers before returning to client. |
| S-004-13 | **Response status override:** Profile overrides response status code (e.g., `200 → 201`). |
| S-004-14 | **Response transform error:** JSLT evaluation fails → client receives `502` RFC 9457 error. |

### Category 4 — Bidirectional Transformation

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-15 | **Full bidirectional:** Request body transformed before forward, response body transformed before return. Both use the same spec. |
| S-004-16 | **Request transform + response passthrough:** Request transformed, response returned unmodified. |
| S-004-17 | **Request passthrough + response transform:** Request forwarded unmodified, response transformed. |

### Category 5 — Error Handling

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-18 | **Backend unreachable:** Backend host does not resolve → `502 Bad Gateway` with RFC 9457 body. |
| S-004-19 | **Backend timeout:** Backend does not respond within `read-timeout-ms` → `504 Gateway Timeout`. |
| S-004-20 | **Backend connection refused:** Backend port not listening → `502 Bad Gateway`. |
| S-004-21 | **Malformed JSON body:** POST with `Content-Type: application/json` but invalid JSON → `400 Bad Request`. |
| S-004-22 | **Request body too large:** Request body exceeds `max-body-bytes` → `413 Payload Too Large`. |
| S-004-23 | **Unknown method:** Custom HTTP method → `405 Method Not Allowed`. |
| S-004-55 | **Non-JSON body with matching profile:** `POST /api/orders` with `Content-Type: text/xml` matches profile by path/method → `400 Bad Request` (Q-036). |
| S-004-56 | **Response body too large:** Backend returns response exceeding `max-body-bytes` → client receives `502 Bad Gateway` (Q-037, FR-004-13). |

### Category 6 — Configuration

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-24 | **YAML config loaded:** Proxy starts with valid `message-xform-proxy.yaml` → all settings applied. |
| S-004-25 | **Env var override:** `BACKEND_HOST=override` overrides YAML `backend.host: original`. |
| S-004-26 | **Missing config file:** No config file, no env vars → startup fails with usage message. |
| S-004-27 | **Invalid config:** Missing `backend.host` → startup fails with descriptive error. |
| S-004-28 | **Default values:** Unspecified optional fields use documented defaults (`port: 9090`, `scheme: http`, etc.). |

### Category 7 — Hot Reload

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-29 | **File watcher reload:** Save new spec to `specs-dir` → watcher detects → `reload()` → new spec used. |
| S-004-30 | **Admin reload:** `POST /admin/reload` → engine reloads → `200 OK`. |
| S-004-31 | **Reload failure:** Modified spec has invalid JSLT → reload fails → old registry stays → error logged. |
| S-004-32 | **Debounce:** Rapid file saves → only one reload triggered after debounce period. |
| S-004-33 | **Zero-downtime reload:** In-flight request during reload gets consistent response (old or new, never mixed). |

### Category 8 — Health & Readiness

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-34 | **Health check UP:** `GET /health` → `200 {"status": "UP"}`. |
| S-004-35 | **Readiness READY:** `GET /ready` when engine loaded and backend reachable → `200 {"status": "READY"}`. |
| S-004-36 | **Readiness NOT_READY (startup):** `GET /ready` before engine loads → `503 {"status": "NOT_READY"}`. |
| S-004-37 | **Readiness NOT_READY (backend down):** `GET /ready` when backend unreachable → `503 {"status": "NOT_READY", "reason": "backend_unreachable"}`. |
| S-004-38 | **Health/ready not transformed:** Health and readiness endpoints are NOT subject to profile matching. |
| S-004-70 | **Wildcard profile vs admin/health:** Profile with `path: /*` or `path: /health` exists → `GET /health` still returns health response, NOT transformed. Admin/health routing takes precedence over profile matching (FR-004-21/22 + endpoint routing priority note). |

### Category 9 — TLS

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-39 | **Inbound TLS:** `proxy.tls.enabled: true` → proxy serves HTTPS → client connects via HTTPS. |
| S-004-40 | **Inbound mTLS:** `proxy.tls.client-auth: need` → client must present valid cert. |
| S-004-41 | **Outbound TLS:** `backend.scheme: https` → proxy validates backend cert against truststore. |
| S-004-42 | **Outbound mTLS:** `backend.tls.keystore` configured → proxy presents client cert to backend. |
| S-004-43 | **TLS config error:** Invalid keystore path → startup fails with descriptive error. |

### Category 10 — Startup & Shutdown

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-44 | **Clean startup:** Proxy starts, logs summary (port, backend, spec count), begins accepting requests. |
| S-004-45 | **Startup failure (invalid spec):** Spec with bad JSLT → `TransformLoadException` → exit non-zero. |
| S-004-46 | **Graceful shutdown:** SIGTERM → stop accepting → drain in-flight → exit 0. |
| S-004-47 | **In-flight completion on shutdown:** Long-running request during SIGTERM → completes before exit. |

### Category 11 — Docker & Kubernetes

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-48 | **Docker build:** `docker build` produces image <150 MB. |
| S-004-49 | **Docker run:** `docker run -e BACKEND_HOST=httpbin.org -e BACKEND_PORT=80 message-xform-proxy` starts and serves traffic. |
| S-004-50 | **Volume mount:** `docker run -v ./specs:/specs` → specs loaded from mounted volume. |
| S-004-51 | **Shadow JAR:** `java -jar proxy.jar` starts the proxy with no classpath issues. |

### Category 12 — Upstream Protocol

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-52 | **HTTP/1.1 enforced:** Upstream request uses HTTP/1.1 regardless of client protocol version (FR-004-33). |
| S-004-53 | **Content-Length recalculated:** After request body transform changes size, upstream receives correct `Content-Length` (FR-004-34). |
| S-004-54 | **Response Content-Length recalculated:** After response body transform changes size, client receives correct `Content-Length` (FR-004-34). |

### Category 13 — Forwarded Headers

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-57 | **X-Forwarded-* added (default):** `proxy.forwarded-headers.enabled: true` (default) → upstream request includes `X-Forwarded-For` (client IP), `X-Forwarded-Proto`, `X-Forwarded-Host` (FR-004-36). |
| S-004-58 | **X-Forwarded-* disabled:** `proxy.forwarded-headers.enabled: false` → upstream request does NOT include any `X-Forwarded-*` headers (FR-004-36). |
| S-004-59 | **X-Forwarded-For appended:** Client behind an existing proxy sends `X-Forwarded-For: 1.1.1.1` → proxy appends client IP → backend receives `X-Forwarded-For: 1.1.1.1, 10.0.0.5` (FR-004-36). |

### Category 14 — Dispatch Table Integration

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-60 | **Request SUCCESS dispatch:** Profile matches → request body transformed → `UpstreamClient` receives transformed `Message` (body, headers, path, method). `applyChanges` is NOT called for request direction. |
| S-004-61 | **Response SUCCESS dispatch:** Backend returns response → response body transformed → `applyChanges` writes transformed body/headers/status to Javalin context → client receives transformed response. |
| S-004-62 | **Request ERROR dispatch:** Profile matches but JSLT expression fails → client receives `502` RFC 9457 error. Request is NOT forwarded to backend. |
| S-004-63 | **Request PASSTHROUGH dispatch:** No profile matches request → raw request bytes forwarded to backend unmodified (no JSON parse). Response returned unmodified. |
| S-004-64 | **Response with no body (204):** Backend returns `204 No Content` → `wrapResponse` creates `Message` with `NullNode` body → profile matching proceeds normally → response returned to client. |

### Category 15 — Concurrency

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-65 | **Concurrent connections:** 100 concurrent requests → all complete without thread exhaustion or error (virtual threads validation). |
| S-004-66 | **Concurrent reload during traffic:** File watcher triggers reload while 10 concurrent requests are in-flight → in-flight requests complete with old registry, new requests use new registry. No mixed results. |

### Category 16 — Cookie Binding

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-67 | **Cookie binding in JSLT:** Request with `Cookie: session=abc123; lang=en` → JSLT expression `$cookies.session` evaluates to `"abc123"` (FR-004-37). |
| S-004-68 | **No cookies:** Request with no `Cookie` header → `$cookies` is an empty map, `$cookies.session` evaluates to `null`. No error. |
| S-004-69 | **URL-encoded cookie value:** Request with `Cookie: name=hello%20world` → `$cookies.name` evaluates to `"hello world"` (Javalin decodes). |

### Category 17 — Request ID

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-71 | **Request ID generated:** Request without `X-Request-ID` header → response includes `X-Request-ID: <uuid>` (FR-004-38). Structured log entry includes the generated UUID. |
| S-004-72 | **Request ID echoed:** Request with `X-Request-ID: abc-123` → response includes `X-Request-ID: abc-123` (FR-004-38). Structured log entry includes `abc-123`. |
| S-004-73 | **Request ID in error response:** Request that triggers `502 Bad Gateway` (backend unreachable) → error response still includes `X-Request-ID` header (generated or echoed). |

### Category 18 — Query Parameter Binding

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-004-74 | **Query param binding in JSLT:** Request `GET /api?page=2&sort=name` → JSLT expression `$queryParams.page` evaluates to `"2"`, `$queryParams.sort` evaluates to `"name"` (FR-004-39). |
| S-004-75 | **No query string:** Request `GET /api` with no query string → `$queryParams` is an empty map, `$queryParams.page` evaluates to `null`. No error. |
| S-004-76 | **Multi-value query param:** Request `GET /api?tag=a&tag=b` → `$queryParams.tag` evaluates to `"a"` (first value wins, consistent with single-value `$headers`). |
| S-004-77 | **URL-encoded query param:** Request `GET /api?name=hello%20world` → `$queryParams.name` evaluates to `"hello world"` (Javalin decodes). |

> **Note on chunked transfer encoding:** When clients or backends use
> `Transfer-Encoding: chunked`, the proxy relies on Javalin/Jetty to assemble the
> full body before parsing (consistent with ADR-0018 body buffering). No special
> handling is required in `ProxyHandler` — the assembled body is treated identically
> to a content-length-framed body.

---

## Test Strategy

- **Unit tests:** `StandaloneAdapter` (wrapRequest, wrapResponse, applyChanges),
  `ProxyConfig` parsing, header normalization, body size enforcement. JUnit 5 + Mockito.
- **Integration tests:** Full proxy lifecycle with mock upstream. Start proxy on random
  port in `@BeforeAll`, start mock upstream (WireMock or embedded HTTP server) on another
  random port, send requests via JDK `HttpClient`, assert on response body/headers/status
  after transformation.
  ```
  Test ← HTTP → Proxy ← HTTP → Mock Upstream
                  │
                  └── TransformEngine (with test specs/profiles)
  ```
- **E2E fixtures:** Load existing Feature 001 test fixtures through the proxy and verify
  transformation results match expectations.
- **TLS tests:** Integration tests with self-signed certs verifying inbound TLS,
  inbound mTLS, outbound TLS, and outbound mTLS.
- **Hot reload tests:** Integration tests that modify spec files during a test run and
  verify the proxy uses updated transformations.
- **Docker tests:** Dockerfile builds successfully, image size assertion, container
  startup and basic health check.
- **Performance:** ADR-0028 Layer 2 scope — HTTP throughput load testing (separate
  from Feature 001 core benchmarks).

---

## Interface & Contract Catalogue

### Domain Objects

| ID | Description | Modules |
|----|-------------|---------|
| DO-004-01 | `ProxyConfig` — parsed YAML configuration (proxy, backend, engine, reload, health, logging sections) | adapter-standalone |
| DO-004-02 | `BackendConfig` — structured backend definition (scheme, host, port, timeouts, pool, TLS) | adapter-standalone |
| DO-004-03 | `TlsConfig` — TLS settings (keystore, truststore, client-auth, verify-hostname) | adapter-standalone |
| DO-004-04 | `PoolConfig` — connection pool settings (max-connections, keep-alive, idle-timeout) | adapter-standalone |

### Adapter Implementation

| ID | Class | Description |
|----|-------|-------------|
| IMPL-004-01 | `StandaloneAdapter implements GatewayAdapter<Context>` | Javalin Context → Message gateway adapter |
| IMPL-004-02 | `ProxyHandler` | HTTP handler: intercept → transform → forward → transform → return |
| IMPL-004-03 | `UpstreamClient` | JDK `HttpClient` wrapper. **Must enforce HTTP/1.1** (FR-004-33) and recalculate `Content-Length` (FR-004-34). |
| IMPL-004-04 | `FileWatcher` | `WatchService`-based hot reload trigger |
| IMPL-004-05 | `StandaloneMain` | Entry point: CLI parsing, config loading, bootstrap |

### Configuration

| ID | Config key | Type | Default | Description |
|----|-----------|------|---------|-------------|
| CFG-004-01 | `proxy.host` | string | `0.0.0.0` | Bind address for HTTP server |
| CFG-004-02 | `proxy.port` | int | `9090` | Listen port for HTTP server |
| CFG-004-03 | `proxy.tls.enabled` | boolean | `false` | Enable inbound HTTPS |
| CFG-004-04 | `proxy.tls.keystore` | path | — | Server certificate keystore (PKCS12 or JKS) |
| CFG-004-05 | `proxy.tls.keystore-password` | string | — | Keystore password (supports `${ENV_VAR}` syntax) |
| CFG-004-06 | `proxy.tls.keystore-type` | enum | `PKCS12` | `PKCS12` or `JKS` |
| CFG-004-07 | `proxy.tls.client-auth` | enum | `none` | `none`, `want`, or `need` (mTLS) |
| CFG-004-08 | `proxy.tls.truststore` | path | — | Client CA truststore for mTLS |
| CFG-004-09 | `proxy.tls.truststore-password` | string | — | Truststore password |
| CFG-004-10 | `proxy.tls.truststore-type` | enum | `PKCS12` | `PKCS12` or `JKS` |
| CFG-004-11 | `backend.scheme` | enum | `http` | `http` or `https` |
| CFG-004-12 | `backend.host` | string | — | **Required.** Backend hostname or IP |
| CFG-004-13 | `backend.port` | int | `80`/`443` | Backend port (auto-derived from scheme if omitted) |
| CFG-004-14 | `backend.connect-timeout-ms` | int | `5000` | TCP connect timeout (ms) |
| CFG-004-15 | `backend.read-timeout-ms` | int | `30000` | Response read timeout (ms) |
| CFG-004-16 | `proxy.max-body-bytes` | int | `10485760` | Max body size in both directions (10 MB). Request > limit → 413; response > limit → 502. |
| CFG-004-17 | `backend.pool.max-connections` | int | `100` | Max concurrent connections to backend |
| CFG-004-18 | `backend.pool.keep-alive` | boolean | `true` | Use HTTP keep-alive |
| CFG-004-19 | `backend.pool.idle-timeout-ms` | int | `60000` | Close idle connections after (ms) |
| CFG-004-20 | `backend.tls.truststore` | path | — | CA certs for validating backend server cert |
| CFG-004-21 | `backend.tls.truststore-password` | string | — | Truststore password |
| CFG-004-22 | `backend.tls.truststore-type` | enum | `PKCS12` | `PKCS12` or `JKS` |
| CFG-004-23 | `backend.tls.verify-hostname` | boolean | `true` | Hostname verification for backend TLS |
| CFG-004-24 | `backend.tls.keystore` | path | — | Client cert for outbound mTLS |
| CFG-004-25 | `backend.tls.keystore-password` | string | — | Client keystore password |
| CFG-004-26 | `backend.tls.keystore-type` | enum | `PKCS12` | `PKCS12` or `JKS` |
| CFG-004-27 | `engine.specs-dir` | path | `./specs` | Directory containing transform spec YAML files |
| CFG-004-28 | `engine.profiles-dir` | path | `./profiles` | Directory containing transform profile YAML files |
| CFG-004-29 | `engine.profile` | path | — | Explicit single profile path (alternative to profiles-dir). When both `engine.profile` and `engine.profiles-dir` are set, `engine.profile` takes precedence and `profiles-dir` is ignored with a warning. The standalone proxy loads exactly **one** active profile per instance (ADR-0023: cross-profile routing is product-defined). |
| CFG-004-30 | `engine.schema-validation` | enum | `lenient` | `lenient` or `strict` |
| CFG-004-31 | `reload.enabled` | boolean | `true` | Enable file-system watching for hot reload |
| CFG-004-32 | `reload.watch-dirs` | list | `[specs-dir, profiles-dir]` | Directories to watch for changes |
| CFG-004-33 | `reload.debounce-ms` | int | `500` | Debounce period for file change events (ms) |
| CFG-004-34 | `health.enabled` | boolean | `true` | Enable health/readiness endpoints |
| CFG-004-35 | `health.path` | string | `/health` | Liveness probe path |
| CFG-004-36 | `health.ready-path` | string | `/ready` | Readiness probe path |
| CFG-004-37 | `logging.format` | enum | `json` | `json` or `text` |
| CFG-004-38 | `logging.level` | enum | `INFO` | Root log level. Valid values: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. Maps to SLF4J/Logback levels. |
| CFG-004-39 | `proxy.shutdown.drain-timeout-ms` | int | `30000` | Max wait for in-flight requests during graceful shutdown (ms) |
| CFG-004-40 | `proxy.forwarded-headers.enabled` | boolean | `true` | Add `X-Forwarded-For/Proto/Host` headers to upstream requests (Q-038) |
| CFG-004-41 | `admin.reload-path` | string | `/admin/reload` | Reload trigger endpoint path. Configurable for consistency with `health.path`/`health.ready-path`. |

### Environment Variable Mapping

| Env Var | Config Path |
|---------|------------|
| `PROXY_HOST` | `proxy.host` |
| `PROXY_PORT` | `proxy.port` |
| `PROXY_TLS_ENABLED` | `proxy.tls.enabled` |
| `PROXY_TLS_KEYSTORE` | `proxy.tls.keystore` |
| `PROXY_TLS_KEYSTORE_PASSWORD` | `proxy.tls.keystore-password` |
| `PROXY_TLS_KEYSTORE_TYPE` | `proxy.tls.keystore-type` |
| `PROXY_TLS_CLIENT_AUTH` | `proxy.tls.client-auth` |
| `PROXY_TLS_TRUSTSTORE` | `proxy.tls.truststore` |
| `PROXY_TLS_TRUSTSTORE_PASSWORD` | `proxy.tls.truststore-password` |
| `PROXY_TLS_TRUSTSTORE_TYPE` | `proxy.tls.truststore-type` |
| `BACKEND_SCHEME` | `backend.scheme` |
| `BACKEND_HOST` | `backend.host` |
| `BACKEND_PORT` | `backend.port` |
| `BACKEND_CONNECT_TIMEOUT_MS` | `backend.connect-timeout-ms` |
| `BACKEND_READ_TIMEOUT_MS` | `backend.read-timeout-ms` |
| `PROXY_MAX_BODY_BYTES` | `proxy.max-body-bytes` |
| `BACKEND_POOL_MAX_CONNECTIONS` | `backend.pool.max-connections` |
| `BACKEND_POOL_KEEP_ALIVE` | `backend.pool.keep-alive` |
| `BACKEND_POOL_IDLE_TIMEOUT_MS` | `backend.pool.idle-timeout-ms` |
| `BACKEND_TLS_TRUSTSTORE` | `backend.tls.truststore` |
| `BACKEND_TLS_TRUSTSTORE_PASSWORD` | `backend.tls.truststore-password` |
| `BACKEND_TLS_TRUSTSTORE_TYPE` | `backend.tls.truststore-type` |
| `BACKEND_TLS_VERIFY_HOSTNAME` | `backend.tls.verify-hostname` |
| `BACKEND_TLS_KEYSTORE` | `backend.tls.keystore` |
| `BACKEND_TLS_KEYSTORE_PASSWORD` | `backend.tls.keystore-password` |
| `BACKEND_TLS_KEYSTORE_TYPE` | `backend.tls.keystore-type` |
| `SPECS_DIR` | `engine.specs-dir` |
| `PROFILES_DIR` | `engine.profiles-dir` |
| `ENGINE_PROFILE` | `engine.profile` |
| `SCHEMA_VALIDATION` | `engine.schema-validation` |
| `RELOAD_ENABLED` | `reload.enabled` |
| `RELOAD_WATCH_DIRS` | `reload.watch-dirs` (comma-separated list) |
| `RELOAD_DEBOUNCE_MS` | `reload.debounce-ms` |
| `HEALTH_ENABLED` | `health.enabled` |
| `HEALTH_PATH` | `health.path` |
| `HEALTH_READY_PATH` | `health.ready-path` |
| `LOG_FORMAT` | `logging.format` |
| `LOG_LEVEL` | `logging.level` |
| `PROXY_SHUTDOWN_DRAIN_TIMEOUT_MS` | `proxy.shutdown.drain-timeout-ms` |
| `PROXY_FORWARDED_HEADERS_ENABLED` | `proxy.forwarded-headers.enabled` |
| `ADMIN_RELOAD_PATH` | `admin.reload-path` |

### Fixtures & Sample Data

| ID | Path | Purpose |
|----|------|---------|
| FX-004-01 | `adapter-standalone/src/test/resources/config/minimal-config.yaml` | Minimal valid proxy config (backend host + port only) |
| FX-004-02 | `adapter-standalone/src/test/resources/config/full-config.yaml` | Full config with all options specified |
| FX-004-03 | `adapter-standalone/src/test/resources/config/tls-config.yaml` | Config with inbound + outbound TLS |
| FX-004-04 | `adapter-standalone/src/test/resources/specs/test-transform.yaml` | Simple JSLT spec for integration tests |
| FX-004-05 | `adapter-standalone/src/test/resources/profiles/test-profile.yaml` | Profile matching test routes to test spec |
| FX-004-06 | `adapter-standalone/src/test/resources/tls/server.p12` | Self-signed server keystore for TLS tests |
| FX-004-07 | `adapter-standalone/src/test/resources/tls/client.p12` | Client keystore for mTLS tests |
| FX-004-08 | `adapter-standalone/src/test/resources/tls/truststore.p12` | CA truststore for TLS tests |
| FX-004-09 | `adapter-standalone/Dockerfile` | Multi-stage Docker build |

---

## Appendix

### A. Architecture Diagram

```
                    ┌─────────────────────────────────────────────────┐
                    │              adapter-standalone                  │
                    │                                                  │
  Client ──HTTP──▶  │  Javalin 6 (Jetty 11, Virtual Threads)          │
                    │    │                                             │
                    │    ▼                                             │
                    │  ProxyHandler                                    │
                    │    │  1. StandaloneAdapter.wrapRequest(ctx)      │
                    │    │  2. TransformEngine.transform(msg, REQUEST) │
                    │    │  3. UpstreamClient.forward(request)  ──HTTP──▶  Backend
                    │    │  3a. Populate ctx with upstream response     │
                    │    │  4. StandaloneAdapter.wrapResponse(ctx)     │
                    │    │  5. TransformEngine.transform(msg, RESPONSE)│
                    │    │  6. StandaloneAdapter.applyChanges(msg,ctx) │
                    │    ▼                                             │
  Client ◀──HTTP──  │  Response                                       │
                    │                                                  │
                    │  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
                    │  │ /health  │  │ /ready   │  │ /admin/  │       │
                    │  │ liveness │  │ readiness│  │  reload  │       │
                    │  └──────────┘  └──────────┘  └──────────┘       │
                    │                                                  │
                    │  ┌──────────────────────────────────────┐       │
                    │  │ FileWatcher (WatchService)           │       │
                    │  │ → TransformEngine.reload()           │       │
                    │  └──────────────────────────────────────┘       │
                    └─────────────────────────────────────────────────┘
                                        │
                                        │ depends on
                                        ▼
                    ┌─────────────────────────────────────────────────┐
                    │                    core                          │
                    │  TransformEngine, TransformRegistry,             │
                    │  GatewayAdapter SPI, Message, TransformResult    │
                    └─────────────────────────────────────────────────┘
```

### B. Kubernetes Sidecar Deployment Example

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app-with-transform
spec:
  containers:
    - name: backend
      image: my-backend:1.0
      ports:
        - containerPort: 8080

    - name: transform-proxy
      image: message-xform-proxy:1.0
      env:
        - name: BACKEND_HOST
          value: "localhost"
        - name: BACKEND_PORT
          value: "8080"
        - name: PROXY_PORT
          value: "9090"
      ports:
        - containerPort: 9090
      volumeMounts:
        - name: specs
          mountPath: /specs
        - name: profiles
          mountPath: /profiles
      livenessProbe:
        httpGet:
          path: /health
          port: 9090
        initialDelaySeconds: 5
        periodSeconds: 10
      readinessProbe:
        httpGet:
          path: /ready
          port: 9090
        initialDelaySeconds: 3
        periodSeconds: 5

  volumes:
    - name: specs
      configMap:
        name: transform-specs
    - name: profiles
      configMap:
        name: transform-profiles
```

### C. Docker Usage Example

```bash
# Build
docker build -t message-xform-proxy .

# Run with env vars
docker run -p 9090:9090 \
  -e BACKEND_HOST=api.example.com \
  -e BACKEND_PORT=443 \
  -e BACKEND_SCHEME=https \
  -v ./my-specs:/specs \
  -v ./my-profiles:/profiles \
  message-xform-proxy

# Run with config file
docker run -p 9090:9090 \
  -v ./message-xform-proxy.yaml:/opt/message-xform/config.yaml \
  -v ./my-specs:/specs \
  message-xform-proxy --config /opt/message-xform/config.yaml
```

### D. Related ADRs

| ADR | Title | Relevance to Feature 004 |
|-----|-------|--------------------------|
| ADR-0011 | Jackson `JsonNode` body representation | Proxy parses request/response bodies to `JsonNode` |
| ADR-0013 | Copy-on-wrap message adapter | Adapter creates deep copies in `wrapRequest`/`wrapResponse` |
| ADR-0018 | Body buffering is adapter concern | Proxy enforces `max-body-bytes` limit |
| ADR-0022 | Error response on transform failure | Proxy returns RFC 9457 error responses |
| ADR-0024 | Error type catalogue | `TransformLoadException` on startup errors |
| ADR-0025 | Adapter lifecycle is adapter-scoped | Proxy owns lifecycle: `main()` → load → start |
| ADR-0029 | Javalin 6 for standalone proxy | HTTP server choice |
