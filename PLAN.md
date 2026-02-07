# message-xform

> Payload & header transformation engine for API Gateways.

## Vision

A **standalone transformation engine** with gateway-specific adapter plugins. Transforms HTTP message payloads (request and response) and headers based on declarative YAML configuration, per API operation.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   message-xform                      │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │              Core Engine                      │   │
│  │  • YAML config parser (per-operation)         │   │
│  │  • JSON transformation engine                 │   │
│  │  • XML transformation engine                  │   │
│  │  • JSON ↔ XML conversion                      │   │
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

### Separation of Concerns

- **`message-xform-core`** — Pure transformation logic. No gateway dependencies. Usable as a standalone Java library or CLI tool.
- **`message-xform-pingaccess`** — PingAccess plugin adapter (primary target).
- **`message-xform-kong`** — Kong plugin adapter.
- **`message-xform-nginx`** — NGINX module/adapter.
- **`message-xform-standalone`** — CLI / embedded HTTP proxy mode for testing and non-gateway use.

---

## Configuration Model

YAML-based, per API operation:

```yaml
# Example: transform for POST /api/v1/users
operations:
  - match:
      method: POST
      path: /api/v1/users
    
    request:
      # JSON field transformations
      transform:
        - source: "$.legacy.firstName"
          target: "$.user.first_name"
        - source: "$.legacy.lastName"
          target: "$.user.last_name"
      
      # Promote header values into payload
      headers-to-payload:
        - header: "X-Correlation-ID"
          target: "$.metadata.correlationId"
      
      # Promote payload fields to headers (with optional strip from body)
      payload-to-headers:
        - source: "$.auth.token"
          header: "Authorization"
          prefix: "Bearer "
          strip: true  # Remove field from payload after promotion
    
    response:
      transform:
        - source: "$.data.user_id"
          target: "$.userId"
      
      # RFC 9457 error response formatting
      error-format: rfc9457
```

---

## Core Features

### P0 — Must Have

| # | Feature | Notes |
|---|---------|-------|
| 1 | **JSON payload transformation** | Field mapping, renaming, restructuring |
| 2 | **HTTP header read/write** | Headers as input to transformations |
| 3 | **Payload ↔ header promotion** | Move fields to headers and vice versa, with optional stripping |
| 4 | **YAML config per operation** | Declarative, no code changes per API |
| 5 | **Request + response pipelines** | Bidirectional transformation |
| 6 | **PingAccess adapter** | Primary gateway target |
| 7 | **Standalone mode** | Core usable without any gateway |
| 8 | **RFC 9457 error responses** | Problem Details for HTTP APIs |

### P1 — Should Have

| # | Feature | Notes |
|---|---------|-------|
| 9 | **XML transformation** | XPath-based field mapping |
| 10 | **JSON ↔ XML conversion** | Content-type negotiation between legacy and modern APIs |
| 11 | **Kong adapter** | Second gateway target |
| 12 | **NGINX adapter** | Third gateway target |
| 13 | **Dockerized E2E testing** | Full gateway + upstream mock in Docker Compose |

### P2 — Could Have

| # | Feature | Notes |
|---|---------|-------|
| 14 | **PingAM response format** | Explore and support PingAM-specific response structures |
| 15 | **Stateless flow patterns** | Extract learnings from journeyoforge for stateless transformation chains |
| 16 | **Conditional transforms** | Apply transforms based on payload content or header values |
| 17 | **Transform chaining** | Compose multiple transforms in sequence |

---

## Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | **Java 21** | PingAccess/PingAM ecosystem requirement |
| Build tool | TBD | Maven or Gradle |
| JSON processing | TBD | Jackson, Gson, or JsonPath |
| XML processing | TBD | JAXB, StAX, or Saxon |
| Config format | **YAML** | Human-readable, Git-diffable |
| Testing | **Docker Compose** | E2E with real gateway instances |
| Module system | **Multi-module project** | core + adapter per gateway |

---

## Research & Exploration

- [ ] **PingAccess plugin API** — Understand extension points for request/response interception
- [ ] **PingAM response format** — Document structure, explore transformation needs
- [ ] **journeyoforge stateless flows** — Extract applicable patterns for stateless transformation chains
- [ ] **Kong plugin development** — Lua vs. Go plugin SDK
- [ ] **NGINX transformation** — njs (JavaScript) vs. native module approach
- [ ] **SDD adoption** — Study spec-driven development patterns from `sdd-specs/`, `journeyforge/`, and `openauth-sim/` for adoption in this project

---

## E2E Testing Strategy

```
┌────────────────────────────────────────────┐
│           Docker Compose Stack              │
│                                             │
│  ┌─────────┐    ┌────────────┐    ┌──────┐ │
│  │  Test    │───▶│  Gateway   │───▶│ Mock │ │
│  │  Client  │    │  + Plugin  │    │ API  │ │
│  │ (JUnit)  │◀───│(PingAccess)│◀───│      │ │
│  └─────────┘    └────────────┘    └──────┘ │
│                                             │
│  Assertions on:                             │
│  • Transformed request reaching mock        │
│  • Transformed response reaching client     │
│  • Error formatting (RFC 9457)              │
│  • Header promotion/injection               │
└────────────────────────────────────────────┘
```

---

## Open Questions

1. **Build tool preference?** Maven (more PingAccess-native) vs. Gradle (more modern)?
2. **JSONPath vs. JMESPath vs. custom DSL** for field addressing?
3. **Hot-reload of YAML config?** Or restart-required?
4. **Versioning strategy** for config schema?
5. **PingAccess version target?** (determines plugin API compatibility)
6. **Logging framework?** SLF4J + Logback standard, or gateway-specific?
7. **Performance requirements?** Max latency budget per transformation?
8. **Polyglot adapter strategy?** PingAccess requires Java, Kong plugins use Lua or Go, NGINX uses njs or C modules. Do we need a multi-language approach? Options: shared YAML config with language-specific adapter implementations, GraalVM polyglot, or WASM-based portable plugins.

---

## Backlog

### Adopt Spec-Driven Development (SDD)

Adopt the SDD methodology used in sibling projects. SDD mandates that every feature flows through a specification pipeline before implementation: **spec → plan → tasks → test → code**.

**Reference projects** (all under `~/dev/`):
- **`sdd-specs/`** — Spec-only repo with templates, schemas, catalogs, and validation tooling. Start here for the spec format and authoring guidelines.
- **`sdd-sample-bundle/`** — Example SDD bundle (`sdd-bundle.yaml`) with document types (Feature, Requirement, Task, ADR, Component, etc.) and layout conventions.
- **`journeyforge/`** — Full SDD adoption in a Java project: spec-first workflow, feature specs at `docs/4-architecture/features/<NNN>/`, ADRs, open-questions log, and two-phase LLM interaction protocol.
- **`openauth-sim/`** — Mature SDD example: mandatory spec pipeline, pre-implementation checklist, analysis gates, `llms.txt` manifest, and `ReadMe.LLM` for AI agents.

**Adoption tasks**:
- [ ] Set up `docs/` structure: templates, architecture, decisions, operations
- [ ] Create feature spec template adapted from `sdd-specs/specs/_template-spec-unit/`
- [ ] Create ADR template for architectural decisions
- [ ] Add `open-questions.md` for tracking ambiguities
- [ ] Define the spec → plan → tasks → code pipeline in `AGENTS.md`
- [ ] Optionally: create an SDD bundle for this project using `sdd-sample-bundle` as reference

### Research: Kong Transformer Plugins

Study how Kong implements request/response transformation to inform a generalized, gateway-agnostic design.

**Links to investigate**:
- [AI Request Transformer](https://developer.konghq.com/plugins/ai-request-transformer/)
- [AI Response Transformer](https://developer.konghq.com/plugins/ai-response-transformer/)
- [Request Transformer Advanced](https://developer.konghq.com/plugins/request-transformer-advanced/)
- [Response Transformer Advanced](https://developer.konghq.com/plugins/response-transformer-advanced/)
- [Custom Plugins](https://developer.konghq.com/custom-plugins/)

**Goals**: Understand Kong's transformation model, configuration surface, and plugin architecture. Extract patterns that can be generalized across gateways (PingAccess, Kong, NGINX, standalone).

### Research: PingGateway (formerly ForgeRock IG)

PingGateway is a **separate product** from PingAccess within the Ping Identity suite. It's a programmable reverse proxy / identity gateway with its own filter/handler chain model. Since message-xform already targets the Ping ecosystem, PingGateway is a natural candidate.

**Research goals:**
- [ ] Understand PingGateway's filter/handler pipeline (how request/response interception works)
- [ ] Evaluate Groovy scripting model for custom filters
- [ ] Compare PingGateway's extension points with PingAccess plugin API
- [ ] Determine if a shared Java core can serve both PingAccess and PingGateway adapters
- [ ] Check PingAM 8 docs (`docs/reference/pingam-8.txt`) for PingGateway references

### Research: NGINX Adapter

Investigate how to integrate message-xform with NGINX. Unlike PingAccess/PingGateway/WSO2, NGINX has **no native Java interop**, so a bridging strategy is needed.

**Possible approaches (decision deferred to implementation):**
1. **njs (JavaScript)** — NGINX's built-in JS scripting. Lightweight, but limited API surface.
2. **OpenResty (Lua)** — Lua scripting on NGINX (same ecosystem as Kong). Mature, but no Java core reuse.
3. **C module** — Native NGINX module. Maximum performance, highest development cost, no Java core reuse.
4. **Standalone sidecar proxy** — Run `message-xform-standalone` as a sidecar HTTP proxy; NGINX proxies through it. Avoids the language gap entirely and reuses the Java core as-is.

**Research goals:**
- [ ] Evaluate each approach for performance, complexity, and maintenance burden
- [ ] Study existing NGINX transformation modules for design patterns
- [ ] Determine if the sidecar proxy approach imposes acceptable latency overhead

### Evaluate: Gateway Adapter Candidates

Assess which API gateways are realistic targets for message-xform adapters, beyond the initially planned PingAccess/Kong/NGINX.

**Evaluation criteria:**
1. Java interop — can the gateway call Java code natively? (critical for core reuse)
2. Open source — is the gateway OSS or does it require a commercial license?
3. Market relevance — is it widely deployed in enterprise environments?
4. Plugin complexity — how hard is it to write a request/response transformation plugin?

**Candidate tiers (initial assessment):**

#### Tier 1 — Java-native (can reuse core directly)
| Gateway | Extension Model | OSS? | Notes |
|---------|----------------|------|-------|
| PingAccess | Java plugin API | No | Primary target, already planned |
| PingGateway | Java + Groovy filters | No | Same Ping ecosystem |
| WSO2 API Manager | Java + Ballerina | Yes | Full lifecycle, Java-native |
| Gravitee.io | Java plugins + Groovy | Yes | Event-native, Kafka support |
| Apache APISIX | Java Plugin Runner | Yes | Hot-reload, high performance |
| Google Apigee | Java callouts | No (SaaS) | Enterprise, but commercial/SaaS lock-in |

#### Tier 2 — Possible with bridging (Go/Lua, wrapper needed)
| Gateway | Extension Model | OSS? | Notes |
|---------|----------------|------|-------|
| Kong | Lua, Go, WASM | Yes | Already planned, massive ecosystem |
| Tyk | Go, JS, Python, Lua | Yes | Go-native, multi-language |
| NGINX | njs (JS), C module | Yes | Already planned |

#### Tier 3 — Poor fit (wrong ecosystem / philosophy mismatch)
| Gateway | Why poor fit |
|---------|-------------|
| Zuplo | TypeScript-only, commercial SaaS, no Java interop |
| KrakenD | Go-only, anti-scripting philosophy (speed over extensibility) |
| Traefik | Go-only via Yaegi interpreter, no Java path |
| Gloo Edge (Envoy) | WASM-based, Java→WASM compilation is immature |

**Decision needed:** Which Tier 1/2 candidates to prioritize for adapter development after PingAccess?

---

*Created: 2026-02-07*  
*Status: DRAFT — Initial braindump, needs refinement*
