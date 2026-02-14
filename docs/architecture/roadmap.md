# Roadmap – message-xform

## Features

| # | Feature | Status | Spec | Dependencies | Language |
|---|---------|--------|------|--------------|----------|
| 001 | Message Transformation Engine (core) | ✅ Complete | `features/001/spec.md` | Research complete | Java 21 |
| 002 | PingAccess Adapter | 🔨 In Progress | `features/002/spec.md` | Feature 001 | Java 21 (PA SDK 9.0) |
| 003 | PingGateway Adapter | 🔲 Not Started | `features/003/spec.md` | Feature 001 | Java / Groovy |
| 004 | Standalone HTTP Proxy Mode | ✅ Complete | `features/004/spec.md` | Feature 001 | Java |
| 005 | WSO2 API Manager Adapter | 🔲 Not Started | `features/005/spec.md` | Feature 001 | Java |
| 006 | Apache APISIX Adapter | 🔲 Not Started | `features/006/spec.md` | Feature 001 | Java (Plugin Runner) |
| 007 | Kong Gateway Adapter | 🔲 Not Started | `features/007/spec.md` | Feature 001 | Lua or sidecar |
| 008 | NGINX Adapter | 🔲 Not Started | `features/008/spec.md` | Feature 001 | njs / sidecar |
| 009 | Toolchain & Quality Platform | 📋 Spec Ready | `features/009/spec.md` | Feature 001 (co-created) | Java / Gradle |

## Feature Overview

### Feature 001 — Message Transformation Engine (core)

The gateway-agnostic core library. Pluggable expression engine SPI (JSLT, JOLT, jq,
JSONata). Message envelope abstraction. Profile loading. Hot-reload. Error handling.
**This is the foundation — all adapters depend on it.**

### Feature 002 — PingAccess Adapter

**Primary target.** PingAccess 9.0 Java Add-on SDK using `AsyncRuleInterceptor` SPI
(Site-only — Agent rules cannot access body or handle responses).
Wraps PingAccess `Exchange` (request/response) as the core `Message` record via
`GatewayAdapter<Exchange>`. PingAccess 9.0 supports Java 21 — same toolchain as core.
Research complete: `docs/research/pingaccess-plugin-api.md`.

### Feature 003 — PingGateway Adapter

Same Ping vendor ecosystem. Java/Groovy filter/handler chain.
Research pending: reference docs at `docs/reference/vendor/pinggateway-2025.11.txt`.

### Feature 004 — Standalone HTTP Proxy Mode

The engine running as an independent HTTP reverse proxy (no gateway needed).
Receives requests, applies transforms, forwards to upstream, transforms response.
Useful for development, testing, and deployments without a gateway.

**Key deliverables:**
- Javalin 6 (Jetty 12) embedded HTTP server with Java 21 virtual threads (ADR-0029)
- JDK `HttpClient` for upstream forwarding (zero additional deps)
- Full YAML configuration: TLS (inbound + outbound), connection pool, timeouts, body limits
- Environment variable overrides for all config knobs (no rebuild for tuning)
- Hot reload via `WatchService` + admin endpoint
- Health/readiness endpoints for liveness/readiness probes
- **Docker image** — multi-stage build, shadow JAR, JRE 21 Alpine (~100 MB)
- **Kubernetes deployment patterns** — sidecar proxy, standalone service,
  ConfigMap-based spec/profile mounting

Research: `docs/research/standalone-proxy-http-server.md` (all questions resolved).
Decisions: ADR-0029 (Javalin).

### Feature 005 — WSO2 API Manager Adapter

Java-native, genuinely open source (Apache 2.0). Direct integration via Java
extension API. No bridging needed — same JVM as the core.

### Feature 006 — Apache APISIX Adapter

OSS gateway with a dedicated Java Plugin Runner (Unix socket/RPC to APISIX).
The Java core runs as-is inside the Plugin Runner. Supports hot-reload.

### Feature 007 — Kong Gateway Adapter

Kong's extension model is Lua (OpenResty) / Go / WASM — no direct Java path.
Options: (a) Lua reimplementation (defeats shared core), (b) sidecar proxy,
(c) WASM (immature). **Approach TBD during research phase.**

### Feature 008 — NGINX Adapter

Similar to Kong — no native Java. Four approaches documented:
(a) njs (JavaScript), (b) OpenResty (Lua), (c) C module, (d) sidecar proxy.
**Sidecar is the pragmatic default** — run standalone mode, NGINX proxies through.

### Feature 009 — Toolchain & Quality Platform

**Meta-feature.** Captures *how* we build: Gradle configuration, Java version,
dependency management, code formatter (Spotless), static analysis, ArchUnit
architectural tests, test infrastructure (JUnit 5, AssertJ, WireMock), Git hooks,
gitlint, and CI pipeline. Decisions made here feed into all other features.
**Created when Gradle project is initialized alongside Feature 001.**

> **Note:** PingAM callback transformations are covered as **test scenarios** in Feature 001
> (S-001-01 through S-001-05, S-001-29, S-001-32). They are validation data for the
> core engine, not a separate feature.

## Priority Order

Based on the gateway candidate research (`docs/research/gateway-candidates.md`),
updated 2026-02-08 to promote Feature 004 as the E2E test harness:

| Tier | Features | Rationale |
|------|----------|-----------|
| **Tier 1** | 001 (core) + 004 (Standalone) | Core engine + E2E test harness. Standalone proxy validates the core API and GatewayAdapter SPI with zero external dependencies. Also serves as the reference adapter for all subsequent gateway integrations. |
| **Tier 2** | 002 (PingAccess) + 003 (PingGateway) | Primary production target + same Ping ecosystem |
| **Tier 3** | 005 (WSO2) + 006 (APISIX) | OSS gateways, Java-native, broader market |
| **Tier 4** | 007 (Kong) + 008 (NGINX) | Language gap — requires bridging strategy |

## Status Key

- 🔲 Not Started — No spec exists yet.
- 🔬 Research — Research phase in progress, decisions being made.
- 📝 Spec Draft — Spec is being written.
- ✅ Spec Ready — Spec approved, ready for implementation.
- 🔨 In Progress — Implementation underway.
- ✅ Complete — Shipped and verified.
