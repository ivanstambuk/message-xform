# Roadmap – message-xform

## Features

| # | Feature | Status | Spec | Dependencies | Language |
|---|---------|--------|------|--------------|----------|
| 001 | Message Transformation Engine (core) | 📝 Spec Draft | `features/001/spec.md` | Research complete | Java 21 |
| 002 | PingAccess Adapter | 🔲 Not Started | `features/002/spec.md` | Feature 001 | Java (SDK) |
| 003 | PingGateway Adapter | 🔲 Not Started | `features/003/spec.md` | Feature 001 | Java / Groovy |
| 004 | Standalone HTTP Proxy Mode | 🔲 Not Started | `features/004/spec.md` | Feature 001 | Java |
| 005 | WSO2 API Manager Adapter | 🔲 Not Started | `features/005/spec.md` | Feature 001 | Java |
| 006 | Apache APISIX Adapter | 🔲 Not Started | `features/006/spec.md` | Feature 001 | Java (Plugin Runner) |
| 007 | Kong Gateway Adapter | 🔲 Not Started | `features/007/spec.md` | Feature 001 | Lua or sidecar |
| 008 | NGINX Adapter | 🔲 Not Started | `features/008/spec.md` | Feature 001 | njs / sidecar |

## Feature Overview

### Feature 001 — Message Transformation Engine (core)

The gateway-agnostic core library. Pluggable expression engine SPI (JSLT, JOLT, jq,
JSONata). Message envelope abstraction. Profile loading. Hot-reload. Error handling.
**This is the foundation — all adapters depend on it.**

### Feature 002 — PingAccess Adapter

**Primary target.** PingAccess Java Add-on SDK using `RuleInterceptor` SPI.
Wraps PingAccess `Exchange` (request/response) as the `Message` interface.
Research complete: `docs/research/pingaccess-plugin-api.md`.

### Feature 003 — PingGateway Adapter

Same Ping vendor ecosystem. Java/Groovy filter/handler chain.
Research pending: reference docs at `docs/reference/pinggateway-2025.11.txt`.

### Feature 004 — Standalone HTTP Proxy Mode

The engine running as an independent HTTP reverse proxy (no gateway needed).
Receives requests, applies transforms, forwards to upstream, transforms response.
Useful for development, testing, and deployments without a gateway.

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

> **Note:** PingAM callback transformations are covered as **test scenarios** in Feature 001
> (S-001-01 through S-001-05, S-001-29, S-001-32). They are validation data for the
> core engine, not a separate feature.

## Priority Order

Based on the gateway candidate research (`docs/research/gateway-candidates.md`):

| Tier | Features | Rationale |
|------|----------|-----------|
| **Tier 1** | 001 (core) + 002 (PingAccess) | MVP: core engine + primary adapter |
| **Tier 2** | 003 (PingGateway) + 004 (Standalone) | Same vendor ecosystem + dev/test tool |
| **Tier 3** | 005 (WSO2) + 006 (APISIX) | OSS gateways, Java-native, broader market |
| **Tier 4** | 007 (Kong) + 008 (NGINX) | Language gap — requires bridging strategy |

## Status Key

- 🔲 Not Started — No spec exists yet.
- 📝 Spec Draft — Spec is being written.
- ✅ Spec Ready — Spec approved, ready for implementation.
- 🔨 In Progress — Implementation underway.
- ✅ Complete — Shipped and verified.
