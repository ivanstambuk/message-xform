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
| 9 | **Kong adapter** | Second gateway target |
| 10 | **NGINX adapter** | Third gateway target |
| 11 | **Dockerized E2E testing** | Full gateway + upstream mock in Docker Compose |

### P2 — Could Have

| # | Feature | Notes |
|---|---------|-------|
| 14 | **PingAM response format** | Explore and support PingAM-specific response structures |
| 15 | **Stateless flow patterns** | Research stateless transformation chain patterns from prior art |
| 16 | **Conditional transforms** | Apply transforms based on payload content or header values |
| 17 | **Transform chaining** | Compose multiple transforms in sequence |

---

## Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | **Java 21** | PingAccess/PingAM ecosystem requirement |
| Build tool | TBD | Maven or Gradle |
| JSON processing | TBD | Jackson, Gson, or JsonPath |

| Config format | **YAML** | Human-readable, Git-diffable |
| Testing | **Docker Compose** | E2E with real gateway instances |
| Module system | **Multi-module project** | core + adapter per gateway |

---

## Research & Exploration

- [x] **PingAccess plugin API** — Extension points fully documented (`docs/research/pingaccess-plugin-api.md`, `docs/research/pingaccess-docker-and-sdk.md`). Docker image (9.0.1) pulled, SDK JAR decompiled, installer SDK samples analyzed. Key finding: `RuleInterceptor` SPI with `handleRequest`/`handleResponse` is the primary integration point; must use Site rules for body access.
- [x] **Spike A: PA classloader model discovery** — ✅ Resolved via static analysis (decompiling `run.sh` + `javap` bytecode). PA uses a **flat classpath** with no classloader isolation. `lib/*` and `deploy/*` share the same `AppClassLoader`. Jackson relocation is unnecessary. Full findings: `docs/research/spike-pa-classloader-model.md`.
- [ ] **Spike B: PA dependency version extraction** — Create a script to extract all library versions from a PA Docker image. Design a version-locked build strategy where the adapter pins its `compileOnly` dependencies to PA's exact versions. Full plan: `docs/research/spike-pa-dependency-extraction.md`.
- [ ] **PingAM response format** — Document structure, explore transformation needs
- [ ] **Stateless flow patterns** — Research stateless transformation chain patterns from prior art
- [ ] **Kong plugin development** — Lua vs. Go plugin SDK
- [ ] **NGINX transformation** — njs (JavaScript) vs. native module approach
- [ ] **SDD adoption** — Study spec-driven development patterns from `sdd-specs/` and `openauth-sim/` for adoption in this project

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

**Key principle: Specs are gateway-agnostic, implementations are gateway-specific.**
- **Specifications** describe *what* the transformation does — field mappings, header promotions, callback handling — in language- and platform-neutral terms. One spec covers all gateways.
- **Implementation plans** describe *how* a specific adapter realizes the spec — Java plugin API for PingAccess, Groovy filter for PingGateway, Lua plugin for Kong, etc.
- This separation means the spec pipeline is **highly reusable** across adapters. The same spec drives PingAccess, PingGateway, WSO2, NGINX, and standalone implementations. Only the implementation plan and tasks diverge per gateway.

**Reference projects** (all under `~/dev/`):
- **`sdd-specs/`** — Spec-only repo with templates, schemas, catalogs, and validation tooling. Start here for the spec format and authoring guidelines.
- **`sdd-sample-bundle/`** — Example SDD bundle (`sdd-bundle.yaml`) with document types (Feature, Requirement, Task, ADR, Component, etc.) and layout conventions.
- **`openauth-sim/`** — Mature SDD example: mandatory spec pipeline, pre-implementation checklist, analysis gates, `llms.txt` manifest, and `ReadMe.LLM` for AI agents.

**Adoption tasks**:
- [ ] Set up `docs/` structure: templates, architecture, decisions, operations
- [ ] Create feature spec template adapted from `sdd-specs/specs/_template-spec-unit/`
- [ ] Create ADR template for architectural decisions
- [ ] Add `open-questions.md` for tracking ambiguities
- [ ] Define the spec → plan → tasks → code pipeline in `AGENTS.md`
- [ ] Optionally: create an SDD bundle for this project using `sdd-sample-bundle` as reference

### Governance Parity: Deferred Items (gap analysis 2026-02-08)

Items identified from a systematic gap analysis against `openauth-sim`
sibling project. Each item has a **trigger** — the point at
which it should be picked up.

**Trigger: When Gradle project is initialized (Feature 001 implementation start)**

- [x] **`githooks/` directory** — Created `pre-commit` (spotlessCheck + check) and
  `commit-msg` (conventional commit enforcement via shell script). No external deps.
- [x] **`.gitlint` config** — Created as reference config. Active enforcement is via
  the self-contained `commit-msg` hook (no gitlint installation required).
- [x] **Hook Guard in AGENTS.md** — Activated as a MANDATORY prerequisite in Build &
  Test Commands section.
- [x] **Fill Build & Test Commands section** — Replaced stub with real `./gradlew`
  targets: full gate, module tests, single test class, shadow JAR, Docker build.
- [x] **`JAVA_HOME` prerequisite check** — Documented in Build & Test Commands
  prerequisites. Pre-commit hook sources SDKMAN automatically.
- [x] **Formatter policy** — Palantir Java Format 2.78.0 via Spotless 8.1.0.
  Documented in AGENTS.md Build & Test Commands section.

**Trigger: After first passing quality gate (Feature 001 implementation midpoint)**

- [x] **Quality Gate documentation** — Created `docs/operations/quality-gate.md` with
  pipeline explanation, failure interpretation, and prerequisites.
- [x] **CI workflow** — Created `.github/workflows/ci.yml` with: spotlessCheck, check,
  commit message lint (PRs), Docker build + size check + smoke test.

**Trigger: Nice-to-have (low priority, pick up when convenient)**

- [ ] **Session Reset Runbook** — Create `docs/operations/runbook-session-reset.md`. Our
  `/init` workflow already covers most of this, but a standalone runbook adds resilience.
- [ ] **Session Quick Reference** — Create `docs/operations/session-quick-reference.md`
  with session kickoff checklist and handoff prompt template.
- [x] **`ReadMe.LLM`** — Created LLM-oriented project overview. Covers: what the project
  does, module layout, key entry points, build commands, and docs navigation guide.
- [ ] **`CONTRIBUTING.md`** — External contributor guide. Low priority for solo project,
  but good practice if the project becomes multi-contributor.

**Trigger: When Gradle project is initialized — Meta-Feature**

- [x] **Feature 009 — Toolchain & Quality Platform** — Create a lightweight feature spec
  covering: build toolchain decisions (Gradle version, Java version, dependency management),
  code quality gates (Spotless formatter, static analysis, ArchUnit architectural tests),
  test infrastructure (JUnit 5, AssertJ, WireMock), and CI pipeline. Model on
  `openauth-sim/docs/4-architecture/features/013/` (Toolchain & Quality Platform) but keep
  it proportional to message-xform's current scope. This feature captures *how* we build,
  not *what* we build. Decisions made here feed into the deferred governance items above
  (githooks, gitlint, formatter policy, quality gate docs, CI workflow).
  **Note:** Do NOT over-engineer this upfront. Start with a minimal spec covering the
  first 3-5 toolchain decisions needed for Feature 001 implementation, then expand
  incrementally as new toolchain questions arise.

**Trigger: Before first release — Artifact Publishing**

- [ ] **Artifact publishing (FR-009-16, FR-009-17)** — User-driven CI publishing via
  GitHub Actions (`workflow_dispatch` + `release` events). Two targets:
  1. **Maven Central** — Gateway adapter JARs as individual artifacts (`message-xform-core`,
     `message-xform-<gateway>`), standalone proxy as a shadow JAR. GPG-signed. Uses
     `org.danilopianini.publish-on-central` plugin (same as `openauth-sim`).
  2. **Docker Hub** — Standalone proxy Docker image (`ivanstambuk/message-xform-proxy`),
     tagged with `latest` + release version. Smoke-tested before push.
  Reference: `openauth-sim/.github/workflows/publish-standalone.yml`.
  Spec: `docs/architecture/features/009/spec.md` (FR-009-16, FR-009-17).

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

### Cross-Language Portability Audit

Revisit all Feature 001 specs, ADRs, and interface contracts to evaluate whether they
are **language-agnostic** enough to support non-Java implementations (Lua, Go, C, Python).

The core engine is Java-first, but Tier 2 gateways (Kong=Lua, Tyk=Go, NGINX=C) may need
native engine reimplementations or sidecar bridge protocols. The spec-level contracts
(TransformContext, Message, ExpressionEngine SPI, GatewayAdapter SPI) should be expressible
in any language without Java-isms leaking through.

**Triggered by:** ADR-0020 (nullable status code) exposed that a Java `int` sentinel
was incompatible with the language-neutral `null` contract. Other contracts may have
similar issues.

**Audit scope:**
- [ ] Review all Java interface contracts (TransformContext, Message, ExpressionEngine,
      CompiledExpression, GatewayAdapter) for Java-specific assumptions
- [ ] Review all ADRs for language-specific implications
- [ ] Document a "JSON wire protocol" spec for the sidecar/bridge pattern — this is the
      language-neutral serialization of TransformContext, Message, and TransformResult
- [ ] Evaluate whether the expression engine SPI should define a cross-language contract
      (e.g., WASM, gRPC) or remain Java-only with sidecar as the bridge

**When:** After all current open questions (Q-021 through Q-027) are resolved.

### E2E Gateway Integration Testing

Build and validate message-xform adapters against real gateway instances, end-to-end. For each gateway, the workflow is:

1. **Dockerize the gateway** — Docker Compose stack with the gateway, a mock upstream API (e.g., WireMock or a simple stub), and a test client.
2. **Build the adapter locally** — Compile the `message-xform-<gateway>` adapter module and deploy the plugin/filter into the running gateway container.
3. **Deploy transform specs** — Load YAML transform specs and profiles that exercise the core features (field mapping, header promotion, status code transforms, bidirectional specs).
4. **Run E2E validation** — Automated test suite (JUnit or shell-based) that sends requests through the gateway, asserts on transformed payloads reaching the mock, and validates responses back to the client.

**Gateway testing order** (follows the roadmap tiers):

| Priority | Gateway | Notes |
|----------|---------|-------|
| 1st | **Standalone proxy** | `message-xform-standalone` as an embedded HTTP proxy — no gateway dependency. Validates core API and GatewayAdapter SPI with zero external dependencies. |
| 2nd | **PingAccess** | Primary production target — Java plugin API. Docker image available via Ping Identity. |
| 3rd | **PingGateway** | Same Ping ecosystem — Groovy filters, shared Java core. |
| 4th | **WSO2 API Manager** | Java-native, OSS — Docker images on Docker Hub. |
| 5th | **Apache APISIX** | Java Plugin Runner, hot-reload — Docker Compose ready. |
| 6th | **Kong** | Lua/Go plugin — requires bridging strategy. |
| 7th | **NGINX** | njs or sidecar proxy — requires bridging strategy. |

**Key deliverables per gateway:**
- [ ] `docker-compose.<gateway>.yml` — Gateway + mock API + test client stack
- [ ] Adapter module built and deployable as a plugin/filter
- [ ] Scenario-driven test suite covering core transform features
- [ ] CI-ready: tests runnable via a single `make test-e2e-<gateway>` command

---

*Created: 2026-02-07*  
*Status: DRAFT — Initial braindump, needs refinement*
