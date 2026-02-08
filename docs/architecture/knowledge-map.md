# Knowledge Map

Status: Draft | Last updated: 2026-02-08

This document maps the architectural relationships between modules, specs, ADRs,
and key concepts in message-xform. It serves as a navigational aid for agents and
contributors to understand how components connect.

## Module Structure (planned)

```
message-xform/
├── core/                          # Transform engine (zero gateway deps)
│   ├── model/                     # TransformSpec, TransformProfile, Message
│   ├── engine/                    # Expression evaluation, profile matching
│   ├── spec/                      # SpecParser — YAML to TransformSpec loading
│   ├── spi/                       # ExpressionEngine, TelemetryListener
│   ├── schema/                    # JSON Schema validation
│   └── test-vectors/              # Golden YAML fixtures (FX-001-01/02/03)
├── engine-jslt/                   # JSLT expression engine plugin
├── engine-jolt/                   # JOLT expression engine plugin (future)
├── adapter-pingaccess/            # PingAccess gateway adapter
├── adapter-pinggateway/           # PingGateway adapter (future)
├── adapter-standalone/            # Standalone HTTP proxy mode (future)
└── docs/                          # Specifications, ADRs, research
```

## Concept Map

```
TransformSpec ──────── defines ──────── Transformation Logic
  │                                        │
  ├── id + version                         ├── transform block (JSLT expr)
  ├── input/output schemas                 ├── headers block (add/remove/rename)
  ├── reverse block (bidirectional)        └── status block (status code)
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

## Research Documents

| Document | Topic | Feeds Into |
|----------|-------|------------|
| `docs/research/pingam-callback-api.md` | PingAM callback API analysis | Feature 001 scenarios |
| `docs/research/gateway-extension-models.md` | Gateway adapter comparison | Features 002–008 |
| `docs/research/t-001-12-jslt-context-variables.md` | JSLT context variable binding approach | T-001-10 (JsltExpressionEngine), ADR-0021 |
