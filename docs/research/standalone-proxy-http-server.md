# Research: Standalone Proxy — HTTP Server Selection

Date: 2026-02-08 | Author: Agent (reviewed by Ivan)
Related: Feature 004, ADR-0025, ADR-0023, ADR-0013

## 1. Context

Feature 004 implements the message-xform engine as a **standalone HTTP reverse proxy**.
This is the first concrete `GatewayAdapter` implementation and serves three roles:

1. **E2E test harness** — validates the core API and GatewayAdapter SPI with zero
   external dependencies.
2. **Reference adapter** — cleanest, simplest implementation of the adapter pattern
   for all subsequent gateway integrations to model against.
3. **Dev/test tool** — zero-dependency way to test YAML specs against real HTTP traffic.

The proxy lifecycle is straightforward (per ADR-0025):
```
main(args) → load specs → load profile → start HTTP server
  ↓
for each request:
  adapter.wrapRequest(native) → engine.transform(msg, REQUEST) → forward to upstream
  ↓
  receive upstream response
  ↓
  adapter.wrapResponse(native) → engine.transform(msg, RESPONSE) → return to client
```

### Constraints from Existing Architecture

- **Java 21** target (same as core).
- **Jackson `JsonNode`** is the body representation (ADR-0011). The proxy MUST
  buffer the full body and parse to `JsonNode` before transformation.
- **Copy-on-wrap semantics** (ADR-0013) — adapter creates a mutable deep copy.
- **Engine is synchronous** — `TransformEngine.transform()` returns
  `TransformResult` directly (not `CompletableFuture`). This simplifies the proxy:
  a thread-per-request model works perfectly with virtual threads.
- **Atomic reload** (NFR-001-05) — already supported via `TransformEngine.reload()`.
  The proxy just needs a trigger mechanism (e.g., `WatchService`, admin endpoint,
  or signal handler).
- **Zero gateway dependencies** (NFR-001-02) — the `core` module stays clean.
  The proxy goes in its own Gradle submodule (`adapter-standalone`).

### Dependency Budget

The core module already depends on:
- `com.fasterxml.jackson.core:jackson-databind` (JSON)
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` (YAML)
- `com.schibsted.spt.data:jslt` (transformation)
- `com.networknt:json-schema-validator` (schema validation)
- `org.slf4j:slf4j-api` (logging facade)

The standalone adapter will add:
- HTTP server library (the subject of this research)
- HTTP client for upstream forwarding (Java 21 built-in `HttpClient` — zero-dep)
- Logback runtime (for structured JSON logging)
- CLI argument parser (optional — picocli, args4j, or manual)

---

## 2. HTTP Server Candidates

### 2.1 Javalin (Built on Jetty)

| Attribute | Detail |
|-----------|--------|
| **Library** | `io.javalin:javalin:6.x` |
| **Underlying server** | Jetty 12 (embedded) |
| **License** | Apache-2.0 |
| **JAR size** | ~1 MB (+ Jetty ~4 MB) |
| **Virtual threads** | Opt-in via `config.jetty.useVirtualThreads` |
| **API style** | Functional builder: `app.get("/path", ctx -> {})` |
| **Maturity** | Active, well-documented, >7K GitHub stars |

**Pros:**
- Simplest API of all candidates — minimal boilerplate, clean routing DSL.
- Built-in Jetty proxy servlet (`AsyncProxyServlet.Transparent`) for reverse
  proxy use cases.
- Virtual thread support is first-class (opt-in, not forced).
- Middleware/plugin system for cross-cutting concerns (logging, error handling).
- Excellent documentation and community.
- Naturally maps to our simple routing needs.

**Cons:**
- Transitive dependency on Jetty (~4 MB). This is lightweight by Java standards
  but adds a non-trivial dependency graph.
- Jetty 12 is a new major version — potential for breaking changes in minor updates.
- Javalin's proxy support uses Jetty servlets, which may feel heavy for a custom
  reverse proxy where we control every byte.

**Proxy implementation sketch:**
```java
var app = Javalin.create(config -> {
    config.jetty.useVirtualThreads = true;
}).start(port);

app.addHandler(HandlerType.BEFORE, "/*", ctx -> {
    // 1. Parse request body to JsonNode
    // 2. Build Message via adapter.wrapRequest()
    // 3. engine.transform(msg, REQUEST)
    // 4. Forward transformed request to upstream via HttpClient
    // 5. Receive upstream response
    // 6. adapter.wrapResponse() on upstream response
    // 7. engine.transform(msg, RESPONSE)
    // 8. Write transformed response to ctx
});
```

### 2.2 Undertow (JBoss/Red Hat)

| Attribute | Detail |
|-----------|--------|
| **Library** | `io.undertow:undertow-core:2.3.x` |
| **License** | Apache-2.0 |
| **JAR size** | ~2 MB (+ XNIO ~400 KB) |
| **Virtual threads** | Not native; uses XNIO worker threads. Can dispatch to virtual thread executor. |
| **API style** | Handler-chain: `Undertow.builder().addHttpListener(port, host).setHandler(handler)` |
| **Maturity** | Very mature, powers WildFly/JBoss, production-proven |

**Pros:**
- Very lightweight and performant — optimized for low latency.
- Non-blocking I/O with clean handler chain model.
- Minimal API surface — no unnecessary abstractions.
- Proven at massive scale in WildFly/Quarkus deployments.
- Direct access to request/response bytes — full control.

**Cons:**
- Lower-level API than Javalin — more boilerplate for routing, error handling.
- Virtual thread support is indirect (dispatch to executor), not a first-class config.
- XNIO dependency adds complexity to the thread model.
- Less community documentation for standalone use (most docs are WildFly-focused).
- No built-in reverse proxy abstraction — we'd build everything from scratch.

### 2.3 Vert.x (Eclipse Foundation)

| Attribute | Detail |
|-----------|--------|
| **Library** | `io.vertx:vertx-web:4.5.x` |
| **License** | Apache-2.0 / EPL-2.0 |
| **JAR size** | ~3.5 MB (vertx-core + vertx-web) |
| **Virtual threads** | Yes, via `vertx.executeBlocking()` or Vert.x 5 native support |
| **API style** | Reactive/event-driven: `router.route().handler(ctx -> {})` |
| **Maturity** | Very mature, top TechEmpower benchmarks, polyglot |

**Pros:**
- Highest raw performance in TechEmpower benchmarks (Round 23, Feb 2025).
- Built-in `HttpProxy` class for reverse proxy use cases.
- Reactive event bus for advanced orchestration.
- Excellent for high-concurrency scenarios.
- Built-in HTTP client (`WebClient`) — no need for JDK `HttpClient`.

**Cons:**
- Reactive programming model creates friction with our synchronous
  `TransformEngine.transform()`. Would need `executeBlocking()` calls.
- Heavier dependency graph than Javalin or Undertow.
- Steeper learning curve — the "reference adapter" should be easy to study.
- Over-engineered for our needs — we don't need event bus, polyglot, etc.
- The reactive paradigm adds cognitive overhead that doesn't align with the
  project's imperative/synchronous design philosophy.

### 2.4 Netty (Raw)

| Attribute | Detail |
|-----------|--------|
| **Library** | `io.netty:netty-all:4.1.x` |
| **License** | Apache-2.0 |
| **JAR size** | ~4 MB |
| **Virtual threads** | Indirect — can use virtual thread executors |
| **API style** | Low-level channel pipeline: `ChannelInboundHandlerAdapter` |
| **Maturity** | Foundational — used by Vert.x, gRPC, Spring WebFlux, etc. |

**Pros:**
- Maximum performance and control.
- Industry-standard networking library.
- Extremely battle-tested.

**Cons:**
- **Far too low-level** for this use case. We'd need to build HTTP parsing,
  routing, error handling, chunked transfer encoding, keep-alive, all from scratch.
- Massive implementation effort for marginal performance gain over Javalin/Undertow.
- Not a good "reference adapter" — the complexity would obscure the adapter pattern.
- No HTTP-level abstractions — we want a web server, not a network framework.

**Verdict: Eliminated.** The implementation cost is disproportionate to the benefit.

### 2.5 JDK HttpServer (`com.sun.net.httpserver`)

| Attribute | Detail |
|-----------|--------|
| **Library** | Built into JDK 21 — zero additional dependencies |
| **License** | GPL-2.0-with-classpath-exception (JDK) |
| **JAR size** | 0 (part of JDK) |
| **Virtual threads** | Yes — set executor to `Executors.newVirtualThreadPerTaskExecutor()` |
| **API style** | Handler-based: `server.createContext("/", handler)` |
| **Maturity** | Stable JDK API since Java 6, confirmed supported (not internal `sun.*`) |

**Pros:**
- **Zero additional dependencies** — the ultimate lightweight option.
- Virtual threads work perfectly with one line of config.
- Simple, stable API that won't change.
- Aligns with "reference adapter" goal — nothing to learn beyond JDK.
- Performance with virtual threads is surprisingly good (~120K req/s reported).

**Cons:**
- No routing DSL — we'd implement basic path matching manually.
- No middleware/filter chain — cross-cutting concerns need manual wiring.
- Limited HTTP/2 support.
- No built-in graceful shutdown mechanism.
- No WebSocket support (not needed for proxy, but noted).
- Documentation is sparse compared to Javalin.
- The `com.sun.net.httpserver` package name looks "internal" (it's not — JEP 403
  confirms it's stable API), but may cause confusion.

---

## 3. HTTP Client for Upstream Forwarding

All proxy implementations need an HTTP client to forward requests to the upstream
service. Options:

| Client | Dependency | Async | Virtual Threads | Notes |
|--------|-----------|-------|-----------------|-------|
| **JDK `HttpClient`** | None (built-in) | Yes (`sendAsync`) | Excellent | Java 11+, `AutoCloseable` in 21 |
| **Apache HttpClient 5** | ~1.5 MB | Yes | Good | Very mature, feature-rich |
| **OkHttp** | ~600 KB | Yes | Good | Popular, clean API |

**Recommendation: JDK `HttpClient`**.
- Zero dependency — aligns with dependency budget minimalism.
- Native virtual thread support — blocking `send()` works perfectly.
- `AutoCloseable` in Java 21 for clean resource management.
- Supports connection pooling, timeouts, and redirects.
- All candidates would work, but the JDK client eliminates a dependency.

---

## 4. Evaluation Matrix

| Criterion | Weight | Javalin | Undertow | JDK HttpServer | Vert.x |
|-----------|--------|---------|----------|----------------|--------|
| **API simplicity** | 25% | ★★★★★ | ★★★☆☆ | ★★★☆☆ | ★★★☆☆ |
| **Dependency weight** | 20% | ★★★☆☆ | ★★★★☆ | ★★★★★ | ★★☆☆☆ |
| **Virtual thread support** | 15% | ★★★★★ | ★★★☆☆ | ★★★★★ | ★★★☆☆ |
| **Reference adapter clarity** | 15% | ★★★★★ | ★★★★☆ | ★★★☆☆ | ★★☆☆☆ |
| **Production readiness** | 15% | ★★★★☆ | ★★★★★ | ★★★☆☆ | ★★★★★ |
| **Proxy-specific features** | 10% | ★★★★☆ | ★★★☆☆ | ★★☆☆☆ | ★★★★★ |
| **Weighted total** | | **4.15** | **3.55** | **3.70** | **3.00** |

### Shortlist

1. **Javalin** — Best overall balance. Simplest API, excellent virtual thread
   support, good proxy capabilities, and the "reference adapter" will be crystal
   clear to study. The Jetty dependency is acceptable.

2. **JDK HttpServer** — Best for zero-dependency purism. Requires more manual
   work for routing and error handling, but eliminates all external HTTP server
   dependencies.

Both are viable. The choice depends on whether we prioritize **API elegance and
developer experience** (Javalin) or **zero-dependency minimalism** (JDK HttpServer).

---

## 5. Architecture Sketch

Regardless of HTTP server choice, the standalone proxy has this structure:

```
adapter-standalone/
├── build.gradle.kts
└── src/main/java/io/messagexform/adapter/standalone/
    ├── StandaloneMain.java              # Entry point, CLI parsing, bootstrap
    ├── StandaloneAdapter.java           # GatewayAdapter<NativeRequest> impl
    ├── ProxyHandler.java                # HTTP handler: intercept → transform → forward
    ├── UpstreamClient.java              # JDK HttpClient wrapper for upstream calls
    ├── config/
    │   └── ProxyConfig.java             # YAML configuration model
    ├── health/
    │   └── HealthHandler.java           # GET /health, GET /ready
    └── reload/
        └── FileWatcher.java             # WatchService-based hot reload trigger
```

### Request Flow (Detailed)

```
Client Request
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│ ProxyHandler                                                │
│  1. Read HTTP request (method, path, headers, body)         │
│  2. Parse body to JsonNode (Jackson)                        │
│  3. adapter.wrapRequest(nativeCtx) → Message                │
│  4. engine.transform(message, REQUEST)                      │
│     ├── SUCCESS  → use transformed message                  │
│     ├── PASSTHROUGH → use original message                  │
│     └── ERROR → return error response to client (short-circuit) │
│  5. Build upstream HTTP request from (transformed) message  │
│  6. upstreamClient.forward(request) → upstream response     │
│  7. Parse upstream response body to JsonNode                │
│  8. adapter.wrapResponse(nativeCtx) → Message               │
│  9. engine.transform(message, RESPONSE)                     │
│     ├── SUCCESS → use transformed message                   │
│     ├── PASSTHROUGH → use original message                  │
│     └── ERROR → return error response to client             │
│ 10. Write final response to client                          │
└─────────────────────────────────────────────────────────────┘
```

### Configuration Model (Resolved)

Q-030 resolved: **single backend per proxy instance** with structured backend
config. Q-031 resolved: **10 MB default** body limit. Q-032 resolved: **TLS
supported via config** (plaintext default, optional TLS for both inbound and
outbound).

**Design principle:** Every operational knob is runtime-configurable via YAML
and/or environment variables. No rebuild required for tuning.

```yaml
# message-xform-proxy.yaml
# ──────────────────────────────────────────────────────────────
# All values shown are DEFAULTS. Override via YAML or env vars.
# ──────────────────────────────────────────────────────────────

# ── Inbound (proxy ← client) ─────────────────────────────────
proxy:
  host: 0.0.0.0
  port: 9090
  tls:                                    # Inbound TLS (optional)
    enabled: false                        # true → serve HTTPS
    keystore: /path/to/server.p12         # Server certificate + private key
    keystore-password: ${KEYSTORE_PASS}
    keystore-type: PKCS12                 # PKCS12 | JKS
    # Mutual TLS (client certificate verification):
    client-auth: none                     # none | want | need
    truststore: /path/to/client-ca.p12    # Required when client-auth != none
    truststore-password: ${CLIENT_CA_PASS}
    truststore-type: PKCS12

# ── Outbound (proxy → backend) ────────────────────────────────
backend:
  scheme: http                            # http | https
  host: backend-service
  port: 8080

  # Timeouts
  connect-timeout-ms: 5000               # TCP connect timeout
  read-timeout-ms: 30000                 # Response read timeout (socket timeout)

  # Body limits (per ADR-0018: adapter concern)
  max-body-bytes: 10485760               # 10 MB default; 413 if exceeded

  # Connection pool
  pool:
    max-connections: 100                  # Max concurrent connections to backend
    keep-alive: true                      # Use HTTP keep-alive
    idle-timeout-ms: 60000               # Close idle connections after (ms)
    # Note: JDK HttpClient exposes pool tuning via system properties
    # (jdk.httpclient.connectionPoolSize, jdk.httpclient.keepalive.timeout).
    # These config values map to those properties at startup.
    # If finer-grained pool control is needed in production, Apache
    # HttpClient 5 can be substituted as an implementation detail.

  # Outbound TLS (when scheme=https)
  tls:
    truststore: /path/to/backend-ca.p12  # CA certs for backend validation
    truststore-password: ${BACKEND_CA_PASS}
    truststore-type: PKCS12
    verify-hostname: true                 # Default: true (disable at your own risk)
    # Client certificate for mTLS with backend:
    keystore: /path/to/client.p12         # Client cert + private key
    keystore-password: ${CLIENT_KEY_PASS}
    keystore-type: PKCS12

  # Future — not implemented in v1:
  # auth:
  #   type: none                          # none | api-key | bearer-token
  #   api-key:
  #     header: X-API-Key
  #     value: ${API_KEY}
  #   bearer-token:
  #     token: ${JWT_TOKEN}

# ── Transform engine ──────────────────────────────────────────
engine:
  specs-dir: ./specs
  profiles-dir: ./profiles               # Optional — single profile per instance
  profile: ./profiles/dev.yaml           # Or explicit single profile path
  schema-validation: lenient             # lenient (default) | strict

# ── Hot reload ────────────────────────────────────────────────
reload:
  enabled: true
  watch-dirs:                             # Directories to watch for changes
    - ./specs
    - ./profiles
  debounce-ms: 500                        # Debounce file change events

# ── Health / readiness ────────────────────────────────────────
health:
  enabled: true
  path: /health                           # Liveness probe
  ready-path: /ready                      # Readiness (engine loaded + backend reachable)

# ── Logging ───────────────────────────────────────────────────
logging:
  format: json                            # json | text
  level: INFO
```

#### Environment Variable Overrides

Every config key maps to an env var for Docker/K8s:

| Env Var | Config Path | Example |
|---------|------------|---------|
| `PROXY_PORT` | `proxy.port` | `9090` |
| `PROXY_TLS_ENABLED` | `proxy.tls.enabled` | `true` |
| `BACKEND_SCHEME` | `backend.scheme` | `https` |
| `BACKEND_HOST` | `backend.host` | `my-backend` |
| `BACKEND_PORT` | `backend.port` | `8080` |
| `BACKEND_CONNECT_TIMEOUT_MS` | `backend.connect-timeout-ms` | `5000` |
| `BACKEND_READ_TIMEOUT_MS` | `backend.read-timeout-ms` | `30000` |
| `BACKEND_MAX_BODY_BYTES` | `backend.max-body-bytes` | `10485760` |
| `BACKEND_POOL_MAX_CONNECTIONS` | `backend.pool.max-connections` | `100` |
| `BACKEND_POOL_IDLE_TIMEOUT_MS` | `backend.pool.idle-timeout-ms` | `60000` |
| `SPECS_DIR` | `engine.specs-dir` | `/specs` |
| `LOG_LEVEL` | `logging.level` | `DEBUG` |

#### TLS Configuration Summary

| Direction | Purpose | Config Section | Minimum Keys |
|-----------|---------|---------------|-------------|
| **Inbound** (client → proxy) | Serve HTTPS | `proxy.tls` | `enabled`, `keystore`, `keystore-password` |
| **Inbound mTLS** | Verify client certs | `proxy.tls` | + `client-auth: need`, `truststore` |
| **Outbound** (proxy → backend) | Validate backend cert | `backend.tls` | `truststore`, `truststore-password` |
| **Outbound mTLS** | Present client cert to backend | `backend.tls` | + `keystore`, `keystore-password` |

#### Connection Pool Notes

The JDK `HttpClient` exposes connection pool tuning via system properties:
- `jdk.httpclient.connectionPoolSize` — max cached connections (0 = unlimited)
- `jdk.httpclient.keepalive.timeout` — idle keepalive timeout (seconds)

Our config maps `backend.pool.*` to these system properties at startup. If
production users need finer-grained pool control (per-route limits, eviction
policies, detailed metrics), we can substitute Apache HttpClient 5 as an
implementation detail behind `UpstreamClient` without changing the config schema.

### GatewayAdapter Implementation

The standalone adapter's native type is framework-specific. For Javalin:

```java
public class StandaloneAdapter implements GatewayAdapter<Context> {

    @Override
    public Message wrapRequest(Context ctx) {
        JsonNode body = parseBody(ctx.body());   // Parse to JsonNode
        Map<String, String> headers = extractHeaders(ctx);
        return new Message(
            body, headers, headersAll,
            null,                               // No status for requests
            ctx.contentType(),
            ctx.path(),
            ctx.method().name(),
            ctx.queryString()
        );
    }

    @Override
    public Message wrapResponse(Context ctx) {
        // ctx here wraps the upstream response
        // ...
    }

    @Override
    public void applyChanges(Message msg, Context ctx) {
        // Write transformed body, headers, status to the Javalin response
        // ...
    }
}
```

For JDK HttpServer, `<R>` would be `HttpExchange`.

---

## 6. Hot Reload Strategy

The core engine already supports atomic reload via `TransformEngine.reload()`.
The standalone proxy adds a **trigger mechanism**:

**Option A — `WatchService` (recommended for dev/test)**
- Use `java.nio.file.WatchService` to monitor `specs-dir` and `profiles-dir`.
- Debounce file events (500ms default) to avoid rapid reloads during editing.
- On change: collect all `.yaml` files → call `engine.reload(specPaths, profilePath)`.
- Run the watcher on a daemon virtual thread.

**Option B — Admin endpoint**
- `POST /admin/reload` triggers `engine.reload()`.
- Useful for CI/CD pipelines and orchestration.
- Can coexist with WatchService.

**Option C — Unix signal (SIGHUP)**
- Classic approach, but not portable and harder to test.
- Lower priority — WatchService + admin endpoint cover all use cases.

**Recommendation:** Implement both A and B. SIGHUP is a stretch goal.

---

## 7. Key Design Decisions for Spec Phase

| ID | Question | Impact | Resolution |
|----|----------|--------|------------|
| Q-029 | HTTP server choice | High | ✅ Resolved → ADR-0029: Javalin 6 (Jetty 12) |
| Q-030 | Upstream routing model | Medium | ✅ Resolved: single backend per instance, structured config (scheme/host/port) |
| Q-031 | Body size limits for proxy buffering | Medium | ✅ Resolved: 10 MB default, configurable via `backend.max-body-bytes` |
| Q-032 | TLS termination | Low | ✅ Resolved: plaintext default + TLS via config (inbound + outbound) |

All four questions are now resolved. Decisions documented in §5 Configuration Model.

### 7.1 Upstream Routing (Resolved)

**Decision: Single backend per proxy instance** with structured backend config.
The `backend` block uses `scheme`, `host`, `port` instead of a raw URL string.
This is extensible for future auth (client-cert, API key, bearer token) without
breaking the config schema. See §5 Configuration Model for the full YAML.

Multiple backends require multiple proxy instances — natural for Docker/K8s
deployments where each container handles one upstream.

### 7.2 Body Size Limits (Resolved)

Per ADR-0018, body buffering is an adapter concern. The standalone proxy needs
its own limit to prevent OOM from large payloads.

**Decision:** Default `max-body-bytes: 10485760` (10 MB) with YAML config
override. Requests exceeding the limit get a 413 Payload Too Large response.
Configurable via `backend.max-body-bytes` and `BACKEND_MAX_BODY_BYTES` env var.

### 7.3 TLS (Resolved)

**Decision:** TLS is supported via YAML config (plaintext is the default).

- **Inbound TLS** (`proxy.tls`): server cert + optional mTLS (client cert validation)
- **Outbound TLS** (`backend.tls`): truststore for backend cert validation +
  optional client cert for mTLS with backend

See §5 Configuration Model for the full TLS config structure.

---

## 8. Testing Strategy

| Layer | Scope | Tooling |
|-------|-------|---------|
| **Unit** | `StandaloneAdapter` (wrapRequest, wrapResponse, applyChanges), config parsing | JUnit 5 + Mockito |
| **Integration** | Full proxy lifecycle: start → request → transform → forward → response | JUnit 5 + JDK `HttpClient` + mock upstream (WireMock or embedded HTTP server) |
| **E2E fixtures** | Load existing Feature 001 test fixtures through the proxy | Hurl or JDK HttpClient scripts |
| **Performance** | ADR-0028 Layer 2: HTTP throughput load testing | JMH or Gatling (separate Feature 009 scope) |

### Integration Test Approach

```
Test ← HTTP → Proxy ← HTTP → Mock Upstream
                │
                └── TransformEngine (with test specs/profiles)
```

- Start proxy on random port in `@BeforeAll`.
- Start mock upstream (returns known JSON responses) on another random port.
- Send HTTP requests via JDK `HttpClient`.
- Assert on response body, headers, status code after transformation.

---

## 9. Summary — All Decisions Resolved

| Decision | Resolution | Status |
|----------|-----------|--------|
| HTTP server | **Javalin 6** (Jetty 12) — ADR-0029 | ✅ Resolved |
| HTTP client | **JDK `HttpClient`** (built-in, zero deps) | ✅ Resolved |
| Config format | **YAML** (`message-xform-proxy.yaml`) | ✅ Resolved |
| Backend model | **Structured config** (scheme/host/port), single per instance | ✅ Resolved |
| Hot reload | **WatchService + admin endpoint** | ✅ Resolved |
| Docker packaging | **Multi-stage Dockerfile**, shadow JAR, JRE Alpine | ✅ In scope |
| TLS | **Plaintext default + TLS via config** (inbound + outbound) | ✅ Resolved |
| Body limit | **10 MB default** (configurable via YAML/env) | ✅ Resolved |
| Connection pool | Configurable via `backend.pool.*` (maps to JDK system props) | ✅ Resolved |
| Gradle module | `adapter-standalone` | ✅ Resolved |
| CLI parsing | **picocli** or manual `args` | Low — to be decided |

All key research questions are resolved. The research phase is complete.
Next step: write the Feature 004 specification (`docs/architecture/features/004/spec.md`).
