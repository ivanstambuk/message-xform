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

---

*Created: 2026-02-07*  
*Status: DRAFT — Initial braindump, needs refinement*
