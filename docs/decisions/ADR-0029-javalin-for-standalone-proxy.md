# ADR-0029 – Javalin 6 (Jetty 12) for Standalone HTTP Proxy

Date: 2026-02-08 | Status: Accepted

## Context

Feature 004 (Standalone HTTP Proxy Mode) needs an embedded HTTP server to receive
client requests, invoke the core `TransformEngine`, and forward transformed requests
to an upstream service. This is the first concrete `GatewayAdapter` implementation
and serves as the **reference adapter** for all subsequent gateway integrations.

The proxy requires:
- Java 21 virtual thread support (thread-per-request model).
- HTTP routing and middleware for error handling and logging.
- Clean, readable code that demonstrates the `GatewayAdapter` pattern.
- Production-grade stability for dev/test and sidecar deployments.

Research: `docs/research/standalone-proxy-http-server.md`

### Options Considered

- **Option A – Javalin 6 (Jetty 12)** (chosen)
  - Lightweight web framework on Jetty 12. Simplest API, virtual threads via
    `config.jetty.useVirtualThreads`, clean routing DSL, built-in middleware.
  - Pros: minimal boilerplate, excellent documentation, "reference adapter" code
    will be crystal clear. Jetty is production-proven.
  - Cons: adds Jetty transitive dependency (~5 MB). Jetty 12 is relatively new.

- **Option B – JDK HttpServer** (rejected)
  - Built-in `com.sun.net.httpserver.HttpServer`. Zero external dependencies.
  - Pros: no deps, virtual threads work natively, stable API.
  - Cons: no routing DSL, no middleware/filter chain, more manual boilerplate
    for routing, error handling, and header management. Sparse documentation.

- **Option C – Undertow** (rejected)
  - JBoss/Red Hat embedded server. High performance, non-blocking I/O.
  - Pros: very performant, lightweight (~2.4 MB), battle-tested in WildFly.
  - Cons: lower-level API, virtual threads not first-class (XNIO model),
    less community docs for standalone use.

- **Option D – Vert.x** (rejected)
  - Eclipse reactive framework. Highest raw performance (TechEmpower R23).
  - Pros: built-in `HttpProxy`, reactive event bus, top benchmarks.
  - Cons: reactive paradigm creates friction with synchronous `TransformEngine`,
    heavier dependency graph, steeper learning curve, over-engineered for our needs.

- **Option E – Netty (raw)** (eliminated)
  - Low-level networking framework. Maximum control and performance.
  - Eliminated: far too low-level. Would require building HTTP parsing, routing,
    error handling from scratch. Disproportionate effort for marginal gain.

### Decision Drivers

1. **Reference adapter clarity** — the standalone proxy is the first adapter others
   will study. Code simplicity is paramount.
2. **Virtual thread alignment** — the `TransformEngine` API is synchronous; virtual
   threads provide scalability without reactive complexity.
3. **Dependency budget** — the core module has zero gateway deps (NFR-001-02). The
   standalone adapter's own deps should be minimal but practical.
4. **Development velocity** — Javalin eliminates boilerplate that would slow Feature
   004 implementation.

## Decision

We adopt **Option A – Javalin 6 (Jetty 12)** as the HTTP server for the standalone
proxy module (`adapter-standalone`).

Concretely:
1. Add `io.javalin:javalin:6.x` as a dependency in `adapter-standalone/build.gradle.kts`.
2. Enable virtual threads via `config.jetty.useVirtualThreads = true`.
3. Use JDK's built-in `java.net.http.HttpClient` for upstream forwarding (zero
   additional dependency for the HTTP client).
4. The `GatewayAdapter<Context>` implementation wraps Javalin's `Context` object.
5. Javalin's middleware system handles cross-cutting concerns (logging, error handling).

## Consequences

Positive:
- Simplest possible adapter code — clear demonstration of `GatewayAdapter` pattern.
- Virtual threads provide high concurrency with synchronous code style.
- Javalin's routing DSL, error handling, and lifecycle management are production-ready.
- JDK `HttpClient` for upstream forwarding adds zero additional dependencies.

Negative / trade-offs:
- Jetty 12 transitive dependency (~5 MB). Acceptable for a standalone deployment.
- Adapter code is coupled to Javalin's `Context` type. This is by design — each
  adapter is coupled to its gateway's native types (per ADR-0025).
- Javalin 6 requires Java 17+ (we target 21, so no issue).

Follow-ups:
- Feature 004 spec: reference this ADR for HTTP server choice.
- `adapter-standalone/build.gradle.kts`: add Javalin dependency.
- `settings.gradle.kts`: include `adapter-standalone` module.
- `llms.txt`: add ADR-0029.
- `knowledge-map.md`: add ADR-0029 to traceability table.
- Remove Q-029 from `open-questions.md`.

References:
- Research: `docs/research/standalone-proxy-http-server.md`
- Feature 004 spec: `docs/architecture/features/004/spec.md`
- ADR-0025 – Adapter Lifecycle is Adapter-Scoped
- ADR-0013 – Copy-on-Wrap Message Adapter
- NFR-001-02 – Zero gateway-specific dependencies in core

Validating scenarios:
- S-004-01 through S-004-06 — basic proxy operation validates Javalin serves traffic
- S-004-44 — clean startup validates Javalin + Jetty lifecycle
- S-004-52 — HTTP/1.1 enforced validates JDK `HttpClient` upstream config
