# message-xform

> Payload & header transformation engine for API Gateways.

## Vision

A **standalone transformation engine** with gateway-specific adapter plugins. Transforms HTTP message payloads (request and response) and headers based on declarative YAML configuration, per API operation.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   message-xform                      │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │              Core Engine                      │   │
│  │  • YAML config parser (per-operation)         │   │
│  │  • JSON transformation engine                 │   │
│  │  • Header manipulation (read/write/strip)     │   │
│  │  • Payload field → header promotion           │   │
│  │  • Header → payload field injection           │   │
│  │  • RFC 9457 (Problem Details) error support   │   │
│  │  • Request transform pipeline                 │   │
│  │  • Response transform pipeline                │   │
│  └──────────────────────────────────────────────┘   │
│                        │                             │
│  ┌─────────┬──────────┼──────────┬──────────────┐   │
│  │         │          │          │              │   │
│  ▼         ▼          ▼          ▼              ▼   │
│ PingAccess  Kong     NGINX    Standalone     Future │
│  Adapter   Adapter  Adapter    (CLI/lib)   Adapters │
│                                                      │
└─────────────────────────────────────────────────────┘
```

### Modules

- **`core`** — Pure transformation logic. No gateway dependencies. Anti-corruption layer
  hides Jackson behind byte[] boundary (ADR-0032, ADR-0033).
- **`adapter-standalone`** — HTTP proxy mode for testing and non-gateway use (Feature 004).
- **`adapter-pingaccess`** — PingAccess plugin adapter (Feature 002, not yet created).

---

## Completed

> Items below are fully implemented and captured in specs, ADRs, and code.
> Listed here for historical reference only.

- **Feature 001 — Core Transformation Engine** — spec, scenarios, tasks, implementation
- **Feature 004 — Standalone HTTP Proxy** — spec, plan, tasks, implementation
- **Feature 009 — Toolchain & Quality Platform** — spec, implementation
- **Core byte-boundary refactor** — ADR-0032, ADR-0033. Core hides Jackson behind
  `byte[]`/`Map` boundary, bundles and relocates its own copy. Jackson 2.21.0 LTS.
- **SDD adoption** — Spec-driven development pipeline fully adopted
- **Governance** — githooks, commit-msg enforcement, Spotless formatter, quality gate,
  CI workflow, `ReadMe.LLM`, `llms.txt`
- **PA research** — Plugin API, classloader model (Spike A), dependency extraction (Spike B)

---

## Next Up

### Feature 002 — PingAccess Adapter

Primary production target. Spec complete (`docs/architecture/features/002/spec.md`),
SDK guide complete (`docs/reference/pingaccess-sdk-guide.md`).

### Artifact Publishing (FR-009-16, FR-009-17)

User-driven CI publishing via GitHub Actions. Two targets:
1. **Maven Central** — Gateway adapter JARs, standalone proxy shadow JAR. GPG-signed.
2. **Docker Hub** — Standalone proxy Docker image. Smoke-tested before push.

---

## Backlog

### Governance (low priority)

- [ ] **Session Reset Runbook** — `docs/operations/runbook-session-reset.md`
- [ ] **Session Quick Reference** — `docs/operations/session-quick-reference.md`
- [ ] **`CONTRIBUTING.md`** — External contributor guide

### Research

- [ ] **PingAM response format** — Document structure, explore transformation needs
- [ ] **Stateless flow patterns** — Research stateless transformation chain patterns
- [ ] **Kong plugin development** — Lua vs. Go plugin SDK
- [ ] **NGINX adapter** — njs, OpenResty, C module, or sidecar proxy approach
- [ ] **PingGateway** — Groovy filter model, shared Java core feasibility

### Research: Kong Transformer Plugins

Study Kong's request/response transformation to inform a gateway-agnostic design.

- [AI Request Transformer](https://developer.konghq.com/plugins/ai-request-transformer/)
- [AI Response Transformer](https://developer.konghq.com/plugins/ai-response-transformer/)
- [Request Transformer Advanced](https://developer.konghq.com/plugins/request-transformer-advanced/)
- [Response Transformer Advanced](https://developer.konghq.com/plugins/response-transformer-advanced/)
- [Custom Plugins](https://developer.konghq.com/custom-plugins/)

### Evaluate: Gateway Adapter Candidates

#### Tier 1 — Java-native (can reuse core directly)
| Gateway | Extension Model | OSS? |
|---------|----------------|------|
| PingAccess | Java plugin API | No |
| PingGateway | Java + Groovy filters | No |
| WSO2 API Manager | Java + Ballerina | Yes |
| Gravitee.io | Java plugins + Groovy | Yes |
| Apache APISIX | Java Plugin Runner | Yes |

#### Tier 2 — Possible with bridging (wrapper needed)
| Gateway | Extension Model | OSS? |
|---------|----------------|------|
| Kong | Lua, Go, WASM | Yes |
| Tyk | Go, JS, Python, Lua | Yes |
| NGINX | njs (JS), C module | Yes |

#### Tier 3 — Poor fit
| Gateway | Why |
|---------|-----|
| Zuplo | TypeScript-only SaaS |
| KrakenD | Go-only, anti-scripting philosophy |
| Traefik | Go-only, no Java path |
| Gloo Edge | WASM-based, Java→WASM immature |

### Cross-Language Portability Audit

Review all specs, ADRs, and interface contracts for language-agnostic portability.
Non-Java gateways (Kong=Lua, Tyk=Go, NGINX=C) may need native engine
reimplementations or sidecar bridge protocols.

- [ ] Review Java interface contracts for language-specific assumptions
- [ ] Document a "JSON wire protocol" spec for the sidecar/bridge pattern
- [ ] Evaluate cross-language expression engine contract (WASM, gRPC)

### E2E Gateway Integration Testing

Build and validate adapters against real gateway instances end-to-end.

| Priority | Gateway | Notes |
|----------|---------|-------|
| 1st | **Standalone proxy** | ✅ Done (Feature 004) |
| 2nd | **PingAccess** | Primary target — Java plugin API |
| 3rd | **PingGateway** | Groovy filters, shared Java core |
| 4th | **WSO2 API Manager** | Java-native, OSS |
| 5th | **Apache APISIX** | Java Plugin Runner, hot-reload |
| 6th | **Kong** | Lua/Go — requires bridging |
| 7th | **NGINX** | njs or sidecar — requires bridging |

---

*Created: 2026-02-07*
