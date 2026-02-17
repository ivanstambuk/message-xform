# Knowledge Map

Status: Draft | Last updated: 2026-02-17

This document maps the architectural relationships between modules, specs, ADRs,
and key concepts in message-xform. It serves as a navigational aid for agents and
contributors to understand how components connect.

## Module Structure (planned)

```
message-xform/
├── core/                          # Transform engine (zero gateway deps)
│   ├── model/                     # TransformSpec, TransformProfile, Message, HeaderSpec, StatusSpec
│   ├── engine/                    # TransformEngine, TransformRegistry, profile matching, header + status transforms
│   ├── spec/                      # SpecParser — YAML to TransformSpec loading
│   ├── spi/                       # ExpressionEngine, TelemetryListener
│   ├── schema/                    # JSON Schema validation
│   └── test-vectors/              # Golden YAML fixtures (FX-001-01/02/03)
├── engine-jslt/                   # JSLT expression engine plugin
├── engine-jolt/                   # JOLT expression engine plugin (future)
├── adapter-pingaccess/            # PingAccess 9.0 gateway adapter (Feature 002)
│   ├── PingAccessAdapter          # GatewayAdapter<Exchange> — Exchange↔Message bridge
│   ├── MessageTransformRule       # AsyncRuleInterceptorBase plugin (Site-only)
│   └── MessageTransformConfig     # @UIElement-annotated configuration
├── adapter-pinggateway/           # PingGateway adapter (future)
├── adapter-standalone/            # Standalone HTTP proxy mode (Feature 004, Complete)
│   ├── adapter/                   # StandaloneAdapter — GatewayAdapter SPI impl
│   ├── config/                    # ConfigLoader, ProxyConfig, EnvVarOverlay
│   ├── proxy/                     # ProxyHandler, UpstreamClient, FileWatcher
│   ├── tls/                       # TlsConfigurator — inbound/outbound TLS/mTLS
│   └── docker/                    # Dockerfile, config.yaml
└── docs/                          # Specifications, ADRs, research
```

## Concept Map

```
TransformSpec ──────── defines ──────── Transformation Logic
  │                                        │
  ├── id + version                         ├── transform block (JSLT expr)
  ├── input/output schemas                 ├── headers block (add/remove/rename)
  ├── reverse block (bidirectional)        ├── status block (status code)
  ├── mappers block (named exprs)          ├── url block (path/query rewrite)
  ├── apply directive (pipeline)           └── sensitive list (redaction paths, ADR-0019)
  └── lang: <engineId>
       │
       └── resolved by ──── ExpressionEngine SPI (FR-001-02)
                              │
                              ├── jslt (baseline)
                              ├── jolt (structural)
                              └── jq, jsonata, dataweave (future)

TransformProfile ────── binds ──────── Spec to URL Pattern
  │                                        │
  ├── spec: id@version (ADR-0005)          ├── match: path, method, content-type
  ├── direction: request/response          └── most-specific-wins (ADR-0006)
  └── multiple profiles → specificity scoring
                              │
                              └── NFR-001-08: structured match logging

TelemetryListener SPI (ADR-0007) ──── receives ──── Transform Events
  │                                                    │
  ├── onTransformStarted/Completed/Failed              ├── specId, version, direction
  ├── onProfileMatched                                 ├── duration, outcome
  └── onSpecLoaded/Rejected                            └── trace context (NFR-001-10)

TransformRegistry (DO-001-10) ──── snapshot ──── Engine State
  │                                                    │
  ├── Map<id|id@version → TransformSpec>               ├── immutable (defensive copy)
  ├── optional TransformProfile                        ├── AtomicReference in engine
  └── Builder + empty() factory                        └── reload() builds new → set()
                                                            │
                                                            ├── fail-safe: exception prevents swap
                                                            └── NFR-001-05 validated
```

## ADR Dependency Graph

```
ADR-0001 (Mandatory JSON Schema)
ADR-0002 (Header Transforms) ──── extends ──── ADR-0001
ADR-0003 (Status Code Transforms) ── extends ── ADR-0001
ADR-0004 (Engine Support Matrix) ── constrains ── ADR-0002, ADR-0003
ADR-0005 (Version Pinning) ──── governs ──── FR-001-05
ADR-0006 (Match Resolution) ── governs ── FR-001-05, introduces NFR-001-08
ADR-0007 (Observability) ──── introduces ──── NFR-001-09, NFR-001-10
ADR-0008 (Single Expr/Dir) ── constrains ── FR-001-01, references ADR-0004
ADR-0009 (JSLT Default) ──── governs ──── FR-001-01, FR-001-02
ADR-0010 (Pluggable SPI) ─── governs ──── FR-001-02, references ADR-0009
ADR-0011 (JsonNode Body) ─── governs ──── FR-001-04, references ADR-0009, ADR-0010
ADR-0012 (Pipeline Chain) ─── governs ── FR-001-05, references ADR-0008
ADR-0013 (Copy-on-Wrap) ──── governs ── FR-001-04, references ADR-0011, ADR-0012
ADR-0014 (Mapper Invoke) ─── governs ── FR-001-08, references ADR-0008, ADR-0010, ADR-0012
ADR-0015 (Spec Match)  ───── governs ── FR-001-01, FR-001-05, references ADR-0006
ADR-0016 (Uni Direction) ─── governs ── FR-001-03, references ADR-0015
ADR-0017 (Status Null)   ──── governs ── FR-001-11, references ADR-0003, ADR-0016
ADR-0018 (Body Buffer)   ──── governs ── FR-001-04, references ADR-0011, ADR-0013
ADR-0019 (Sensitive)     ──── governs ── FR-001-01, NFR-001-06, references ADR-0015
ADR-0020 (Nullable Status) ── governs ── FR-001-02, FR-001-11, references ADR-0017
ADR-0021 (Query+Cookies) ─── governs ── FR-001-02, references ADR-0020, ADR-0004
ADR-0022 (Error Response) ── governs ── FR-001-07, FR-001-06, references ADR-0001, ADR-0012, ADR-0013
ADR-0023 (Cross-Profile)  ── governs ── FR-001-05, references ADR-0006
ADR-0024 (Error Catalogue) ─ governs ── FR-001-07, FR-001-02, references ADR-0022, ADR-0001, ADR-0012, ADR-0019
ADR-0025 (Adapter Lifecycle) governs ── Gateway Adapter SPI, references ADR-0023, ADR-0024
ADR-0026 (Multi-Value Hdrs)  governs ── FR-001-10, references ADR-0002, ADR-0021
ADR-0027 (URL Rewriting)     governs ── FR-001-12, references ADR-0002, ADR-0003, ADR-0013
ADR-0028 (Perf Strategy)     governs ── NFR-001-03, references Feature 009
ADR-0029 (Javalin Proxy)     governs ── FR-004-01, references ADR-0025, ADR-0013
ADR-0030 (Session Context)   governs ── FR-001-13, references ADR-0021, ADR-0020, ADR-0025
ADR-0031 (PA-Provided Deps)  governs ── FR-002-06, FR-002-09 (partially superseded by ADR-0032)
ADR-0032 (Core ACL)          governs ── FR-001-14, NFR-001-02, references ADR-0031, ADR-0033
ADR-0033 (Port Value Objects) governs ── FR-001-14, references ADR-0032
ADR-0034 (SLF4J Binding)     governs ── NFR-001-02, references ADR-0032, ADR-0033
ADR-0035 (Version Parity)    governs ── FR-002-09, supersedes ADR-0031 §7-§8, references ADR-0031
ADR-0036 (Conditional Routing) governs ── FR-001-15, FR-001-16, updates ADR-0003, ADR-0006, ADR-0012
```

## Feature → ADR → NFR Traceability

| Feature | ADRs | NFRs |
|---------|------|------|
| FR-001-01 (Spec Format) | ADR-0001, ADR-0008, ADR-0009, ADR-0015, ADR-0019 | — |
| FR-001-02 (Expression Engine SPI) | ADR-0004, ADR-0009, ADR-0010, ADR-0020, ADR-0021 | NFR-001-02 |
| FR-001-03 (Bidirectional) | ADR-0016 | — |
| FR-001-08 (Reusable Mappers) | ADR-0014 | — |
| FR-001-04 (Message Envelope) | ADR-0011, ADR-0013, ADR-0018, ADR-0022 | — |
| FR-001-05 (Transform Profiles) | ADR-0005, ADR-0006, ADR-0012, ADR-0015, ADR-0022, ADR-0023 | NFR-001-08 |
| FR-001-10 (Header Transforms) | ADR-0002, ADR-0004, ADR-0026 | — |
| FR-001-11 (Status Code Transforms) | ADR-0003, ADR-0004, ADR-0017, ADR-0020 | — |
| Observability | ADR-0007 | NFR-001-08, NFR-001-09, NFR-001-10 |
| FR-001-06 (Passthrough) | ADR-0022 | — |
| FR-001-07 (Error Handling) | ADR-0022, ADR-0024 | — |
| Gateway Adapter SPI | ADR-0025 | — |
| FR-001-09 (Schema Validation) | ADR-0001, ADR-0022 | — |
| FR-001-12 (URL Rewriting) | ADR-0027 | — |
| FR-001-13 (Session Context) | ADR-0030 | — |
| FR-001-14 (Byte Boundary) | ADR-0032, ADR-0033, ADR-0034 | NFR-001-02 |
| FR-001-15 (Status Routing) | ADR-0036, ADR-0006 | — |
| FR-001-16 (Body Predicate Routing) | ADR-0036, ADR-0006, ADR-0012 | — |
| NFR-001-03 (Latency) | ADR-0028 | NFR-001-03 |
| Hot Reload | — | NFR-001-05 |
| FR-002-01 (GatewayAdapter) | ADR-0013, ADR-0025 | — |
| FR-002-02 (AsyncRuleInterceptor) | ADR-0025 | NFR-002-01, NFR-002-03 |
| FR-002-03 (@Rule Annotation) | — | — |
| FR-002-04 (Plugin Config) | — | — |
| FR-002-05 (Plugin Lifecycle) | ADR-0025 | — |
| FR-002-06 (Session Context) | ADR-0030, ADR-0031 | — |
| FR-002-07 (ExchangeProperty) | — | — |
| FR-002-08 (SPI Registration) | — | — |
| FR-002-09 (Deployment Packaging) | ADR-0031, ADR-0032, ADR-0035 | NFR-002-02 |
| FR-002-10 (Gradle Module) | — | NFR-002-05 |
| FR-002-11 (Error Handling) | ADR-0022, ADR-0024 | — |
| FR-002-12 (Docker E2E Test) | `scripts/pa-e2e-bootstrap.sh`, `scripts/pa-e2e-infra-up.sh`, `scripts/pa-e2e-infra-down.sh`, `e2e-pingaccess/`, `e2e/pingaccess/specs/` | `e2e-pingaccess/docs/e2e-results.md` |
| FR-004-01 (Standalone Proxy) | ADR-0029 | NFR-004-01 through NFR-004-07 |
| FR-004-02..13 (Transform) | ADR-0029, ADR-0025, ADR-0013 | — |
| FR-004-14..17 (TLS/mTLS) | — | NFR-004-05 |
| FR-004-18..22 (Health/Admin) | — | — |
| FR-004-29..32 (Docker/K8s) | — | NFR-004-04 |
| FR-004-33..34 (Protocol) | ADR-0029 | — |
| FR-004-36..39 (Context) | ADR-0021, ADR-0029 | — |
| FR-009-01..10 (Baseline toolchain) | — | NFR-009-01 |
| FR-009-11 (EditorConfig) | — | — |
| FR-009-12 (ArchUnit) | — | NFR-009-02 |
| FR-009-13 (SpotBugs) | — | NFR-009-01 |
| FR-009-14 (Gradle upgrade) | — | — |
| FR-009-16 (Maven Central publish) | — | — |
| FR-009-17 (Docker Hub publish) | — | NFR-009-03 |

## Research Documents

| Document | Topic | Feeds Into |
|----------|-------|------------|
| `docs/research/pingam-authentication-api.md` | PingAM authentication API analysis | Feature 001 scenarios |
| `docs/research/gateway-candidates.md` | Gateway adapter comparison | Features 002–008 |
| `docs/research/standalone-proxy-http-server.md` | HTTP server evaluation for standalone proxy | Feature 004, ADR-0029 |
| `docs/research/pingaccess-plugin-api.md` | PingAccess Add-on SDK, RuleInterceptor SPI, Exchange model | Feature 002 spec |
| `docs/research/pingam-authentication-api.md` | PingAM /json/authenticate endpoint, callback format | Feature 002 scenarios |
| `docs/reference/pingaccess-sdk-guide.md` | Standalone PingAccess 9.0 SDK reference (19 sections, 112 classes) | Feature 002 spec, all adapter implementation |
| `docs/operations/pingaccess-operations-guide.md` | PA operations, deployment architecture, Docker/K8s layout, Admin API gotchas, rule execution order | Feature 002 spec, ADR-0023, ADR-0006 |
| `docs/operations/pinggateway-operations-guide.md` | PG operations, Docker image build/run, filesystem layout, configuration mounting, rebuild playbook | Feature 003 spec |
| `docs/operations/pingds-operations-guide.md` | PingDS operations, Docker image build (setup phase), demo/production modes, secrets, env vars, rebuild playbook | Infrastructure (identity store for E2E) |
| `docs/operations/pingam-operations-guide.md` | PingAM operations, Docker image, REST API gotchas (Host header, curl -sf, orgConfig), WebAuthn journeys (identifier-first §10 + usernameless §10a), tree import via REST, auth patterns, transformed response surface. Key insights: userHandle = base64(username) in discoverable credential flow; userVerification regex format differs between auth/reg scripts. | Infrastructure (identity provider for E2E), Feature 002 E2E |
| `docs/operations/platform-deployment-guide.md` | 3-container platform (PA + AM + PD) deployment guide, docker-compose v2, TLS, configuration scripts, WebAuthn import, message-xform plugin wiring (§9c), transform specs/profiles (am-auth-response-v2, am-webauthn-response, am-strip-internal; ADR-0036 body-predicate routing via match.when), troubleshooting | Infrastructure (all features requiring live IdP), Feature 002 integration |
| `docs/operations/e2e-karate-operations-guide.md` | Gateway-neutral E2E test architecture, Docker lifecycle, Gradle integration, Karate patterns, IDE extension patches | All E2E modules, Feature 002, future gateway features |
| `docs/research/message-xform-platform-transforms.md` | Transform pipeline architecture: URI propagation (request.setUri → wrapResponse sees rewritten path), 7-spec catalog (3 request-side URL rewrite + 4 response-side body), profile v4.0.0 routing, PA two-app architecture (/am + /api), body-predicate routing (ADR-0036) | Platform deployment, Feature 002 integration |
| `deployments/platform/e2e/` | Standalone platform E2E tests (Karate JAR, no Gradle). Tests: `auth-login.feature` (3 scenarios), `auth-logout.feature` (1 scenario), `auth-passkey.feature` (3 scenarios: full reg+auth, device-exists auth, unsupported fallback), `auth-passkey-usernameless.feature` (2 scenarios: full reg+auth with UV+residentKey, discoverable credential entry point). Helpers: `webauthn.js` (pure JDK FIDO2 ceremony — EC P-256, CBOR, SHA256withECDSA; handles both auth and reg userVerification formats), `delete-device.feature` (AM Admin API device cleanup), `basic-auth.js` (HTTP basic auth helper). Config: `karate-config.js`. Runner: `run-e2e.sh`. Key gotchas: clear cookie jar between AM-direct and PA-proxied calls; ConfirmationCallback must stay at default value (not 0); usernameless userHandle must be base64(username). | Phase 8 of platform PLAN |
