# Feature 004 – Standalone HTTP Proxy Mode

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-08 |
| Owners | Ivan |
| Roadmap entry | #4 – Standalone HTTP Proxy Mode |
| Depends on | Feature 001 (core engine) |
| Research | `docs/research/standalone-proxy-http-server.md` |
| Decisions | ADR-0029 (Javalin 6 / Jetty 12) |

## Overview

The message-xform engine running as an independent **HTTP reverse proxy** without any
gateway. Receives HTTP requests, applies matching transform profiles, forwards to the
configured upstream, transforms the response, and returns to the client.

Useful for:
- **Development & testing** — test transforms without deploying to a gateway
- **Sidecar pattern** — run alongside NGINX/Kong as a transformation sidecar
- **Lightweight deployments** — when a full gateway isn't needed
- **Kubernetes deployments** — Docker image for sidecar or standalone service

## Scope

### Core Proxy
- **Javalin 6 (Jetty 12)** embedded HTTP server with Java 21 virtual threads (ADR-0029)
- **JDK `HttpClient`** for upstream forwarding (zero additional dependencies)
- **`GatewayAdapter<Context>`** implementation bridging Javalin to the core engine
- Reverse proxy: receive → transform request → forward → transform response → return
- Transform profile matching by URL/method/content-type

### Configuration (YAML + env vars)
- **Structured backend config** — scheme/host/port (not a raw URL)
- Timeouts: connect-timeout-ms, read-timeout-ms
- **Connection pool** — max-connections, keep-alive, idle-timeout-ms
- **Body size limit** — max-body-bytes (10 MB default, 413 if exceeded)
- **Inbound TLS** (`proxy.tls`) — server cert, optional mTLS (client cert validation)
- **Outbound TLS** (`backend.tls`) — truststore validation, optional mTLS with backend
- Engine config: specs-dir, profiles-dir, schema-validation mode
- All knobs overridable via environment variables (no rebuild for tuning)

### Hot Reload
- **`WatchService`** — file-system watcher for specs/profiles directories
- **Admin endpoint** — `POST /admin/reload` for CI/CD orchestration
- Debounce mechanism (500ms default)

### Health & Readiness
- `GET /health` — liveness probe
- `GET /ready` — readiness probe (engine loaded + backend reachable)

### Docker & Kubernetes Deployment
- **Dockerfile** — multi-stage build (JDK build + JRE Alpine runtime, ~100 MB image)
- **Shadow JAR** — single fat JAR via Gradle shadow plugin (core + adapter + deps)
- **Environment variable config** — all operational knobs controllable via env vars
- **Volume mount points** — `/specs` and `/profiles` for YAML spec/profile files
- **Kubernetes sidecar pattern** — proxy container alongside backend in same pod
- **Kubernetes standalone service** — proxy as its own Deployment/Service
- **ConfigMap mounting** — specs and profiles delivered as K8s ConfigMaps

### Observability
- Structured JSON logging (Logback)
- Metrics (TBD — Micrometer / Prometheus endpoint)

## Non-Goals (v1)
- Multi-backend routing (one backend per proxy instance)
- Backend authentication (API key, JWT, OAuth) — config schema is extensible for future
- WebSocket proxying
- HTTP/2 upstream (HTTP/1.1 only for v1)
- Rate limiting / circuit breaker (use external tools)

## Implementation Language

**Java 21** — same as core. Virtual threads for concurrency.

## Status

🔬 Research complete. Spec in draft. Full specification to be written.
