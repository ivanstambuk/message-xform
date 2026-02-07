# Feature 001 – Message Transformation Engine

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Linked plan | `docs/architecture/features/001/plan.md` |
| Linked tasks | `docs/architecture/features/001/tasks.md` |
| Roadmap entry | #1 – Message Transformation Engine |

> Guardrail: This specification is the single normative source of truth for the feature.
> Track questions in `docs/architecture/open-questions.md`, encode resolved answers
> here, and use ADRs under `docs/decisions/` for architectural clarifications.

## Overview

message-xform is a **generic, declarative, bidirectional HTTP message transformation
engine**. It intercepts HTTP request/response pairs flowing through an API gateway (or
standalone proxy) and transforms their bodies, headers, and status codes according to
YAML-defined transformation specs.

The engine is **gateway-agnostic** — the core transformation logic has zero dependencies
on any specific gateway SDK. Thin adapters bind the engine to specific gateway extension
points (PingAccess `RuleInterceptor`, PingGateway filters, Kong plugins, etc.).

The engine is **backend-agnostic** — transformation specs describe structural mappings
between JSON schemas, not specific backend APIs. A spec that transforms PingAM callbacks
into clean fields uses the same engine as a spec that flattens a SCIM response or
reformats an OAuth token endpoint.

**Affected modules:** `core` (transformation engine), adapters (gateway-specific wrappers).

## Goals

- G-001-01 – Define a declarative YAML format for describing JSON-to-JSON structural
  transformations, including field mapping, type annotation, array reshaping, and
  conditional logic.
- G-001-02 – Support **bidirectional** transformation: a single spec can drive both the
  forward (response prettification) and reverse (request un-prettification) paths,
  or define them separately.
- G-001-03 – Operate on the full HTTP message envelope: body (JSON), headers, and
  status code — not just the body.
- G-001-04 – Remain **gateway-agnostic** in the core engine: no dependency on PingAccess,
  PingGateway, Kong, or any other gateway SDK.
- G-001-05 – Remain **backend-agnostic**: the spec language must not assume any specific
  upstream service. Backend-specific details belong in individual transform profiles,
  not in the engine.
- G-001-06 – Compose transformations as a **pipeline** of ordered operations, inspired
  by Kong's 5-phase model and JOLT's chainable specs.

## Non-Goals

- N-001-01 – This feature does NOT implement a full expression language (like JSONata).
  Complex value computation is deferred; initial scope covers structural mapping and
  conditional branching.
- N-001-02 – XML or non-JSON body transformation is out of scope for the initial version.
- N-001-03 – Gateway adapter implementation details are NOT defined here. Each adapter
  gets its own feature spec.
- N-001-04 – The engine does NOT perform protocol-level operations (TLS termination, rate
  limiting, authentication). It only transforms message content.

## Functional Requirements

### FR-001-01: Transformation Spec Format

**Requirement:** The engine MUST accept transformation specifications defined in YAML.
A spec describes a structural mapping between an **input schema** and an **output schema**,
applied to the HTTP message body. Specs MUST be loadable from the filesystem, classpath,
or inline configuration.

A transformation spec consists of:

```yaml
# Transform spec: callback-prettify
id: callback-prettify
version: "1.0.0"

match:
  content-type: "application/json"
  # Specs do NOT bind to URLs here — URL binding is a gateway adapter concern.

pipeline:
  - operation: shift
    spec:
      # Move and rename fields
      "authId": "authId"
      "stage": "stage"
      "header": "header"
      "callbacks[*]":
        "output[0].value": "fields[&1].label"
        "input[0].value": "fields[&1].value"
        "type": "fields[&1].originalType"

  - operation: default
    spec:
      # Add fields that are always present in the output
      "type": "challenge"

  - operation: derive
    spec:
      # Compute derived values
      "fields[*].type":
        from: "fields[*].originalType"
        mapping:
          "NameCallback": "text"
          "PasswordCallback": "password"
          "TextInputCallback": "text"
          "ChoiceCallback": "choice"
          "ConfirmationCallback": "choice"
          "HiddenValueCallback": "hidden"
          "*": "unknown"

  - operation: remove
    spec:
      # Clean up intermediate fields
      "fields[*].originalType": ""
```

| Aspect | Detail |
|--------|--------|
| Success path | Valid YAML spec → parsed into an internal pipeline representation |
| Validation path | Invalid YAML → fail fast with descriptive parse error including line/column |
| Failure path | Spec references unknown operation → reject at load time, not at runtime |
| Source | Inspired by JOLT (pipeline of operations), Kong (declarative YAML), JSONata (derived values) |

### FR-001-02: Pipeline Operations

**Requirement:** The engine MUST support the following core operations, applied in
pipeline order. Each operation receives the current message state and produces a new
message state.

| Operation | Description | Inspiration |
|-----------|-------------|-------------|
| `shift` | Move, rename, and restructure fields. Maps input paths to output paths. | JOLT shift |
| `default` | Add fields with default values if they are missing. | JOLT default |
| `remove` | Delete fields from the message. | JOLT remove, Kong remove |
| `derive` | Compute field values from other fields using mapping tables or simple expressions. | JSONata conditionals |
| `rename` | Rename fields without moving them in the hierarchy. | Kong rename |
| `replace` | Overwrite field values unconditionally. | Kong replace |
| `coerce` | Change the type of a field (e.g., string → number, string → boolean). | JOLT modify |
| `conditional` | Apply a sub-pipeline only if a condition matches. | Apigee conditional flows |

Operations MUST be composable: the output of one operation is the input to the next.

| Aspect | Detail |
|--------|--------|
| Success path | Pipeline of N operations executes sequentially, each transforming the message |
| Validation path | Operation with invalid spec → reject at load time |
| Failure path | Operation fails at runtime (e.g., path not found with strict mode) → configurable: skip or abort |
| Source | JOLT operation chain, Kong 5-phase pipeline |

### FR-001-03: Bidirectional Transformation

**Requirement:** The engine MUST support bidirectional transformation. Given a forward
spec (input → output), the engine MAY:
- **Auto-derive** the reverse spec for simple transformations (shift, rename)
- **Require explicit definition** of the reverse spec for complex transformations
  (derive, conditional)

A bidirectional spec bundles the forward and reverse:

```yaml
id: callback-prettify
version: "1.0.0"

forward:
  pipeline:
    - operation: shift
      spec: { ... }

reverse:
  pipeline:
    - operation: shift
      spec: { ... }  # Inverse mapping
```

When only `pipeline` is provided (no `forward`/`reverse` wrapper), the spec is
treated as unidirectional.

| Aspect | Detail |
|--------|--------|
| Success path | Forward spec applied to response, reverse spec applied to request |
| Validation path | Reverse spec is structurally valid and type-compatible with forward |
| Failure path | Auto-derive fails for complex operations → log warning, require explicit reverse |
| Source | Novel — no existing gateway transformer supports bidirectional |

### FR-001-04: Message Envelope

**Requirement:** The engine MUST operate on a generic **message envelope** that
abstracts the HTTP message, regardless of which gateway it came from:

```java
interface Message {
    byte[] getBody();
    void setBody(byte[] content);
    Map<String, List<String>> getHeaders();
    void setHeader(String name, String value);
    void removeHeader(String name);
    int getStatusCode();       // response only
    void setStatusCode(int code); // response only
    String getContentType();
}
```

Gateway adapters are responsible for mapping their native message types to this
interface:
- PingAccess: `Exchange.getRequest()` / `Exchange.getResponse()` → `Message`
- PingGateway: `Request` / `Response` → `Message`
- Standalone: `HttpServletRequest` / `HttpServletResponse` → `Message`

| Aspect | Detail |
|--------|--------|
| Success path | Adapter wraps native message → engine processes → adapter applies changes |
| Validation path | Message body is not valid JSON → skip body transformation, apply header transforms only |
| Failure path | Adapter cannot read body (e.g., streaming) → log warning, pass through |
| Source | PingAccess `Exchange`, gateway adapter pattern |

### FR-001-05: Transform Profiles (Backend-Specific Definitions)

**Requirement:** Backend-specific transformation details MUST be packaged as
**transform profiles** — self-contained YAML files that compose transform specs with
match criteria and direction. Profiles are NOT part of the core engine; they are
user-supplied configuration.

```yaml
# Profile: PingAM Callback Prettification
profile: pingam-callback-prettify
description: "Transforms PingAM callback responses into clean JSON for frontend consumption"
version: "1.0.0"

transforms:
  - spec: callback-prettify
    direction: response
    match:
      path: "/json/*/authenticate"
      method: POST
      content-type: "application/json"

  - spec: callback-prettify
    direction: request
    reverse: true    # Use the reverse pipeline
    match:
      path: "/json/*/authenticate"
      method: POST
      content-type: "application/json"
```

| Aspect | Detail |
|--------|--------|
| Success path | Profile loaded → specs resolved → bound to URL patterns |
| Validation path | Missing spec reference → fail at load time with descriptive error |
| Failure path | Duplicate profile IDs → reject, no silent override |
| Source | Kong route/plugin binding, Apigee flow attachment |

### FR-001-06: Passthrough Behavior

**Requirement:** When a message does not match any transform profile, or when the
body is not valid JSON, the engine MUST pass the message through **completely
unmodified**. No headers, body, or status code changes. This is the default behavior.

| Aspect | Detail |
|--------|--------|
| Success path | Non-matching request → forwarded unmodified |
| Validation path | N/A |
| Failure path | N/A — passthrough is always safe |
| Source | Fundamental safety principle |

### FR-001-07: Error Handling

**Requirement:** When a transformation operation fails at runtime (e.g., a required
field is missing, a type coercion fails), the engine MUST:
1. Log a structured warning with the spec ID, operation index, and error detail.
2. Depending on configuration: either **skip the failing operation** and continue
   the pipeline (lenient mode), or **abort the entire transformation** and pass
   the original message through (strict mode).
3. Never return a partially-transformed message to the client without an explicit
   configuration choice.

| Aspect | Detail |
|--------|--------|
| Success path | Operation fails → skip or abort per config → original passes through |
| Validation path | Configuration must explicitly choose lenient or strict |
| Failure path | Engine itself crashes → gateway adapter catches exception, passes through |
| Source | Defensive design — transformations must not break production traffic |

## Non-Functional Requirements

| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-001-01 | The engine MUST be stateless per-request — no server-side session, cache, or shared mutable state between requests. | Horizontal scalability; gateway deployments must not require shared state. | Unit tests confirm no mutable static state or cross-request side effects. | None. | Architecture. |
| NFR-001-02 | The core engine library MUST have zero gateway-specific dependencies. Its only dependencies are standard JSON parsing and YAML parsing. | Gateway-agnostic core enables adapter reuse across PingAccess, PingGateway, Kong, standalone. | Maven/Gradle dependency analysis confirms no gateway SDK imports in `core`. | Module structure. | `PLAN.md`. |
| NFR-001-03 | Transformation latency MUST be < 5ms for a typical message (< 50KB body, < 10 pipeline operations). | Gateway rules are in the critical path; latency is unacceptable. | Microbenchmark with JMH using representative payloads. | Core only. | Performance. |
| NFR-001-04 | Unknown/unrecognized fields in the input message MUST NOT cause transformation failure. Fields not mentioned in the spec MUST pass through unmodified (open-world assumption). | Upstream services may add fields at any time; transformation must be forward-compatible. | Test with input containing extra fields not in spec. | Core. | Robustness. |
| NFR-001-05 | Transform specs MUST be hot-reloadable without restarting the gateway or proxy. | Operational requirement for production deployments. | Integration test: modify spec file → verify next request uses new spec. | File-watching or config polling. | Operations. |
| NFR-001-06 | The engine MUST NOT log, cache, or inspect the content of fields marked as `sensitive` in the spec. | Security — passwords, tokens, and secrets must never appear in logs. | Static analysis + code review to confirm no sensitive-field logging. | Core. | Security. |

## Branch & Scenario Matrix

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-001-01 | Simple shift transformation: flat input → restructured output |
| S-001-02 | Pipeline of 3 operations: shift → default → remove |
| S-001-03 | Bidirectional transform: forward applied to response, reverse applied to request |
| S-001-04 | Non-matching request → passed through unmodified |
| S-001-05 | Invalid JSON body → passed through unmodified, header transforms still applied |
| S-001-06 | Unknown fields in input → preserved in output (open-world) |
| S-001-07 | Derive operation with mapping table → field values translated |
| S-001-08 | Conditional operation → sub-pipeline applied only when condition matches |
| S-001-09 | Strict mode: operation fails → entire transformation aborted, original passed through |
| S-001-10 | Lenient mode: operation fails → skipped, remaining pipeline continues |
| S-001-11 | Header transformation: add/remove/rename headers alongside body transforms |
| S-001-12 | Status code transformation: map upstream 200 + error body → 400 response |
| S-001-13 | Array-of-objects reshaping: nested array → flat array with derived fields |
| S-001-14 | Multiple profiles loaded: different transform specs for different URL patterns |
| S-001-15 | Spec hot-reload: spec file changes → next request uses updated spec |

## Test Strategy

- **Core (unit):** JUnit 5 tests for each pipeline operation in isolation. Parameterized
  tests with input/output JSON pairs from `docs/test-vectors/`.
- **Core (pipeline):** End-to-end pipeline tests: load spec YAML → apply to input JSON →
  verify output JSON matches expected. Test bidirectional round-trip.
- **Core (error):** Tests for lenient vs. strict mode, invalid specs, malformed JSON,
  missing fields, type coercion failures.
- **Adapter (unit):** Per-gateway adapter tests with mock message objects that verify
  the adapter correctly wraps/unwraps the native message type.
- **Standalone (integration):** HTTP-level tests with a mock upstream, verifying
  end-to-end transformation through the proxy.

## Interface & Contract Catalogue

### Domain Objects

| ID | Description | Modules |
|----|-------------|---------|
| DO-001-01 | `Message` — generic HTTP message envelope (body, headers, status) | core |
| DO-001-02 | `TransformSpec` — parsed transformation specification (id, version, pipeline) | core |
| DO-001-03 | `PipelineOperation` — single operation in the pipeline (operation type + spec) | core |
| DO-001-04 | `TransformProfile` — user-supplied binding of specs to URL/method/content-type patterns | core |
| DO-001-05 | `TransformResult` — outcome of applying a spec (success/skipped/aborted + diagnostics) | core |

### Core Engine API

| ID | Method | Description |
|----|--------|-------------|
| API-001-01 | `TransformEngine.transform(Message, Direction)` | Apply matching profile's spec to the message |
| API-001-02 | `TransformEngine.loadProfile(Path)` | Load a transform profile from YAML |
| API-001-03 | `TransformEngine.loadSpec(Path)` | Load a transform spec from YAML |
| API-001-04 | `TransformEngine.reload()` | Hot-reload all specs and profiles |

### Adapter SPI

| ID | Method | Description |
|----|--------|-------------|
| SPI-001-01 | `GatewayAdapter.wrapRequest(native) → Message` | Wrap gateway-native request as Message |
| SPI-001-02 | `GatewayAdapter.wrapResponse(native) → Message` | Wrap gateway-native response as Message |
| SPI-001-03 | `GatewayAdapter.applyChanges(Message, native)` | Write Message changes back to native object |

### Configuration

| ID | Config key | Type | Description |
|----|-----------|------|-------------|
| CFG-001-01 | `specs-dir` | path | Directory containing transform spec YAML files |
| CFG-001-02 | `profiles-dir` | path | Directory containing transform profile YAML files |
| CFG-001-03 | `error-mode` | enum | `lenient` (skip failed ops) or `strict` (abort on failure) |
| CFG-001-04 | `reload-interval` | duration | How often to check for spec/profile changes (0 = disabled) |

### Fixtures & Sample Data

| ID | Path | Purpose |
|----|------|---------|
| FX-001-01 | `docs/test-vectors/shift-simple.yaml` | Simple shift operation: flat → restructured |
| FX-001-02 | `docs/test-vectors/pipeline-3op.yaml` | 3-operation pipeline (shift + default + remove) |
| FX-001-03 | `docs/test-vectors/bidirectional-roundtrip.yaml` | Forward + reverse spec with input/output pairs |
| FX-001-04 | `docs/test-vectors/derive-mapping.yaml` | Derive operation with value mapping table |
| FX-001-05 | `docs/test-vectors/conditional.yaml` | Conditional operation with sub-pipeline |
| FX-001-06 | `docs/test-vectors/passthrough.yaml` | Non-matching input → unmodified output |

## Spec DSL

```yaml
domain_objects:
  - id: DO-001-01
    name: Message
    fields:
      - name: body
        type: byte[]
      - name: headers
        type: Map<String, List<String>>
      - name: statusCode
        type: int
        constraints: "response only"
      - name: contentType
        type: string
  - id: DO-001-02
    name: TransformSpec
    fields:
      - name: id
        type: string
      - name: version
        type: string
      - name: pipeline
        type: List<PipelineOperation>
      - name: forward
        type: Pipeline
        constraints: "optional, for bidirectional specs"
      - name: reverse
        type: Pipeline
        constraints: "optional, for bidirectional specs"
  - id: DO-001-03
    name: PipelineOperation
    fields:
      - name: operation
        type: enum[shift, default, remove, derive, rename, replace, coerce, conditional]
      - name: spec
        type: Map<String, Object>

engine_api:
  - id: API-001-01
    method: transform
    inputs: [Message, Direction]
    outputs: [TransformResult]
  - id: API-001-02
    method: loadProfile
    inputs: [Path]
  - id: API-001-03
    method: loadSpec
    inputs: [Path]

adapter_spi:
  - id: SPI-001-01
    method: wrapRequest
    inputs: [native]
    outputs: [Message]
  - id: SPI-001-02
    method: wrapResponse
    inputs: [native]
    outputs: [Message]
```

## Appendix

### A. Relationship to Existing Transformers

This engine fills a specific gap in the API gateway ecosystem:

| Capability | Kong Transformer | AWS VTL | JOLT | **message-xform** |
|-----------|-----------------|---------|------|-------------------|
| Structural reshaping | ❌ | ✅ | ✅ | ✅ |
| Bidirectional | ❌ | ❌ | ❌ | **✅** |
| Gateway-agnostic spec | ❌ | ❌ | ✅ | **✅** |
| URL/path binding | ✅ | ✅ | ❌ | **✅** |
| Header + body + status | ✅ | ✅ | ❌ | **✅** |
| Declarative YAML config | ✅ | ❌ | ❌ | **✅** |
| Conditional logic | ❌ | ✅ | ❌ | **✅** |
| Hot-reloadable | ✅ | ❌ | ❌ | **✅** |

See `docs/research/transformation-patterns.md` for the full analysis.

### B. Example: PingAM Callback Prettification (as a transform profile)

This demonstrates how a backend-specific use case is implemented as a **profile**
using the generic engine — the engine itself knows nothing about PingAM:

```yaml
# file: profiles/pingam-callback-prettify.yaml
profile: pingam-callback-prettify
description: >
  Transforms authentication callback responses into
  clean JSON for frontend consumption.
version: "1.0.0"

transforms:
  - spec: callback-prettify    # references specs/callback-prettify.yaml
    direction: response
    match:
      path: "/json/*/authenticate"
      method: POST
  - spec: callback-prettify
    direction: request
    reverse: true
    match:
      path: "/json/*/authenticate"
      method: POST
```

### C. Research References

- Transformation Patterns: `docs/research/transformation-patterns.md`
- PingAccess Plugin API: `docs/research/pingaccess-plugin-api.md`
- PingAM Authentication API: `docs/research/pingam-authentication-api.md`
- Gateway Candidates: `docs/research/gateway-candidates.md`
