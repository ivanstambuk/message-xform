# Feature 001 – Message Transformation Engine

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Linked plan | `docs/architecture/features/001/plan.md` |
| Linked tasks | `docs/architecture/features/001/tasks.md` |
| Linked scenarios | `docs/architecture/features/001/scenarios.md` |
| Roadmap entry | #1 – Message Transformation Engine |

> Guardrail: This specification is the single normative source of truth for the feature.
> Track questions in `docs/architecture/open-questions.md`, encode resolved answers
> here, and use ADRs under `docs/decisions/` for architectural clarifications.

## Overview

message-xform is a **generic, declarative, bidirectional HTTP message transformation
engine**. It intercepts HTTP request/response pairs flowing through an API gateway (or
standalone proxy) and transforms their bodies, headers, and status codes according to
YAML-defined transformation specs.

Transformations are expressed using **JSLT** (Schibsted), a template-style JSON query
and transformation language. JSLT was chosen for its readability (the spec looks like
the desired output), native Jackson integration (`JsonNode` in/out), and production
maturity (9 billion transforms/day at Schibsted since 2018). The engine architecture
is **pluggable**: alternative expression engines (JOLT, jq, JSONata) can be registered
via an Expression Engine SPI, following the pattern established by JourneyForge ADR-0027.

The engine is **gateway-agnostic** — the core transformation logic has zero dependencies
on any specific gateway SDK. Thin adapters bind the engine to specific gateway extension
points (PingAccess `RuleInterceptor`, PingGateway filters, Kong plugins, etc.).

The engine is **backend-agnostic** — transformation specs describe structural mappings
between JSON schemas, not specific backend APIs. A spec that transforms PingAM callbacks
into clean fields uses the same engine as a spec that flattens a SCIM response or
reformats an OAuth token endpoint.

**Affected modules:** `core` (transformation engine + JSLT), adapters (gateway-specific
wrappers).

## Goals

- G-001-01 – Define a declarative YAML format for describing JSON-to-JSON structural
  transformations using JSLT expressions (field mapping, conditional logic, array
  reshaping, defaults, value computation).
- G-001-02 – Support **bidirectional** transformation: a single spec can define both the
  forward (response prettification) and reverse (request un-prettification) transforms,
  or define them separately.
- G-001-03 – Operate on the full HTTP message envelope: body (JSON), headers, and
  status code — not just the body.
- G-001-04 – Remain **gateway-agnostic** in the core engine: no dependency on PingAccess,
  PingGateway, Kong, or any other gateway SDK.
- G-001-05 – Remain **backend-agnostic**: the spec language must not assume any specific
  upstream service. Backend-specific details belong in individual transform profiles,
  not in the engine.
- G-001-06 – Support a **pluggable expression engine** model: JSLT as the primary default,
  with an SPI that allows registering alternative engines (JOLT, jq, JSONata) for users
  who have existing specs or prefer a different syntax.

## Non-Goals

- N-001-01 – XML or non-JSON body transformation is out of scope for the initial version.
- N-001-02 – Gateway adapter implementation details are NOT defined here. Each adapter
  gets its own feature spec.
- N-001-03 – The engine does NOT perform protocol-level operations (TLS termination, rate
  limiting, authentication). It only transforms message content.
- N-001-04 – Implementing alternative expression engines (JOLT, jq, JSONata) is not part
  of this feature. The SPI is defined here; engine adapters are tracked separately.

## Functional Requirements

### FR-001-01: Transformation Spec Format

**Requirement:** The engine MUST accept transformation specifications defined in YAML.
A spec contains a JSLT expression (or an expression in another registered engine) that
describes the desired output shape. Specs MUST be loadable from the filesystem,
classpath, or inline configuration.

A transformation spec consists of:

```yaml
# Transform spec: callback-prettify
id: callback-prettify
version: "1.0.0"

match:
  content-type: "application/json"
  # Specs do NOT bind to URLs here — URL binding is a profile concern.

transform:
  lang: jslt                    # Expression engine selector (default: jslt)
  expr: |
    {
      "type": if (.callbacks) "challenge" else "simple",
      "authId": .authId,
      "stage": .stage,
      "header": .header,
      "fields": [for (.callbacks)
        let cb = .
        {
          "label": .output[0].value,
          "value": .input[0].value,
          "type":
            if   (.type == "NameCallback") "text"
            else if (.type == "PasswordCallback") "password"
            else if (.type == "TextInputCallback") "text"
            else if (.type == "ChoiceCallback") "choice"
            else if (.type == "ConfirmationCallback") "choice"
            else if (.type == "HiddenValueCallback") "hidden"
            else "unknown",
          "sensitive": .type == "PasswordCallback"
        }
      ]
    }
```

The JSLT expression above is **template-style**: it reads like the desired output JSON,
with embedded expressions (`.authId`, `.callbacks`, `if/else`) for dynamic values. This
is fundamentally different from the JOLT approach of specifying structural operations
(shift, default, remove) — instead, you declare what you want and the engine produces it.

| Aspect | Detail |
|--------|--------|
| Success path | Valid YAML spec → JSLT expression compiled → immutable `Expression` object cached |
| Validation path | Invalid YAML → fail fast with descriptive parse error including line/column |
| Failure path | JSLT syntax error → reject at load time, not at runtime |
| Source | JSLT (Schibsted), JourneyForge transform state pattern |

### FR-001-02: Expression Engine SPI (Pluggable Engines)

**Requirement:** The engine MUST support a pluggable expression engine model. Each
expression engine is identified by a string id (e.g., `jslt`, `jolt`, `jq`, `jsonata`)
and implements a common Java interface.

```java
/**
 * Expression engine plugin — evaluates a transform expression against a JSON input.
 * Implementations MUST be pure (no I/O), thread-safe, and respect engine limits.
 * Adapted from JourneyForge ADR-0027.
 */
public interface ExpressionEngine {
    /** Engine identifier, e.g. "jslt", "jolt", "jq". */
    String id();

    /**
     * Compile an expression string into a reusable, thread-safe handle.
     * Called once at spec load time.
     */
    CompiledExpression compile(String expr) throws ExpressionCompileException;
}

public interface CompiledExpression {
    /**
     * Evaluate the compiled expression against the input JSON.
     * Called per-request on the gateway hot path — MUST be fast and thread-safe.
     */
    JsonNode evaluate(JsonNode input) throws ExpressionEvalException;
}
```

Engine configuration:

```yaml
# Engine limits (in application config, not per-spec)
engines:
  defaults:
    max-eval-ms: 50             # Per-evaluation wall-clock budget
    max-output-bytes: 1048576   # 1MB max output size
  jslt:
    enabled: true               # JSLT is always enabled as the default
  jolt:
    enabled: false              # Opt-in via configuration
  jq:
    enabled: false
```

| Aspect | Detail |
|--------|--------|
| Success path | Spec declares `lang: jslt` → engine registry resolves → expression compiled |
| Validation path | Unknown engine id → reject spec at load time with clear message |
| Failure path | Engine evaluation exceeds time/size budget → abort, pass original through |
| Source | JourneyForge ADR-0027 (Expression Engines and `lang` Extensibility) |

### FR-001-03: Bidirectional Transformation

**Requirement:** The engine MUST support bidirectional transformation. A spec can define
separate forward and reverse JSLT expressions:

```yaml
id: callback-prettify
version: "1.0.0"

forward:
  lang: jslt
  expr: |
    {
      "type": if (.callbacks) "challenge" else "simple",
      "authId": .authId,
      "fields": [for (.callbacks)
        { "label": .output[0].value, "type": "text" }
      ]
    }

reverse:
  lang: jslt
  expr: |
    {
      "authId": .authId,
      "callbacks": [for (.fields)
        {
          "type": "NameCallback",
          "output": [{ "name": "prompt", "value": .label }],
          "input": [{ "name": "IDToken1", "value": .value }]
        }
      ]
    }
```

When only `transform` is provided (no `forward`/`reverse` wrapper), the spec is
treated as unidirectional.

| Aspect | Detail |
|--------|--------|
| Success path | Forward transform applied to response, reverse transform applied to request |
| Validation path | Both forward and reverse compile successfully at load time |
| Failure path | Reverse expression errors at runtime → abort, pass original through |
| Source | Novel — no existing gateway transformer supports bidirectional |

### FR-001-04: Message Envelope

**Requirement:** The engine MUST operate on a generic **message envelope** that
abstracts the HTTP message, regardless of which gateway it came from:

```java
public interface Message {
    JsonNode getBodyAsJson();
    void setBodyFromJson(JsonNode body);
    Map<String, List<String>> getHeaders();
    void setHeader(String name, String value);
    void removeHeader(String name);
    int getStatusCode();          // response only
    void setStatusCode(int code); // response only
    String getContentType();
}
```

Note: the body interface uses `JsonNode` directly (not `byte[]`) because the engine
already requires JSON — and JSLT operates natively on Jackson `JsonNode`. The raw
byte[] ↔ JsonNode conversion is the adapter's responsibility.

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
    reverse: true    # Use the reverse transform
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

**Requirement:** When a transformation fails at runtime (e.g., JSLT evaluation error,
missing field, output size exceeded), the engine MUST:
1. Log a structured warning with the spec ID, engine id, and error detail.
2. Depending on configuration: either **abort the transformation** and pass the
   original message through (strict mode, default), or **use partial output** where
   possible (lenient mode).
3. Never return an empty or corrupted message to the client.

| Aspect | Detail |
|--------|--------|
| Success path | Transform fails → abort per config → original passes through |
| Validation path | Configuration must explicitly choose lenient or strict |
| Failure path | Engine itself crashes → gateway adapter catches exception, passes through |
| Source | Defensive design — transformations must not break production traffic |

### FR-001-08: Reusable Mappers

**Requirement:** Transform specs MAY define reusable named expressions under a
`mappers` block, referenced inline via `mapperRef`. This avoids duplicating common
mapping logic.

```yaml
id: common-transforms
version: "1.0.0"

mappers:
  strip-internal:
    lang: jslt
    expr: |
      { * : . }
      - "_internal"
      - "_debug"

  add-gateway-metadata:
    lang: jslt
    expr: |
      . + {
        "_meta": {
          "transformedBy": "message-xform",
          "specVersion": "1.0.0"
        }
      }
```

| Aspect | Detail |
|--------|--------|
| Success path | `mapperRef: strip-internal` resolves to compiled expression |
| Validation path | Missing mapper id → reject at load time |
| Failure path | Circular mapperRef → reject at load time |
| Source | JourneyForge `spec.mappers` + `mapperRef` pattern |

## Non-Functional Requirements

| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-001-01 | The engine MUST be stateless per-request — no server-side session, cache, or shared mutable state between requests. JSLT `Expression` objects are immutable and shared across threads. | Horizontal scalability; gateway deployments must not require shared state. | Unit tests confirm no mutable static state or cross-request side effects. | None. | Architecture. |
| NFR-001-02 | The core engine library MUST have zero gateway-specific dependencies. Allowed dependencies: Jackson (JSON), SnakeYAML (YAML parsing), and JSLT (transformation). | Gateway-agnostic core enables adapter reuse across PingAccess, PingGateway, Kong, standalone. | Maven/Gradle dependency analysis confirms no gateway SDK imports in `core`. | Module structure. | `PLAN.md`. |
| NFR-001-03 | Transformation latency MUST be < 5ms for a typical message (<50KB body). Compiled JSLT expressions are evaluated per-request; compilation happens once at spec load time. | Gateway rules are in the critical path; latency is unacceptable. | Microbenchmark with JMH using representative payloads. | Core only. | Performance. |
| NFR-001-04 | Unknown/unrecognized fields in the input message MUST NOT cause transformation failure. JSLT supports `* : .` (object matching) to pass through unmentioned fields. | Upstream services may add fields at any time; transformation must be forward-compatible. | Test with input containing extra fields not in spec. | Core. | Robustness. |
| NFR-001-05 | Transform specs MUST be hot-reloadable without restarting the gateway or proxy. On reload, JSLT expressions are recompiled and atomically swapped. | Operational requirement for production deployments. | Integration test: modify spec file → verify next request uses new spec. | File-watching or config polling. | Operations. |
| NFR-001-06 | The engine MUST NOT log, cache, or inspect the content of fields marked as `sensitive` in the spec. | Security — passwords, tokens, and secrets must never appear in logs. | Static analysis + code review to confirm no sensitive-field logging. | Core. | Security. |
| NFR-001-07 | Expression evaluation MUST be bounded by configurable time (`max-eval-ms`) and output size (`max-output-bytes`) limits. Exceeding either budget MUST abort the evaluation. | Prevents runaway expressions from blocking gateway threads. | Test with deliberately slow/large expressions. | Core. | Safety. |

## Branch & Scenario Matrix

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-001-01 | JSLT transform: flat input → restructured output (rename, nest, derive) |
| S-001-02 | Bidirectional transform: forward applied to response, reverse applied to request |
| S-001-03 | Non-matching request → passed through unmodified |
| S-001-04 | Invalid JSON body → passed through unmodified, header transforms still applied |
| S-001-05 | Unknown fields in input → preserved in output via `* : .` (open-world) |
| S-001-06 | Conditional logic in JSLT: `if/else` produces different output shapes |
| S-001-07 | Array reshaping: `[for ...]` maps nested arrays to flat arrays with derived fields |
| S-001-08 | Strict mode: JSLT evaluation error → transformation aborted, original passed through |
| S-001-09 | Lenient mode: JSLT evaluation error → partial result used where safe |
| S-001-10 | Header transformation: add/remove/rename headers alongside body transforms |
| S-001-11 | Status code transformation: map upstream 200 + error body → 400 response |
| S-001-12 | Multiple profiles loaded: different transform specs for different URL patterns |
| S-001-13 | Spec hot-reload: spec file changes → JSLT recompiled, next request uses new spec |
| S-001-14 | Alternative engine: spec declares `lang: jolt` → JOLT engine handles transform |
| S-001-15 | Engine time budget exceeded → evaluation aborted, original passed through |
| S-001-16 | Reusable mapper: `mapperRef: strip-internal` resolves and applies correctly |

## Test Strategy

- **Core (unit):** JUnit 5 tests for JSLT expression evaluation in isolation.
  Parameterized tests with input/output JSON pairs from `docs/test-vectors/`.
- **Core (spec loading):** Tests for YAML spec parsing, JSLT compilation, engine
  resolution, and validation error reporting.
- **Core (full pipeline):** End-to-end tests: load spec YAML → apply to input JSON →
  verify output JSON matches expected. Test bidirectional round-trip.
- **Core (error):** Tests for lenient vs. strict mode, invalid JSLT expressions,
  malformed JSON input, evaluation timeout, and output size limits.
- **Core (SPI):** Tests for engine registration, unknown engine rejection, and
  engine configuration limits.
- **Adapter (unit):** Per-gateway adapter tests with mock message objects that verify
  the adapter correctly wraps/unwraps the native message type.
- **Standalone (integration):** HTTP-level tests with a mock upstream, verifying
  end-to-end transformation through the proxy.

## Interface & Contract Catalogue

### Domain Objects

| ID | Description | Modules |
|----|-------------|---------|
| DO-001-01 | `Message` — generic HTTP message envelope (JsonNode body, headers, status) | core |
| DO-001-02 | `TransformSpec` — parsed spec (id, version, compiled JSLT expression, engine id) | core |
| DO-001-03 | `CompiledExpression` — immutable, thread-safe compiled expression handle | core |
| DO-001-04 | `TransformProfile` — user-supplied binding of specs to URL/method/content-type patterns | core |
| DO-001-05 | `TransformResult` — outcome of applying a spec (success/aborted + diagnostics) | core |
| DO-001-06 | `ExpressionEngine` — pluggable engine SPI (compile + evaluate) | core |

### Core Engine API

| ID | Method | Description |
|----|--------|-------------|
| API-001-01 | `TransformEngine.transform(Message, Direction)` | Apply matching profile's spec to the message |
| API-001-02 | `TransformEngine.loadProfile(Path)` | Load a transform profile from YAML |
| API-001-03 | `TransformEngine.loadSpec(Path)` | Load and compile a transform spec from YAML |
| API-001-04 | `TransformEngine.reload()` | Hot-reload: re-parse specs, recompile expressions, atomic swap |
| API-001-05 | `TransformEngine.registerEngine(ExpressionEngine)` | Register a pluggable expression engine |

### Expression Engine SPI

| ID | Method | Description |
|----|--------|-------------|
| SPI-001-01 | `ExpressionEngine.id()` | Return engine identifier (e.g., `"jslt"`) |
| SPI-001-02 | `ExpressionEngine.compile(String expr)` | Compile expression string → `CompiledExpression` |
| SPI-001-03 | `CompiledExpression.evaluate(JsonNode input)` | Evaluate expression against input → output `JsonNode` |

### Gateway Adapter SPI

| ID | Method | Description |
|----|--------|-------------|
| SPI-001-04 | `GatewayAdapter.wrapRequest(native) → Message` | Wrap gateway-native request as Message |
| SPI-001-05 | `GatewayAdapter.wrapResponse(native) → Message` | Wrap gateway-native response as Message |
| SPI-001-06 | `GatewayAdapter.applyChanges(Message, native)` | Write Message changes back to native object |

### Configuration

| ID | Config key | Type | Description |
|----|-----------|------|-------------|
| CFG-001-01 | `specs-dir` | path | Directory containing transform spec YAML files |
| CFG-001-02 | `profiles-dir` | path | Directory containing transform profile YAML files |
| CFG-001-03 | `error-mode` | enum | `strict` (abort on failure, default) or `lenient` |
| CFG-001-04 | `reload-interval` | duration | How often to check for spec/profile changes (0 = disabled) |
| CFG-001-05 | `engines.defaults.max-eval-ms` | int | Per-expression evaluation time budget (default: 50ms) |
| CFG-001-06 | `engines.defaults.max-output-bytes` | int | Max output size per evaluation (default: 1MB) |
| CFG-001-07 | `engines.<id>.enabled` | boolean | Enable/disable a specific expression engine |

### Fixtures & Sample Data

| ID | Path | Purpose |
|----|------|---------|
| FX-001-01 | `docs/test-vectors/jslt-simple-rename.yaml` | Simple JSLT: rename and restructure fields |
| FX-001-02 | `docs/test-vectors/jslt-conditional.yaml` | JSLT with `if/else` logic |
| FX-001-03 | `docs/test-vectors/jslt-array-reshape.yaml` | JSLT with `[for ...]` array transformation |
| FX-001-04 | `docs/test-vectors/bidirectional-roundtrip.yaml` | Forward + reverse JSLT with input/output pairs |
| FX-001-05 | `docs/test-vectors/passthrough.yaml` | Non-matching input → unmodified output |
| FX-001-06 | `docs/test-vectors/open-world.yaml` | Input with extra fields → preserved via `* : .` |
| FX-001-07 | `docs/test-vectors/error-strict-mode.yaml` | JSLT error → transformation aborted |

## Spec DSL

```yaml
domain_objects:
  - id: DO-001-01
    name: Message
    fields:
      - name: body
        type: JsonNode
        note: "Parsed JSON body (Jackson)"
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
      - name: lang
        type: string
        constraints: "default: jslt"
      - name: compiledExpr
        type: CompiledExpression
        note: "Immutable, thread-safe — compiled at load time"
      - name: forward
        type: CompiledExpression
        constraints: "optional, for bidirectional specs"
      - name: reverse
        type: CompiledExpression
        constraints: "optional, for bidirectional specs"
  - id: DO-001-03
    name: ExpressionEngine
    fields:
      - name: id
        type: string
        note: "e.g. jslt, jolt, jq, jsonata"
      - name: compile
        type: "(String) → CompiledExpression"
  - id: DO-001-04
    name: CompiledExpression
    fields:
      - name: evaluate
        type: "(JsonNode) → JsonNode"
        note: "Thread-safe, called per-request"

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
  - id: API-001-05
    method: registerEngine
    inputs: [ExpressionEngine]

expression_engine_spi:
  - id: SPI-001-01
    method: id
    outputs: [String]
  - id: SPI-001-02
    method: compile
    inputs: [String]
    outputs: [CompiledExpression]
  - id: SPI-001-03
    method: evaluate
    inputs: [JsonNode]
    outputs: [JsonNode]

adapter_spi:
  - id: SPI-001-04
    method: wrapRequest
    inputs: [native]
    outputs: [Message]
  - id: SPI-001-05
    method: wrapResponse
    inputs: [native]
    outputs: [Message]
```

## Appendix

### A. Why JSLT?

message-xform evaluated six JSON transformation engines. JSLT won on every criterion
that matters for gateway-inline transformation:

| Criterion | JSLT | JOLT | jq | JSONata | JMESPath |
|-----------|------|------|----|---------|----------|
| Readability | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| Jackson-native | ✅ | ✅ | ✅ (`jackson-jq`) | ❌ | ❌ |
| Conditional logic | ✅ `if/else` | ❌ | ✅ | ✅ | ❌ |
| Structural reshaping | ✅ | ✅ | ✅ | ✅ | ❌ |
| Passthrough (`* : .`) | ✅ | ❌ | manual | ❌ | ❌ |
| Production maturity | 9B/day (Schibsted) | NiFi/Spring | preview | IBM z/OS | AWS CLI |
| Dependencies | Jackson only | Jackson | Jackson | own model | Jackson |

The template-style syntax is the key differentiator: a JSLT spec looks like what the
output JSON should look like, with expressions where values need to be computed.

See `docs/research/expression-engine-evaluation.md` for the full comparison.

### B. Relationship to Existing Transformers

This engine fills a specific gap in the API gateway ecosystem:

| Capability | Kong Transformer | AWS VTL | JOLT | **message-xform** |
|-----------|-----------------|---------|------|-------------------|
| Structural reshaping | ❌ | ✅ | ✅ | ✅ |
| Readable spec syntax | ✅ | ❌ | ❌ | **✅ (JSLT)** |
| Bidirectional | ❌ | ❌ | ❌ | **✅** |
| Gateway-agnostic spec | ❌ | ❌ | ✅ | **✅** |
| URL/path binding | ✅ | ✅ | ❌ | **✅** |
| Header + body + status | ✅ | ✅ | ❌ | **✅** |
| Conditional logic | ❌ | ✅ | ❌ | **✅** |
| Pluggable engines | ❌ | ❌ | ❌ | **✅** |
| Hot-reloadable | ✅ | ❌ | ❌ | **✅** |

See `docs/research/transformation-patterns.md` for the full analysis.

### C. Example: PingAM Callback Prettification (as a transform profile)

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

The referenced `callback-prettify` spec uses JSLT:

```yaml
# file: specs/callback-prettify.yaml
id: callback-prettify
version: "1.0.0"

forward:
  lang: jslt
  expr: |
    {
      "type": if (.callbacks) "challenge" else "simple",
      "authId": .authId,
      "stage": .stage,
      "fields": [for (.callbacks)
        {
          "label": .output[0].value,
          "value": .input[0].value,
          "type":
            if   (.type == "NameCallback") "text"
            else if (.type == "PasswordCallback") "password"
            else if (.type == "ChoiceCallback") "choice"
            else "unknown",
          "sensitive": .type == "PasswordCallback"
        }
      ]
    }

reverse:
  lang: jslt
  expr: |
    {
      "authId": .authId,
      "callbacks": [for (.fields)
        {
          "type": "NameCallback",
          "output": [{ "name": "prompt", "value": .label }],
          "input": [{ "name": "IDToken1", "value": .value }]
        }
      ]
    }
```

### D. Research References

- Expression Engine Evaluation: `docs/research/expression-engine-evaluation.md`
- JourneyForge DSL Patterns: `docs/research/journeyforge-dsl-patterns.md`
- Transformation Patterns: `docs/research/transformation-patterns.md`
- PingAccess Plugin API: `docs/research/pingaccess-plugin-api.md`
- PingAM Authentication API: `docs/research/pingam-authentication-api.md`
- Gateway Candidates: `docs/research/gateway-candidates.md`
