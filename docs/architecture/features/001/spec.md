# Feature 001 – Message Transformation Engine

| Field | Value |
|-------|-------|
| Status | Ready |
| Last updated | 2026-02-13 |
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
via an Expression Engine SPI (ADR-0010).

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

- N-001-01 – The engine operates **exclusively on JSON bodies** (`application/json` and
  `application/*+json`). XML, `application/x-www-form-urlencoded`, `multipart/form-data`,
  and all other non-JSON content types are permanent non-goals. Non-JSON bodies pass
  through unmodified (FR-001-06).
- N-001-02 – Gateway adapter implementation details are NOT defined here. Each adapter
  gets its own feature spec.
- N-001-03 – The engine does NOT perform protocol-level operations (TLS termination, rate
  limiting, authentication). It only transforms message content.
- N-001-04 – Implementing alternative expression engines (JOLT, jq, JSONata) is not part
  of this feature. The SPI is defined here; engine adapters are tracked separately.

> **Type notation:** Interface and type definitions in this specification use
> TypeScript-style declarations (`.d.ts`) as a **language-neutral notation**.
> The implementation language is Java, but specs are intentionally notation-agnostic.
> `JsonTree` represents a parsed JSON tree (Jackson `JsonNode` in the Java
> implementation). `T | null` denotes nullable types. `namespace` groups
> factory/static methods.

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

input:
  schema:
    type: object
    required: [callbacks, authId]
    properties:
      callbacks: { type: array, items: { type: object } }
      authId: { type: string }
      stage: { type: string }

output:
  schema:
    type: object
    required: [fields, type]
    properties:
      type: { type: string, enum: [challenge, simple] }
      authId: { type: string }
      fields: { type: array, items: { type: object } }

# Sensitive fields — JSON path expressions for fields that MUST be redacted
# from logs, caches, and telemetry (NFR-001-06, ADR-0019).
sensitive:
  - "$.authId"
  - "$.callbacks[*].input[*].value"

# Prerequisite filter — declares what this spec can process (ADR-0015).
# This is a capability declaration, NOT routing. URL/path/method binding is a
# profile concern (FR-001-05). When absent, the spec accepts any content-type.
match:
  content-type: "application/json"

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

**Single expression per direction (ADR-0008):** A transform spec defines exactly **one**
expression per direction — a single `transform.expr` (unidirectional) or `forward.expr` /
`reverse.expr` (bidirectional). The spec format does NOT support pipeline/chaining within
a single spec. Multi-step logic is expressed using each engine's native staged-computation
features (JSLT `let` bindings, jq pipe `|`, JSONata `~>`, DataWeave `var`). When
mixed-engine composition is needed (e.g., JOLT structural shift → JSLT conditional
enrichment), compose at the **profile level** by binding multiple specs to the same route
in sequence.

| Aspect | Detail |
|--------|--------|
| Success path | Valid YAML spec → schemas parsed → JSLT expression compiled → immutable `Expression` object cached |
| Validation path | Invalid YAML → fail fast with descriptive parse error including line/column |
| Failure path | JSLT syntax error → reject at load time, not at evaluation time |
| Source | JSLT (Schibsted), ADR-0008 |

### FR-001-02: Expression Engine SPI (Pluggable Engines)

**Requirement:** The engine MUST support a pluggable expression engine model. Each
expression engine is identified by a string id (e.g., `jslt`, `jolt`, `jq`, `jsonata`)
and implements a common interface.

```typescript
/**
 * Expression engine plugin — evaluates a transform expression against a JSON input.
 * Implementations MUST be pure (no I/O), thread-safe, and respect engine limits.
 * See ADR-0010 (Pluggable Expression Engine SPI).
 */
interface ExpressionEngine {
  /** Engine identifier, e.g. "jslt", "jolt", "jq". */
  readonly id: string;

  /**
   * Compile an expression string into a reusable, thread-safe handle.
   * Called once at spec load time.
   * @throws ExpressionCompileException (subtype of TransformLoadException)
   */
  compile(expr: string): CompiledExpression;
}

interface CompiledExpression {
  /**
   * Evaluate the compiled expression against the input JSON.
   * Called per-request on the gateway hot path — MUST be fast and thread-safe.
   *
   * @param input   the JSON message body
   * @param context read-only transform context (headers, status, request metadata)
   * @throws ExpressionEvalException (subtype of TransformEvalException)
   */
  evaluate(input: JsonTree, context: TransformContext): JsonTree;
}

/**
 * Read-only context passed to expression engines during evaluation.
 * Contains port value objects (ADR-0033) — no third-party types.
 * Engines consume whichever context they support (e.g., JSLT converts
 * HttpHeaders to JsonNode internally for $headers / $headers_all binding;
 * JOLT ignores context entirely).
 */
interface TransformContext {
  /** HTTP headers — port type providing single-value and multi-value views (ADR-0026). */
  readonly headers: HttpHeaders;
  /** HTTP status code, or null for request transforms (ADR-0017, ADR-0020). */
  readonly status: number | null;
  /** Query params (keys = param names, values = first value). ADR-0021. */
  readonly queryParams: Map<string, string>;
  /** Request cookies (keys = cookie names, values = cookie values). ADR-0021. */
  readonly cookies: Map<string, string>;
  /** Gateway session context, or SessionContext.empty() if unavailable. ADR-0030. */
  readonly session: SessionContext;
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

Engine support matrix — the engine MUST validate at load time that a spec does not
use capabilities the declared engine does not support:

| Engine    | Body Transform | Predicates (`when`) | `$headers` / `$headers_all` / `$status` | `$queryParams` / `$cookies` | `$session` | Bidirectional | Status |
|-----------|:-:|:-:|:-:|:-:|:-:|:-:|-----------|
| `jslt`    | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Baseline — always available |
| `jolt`    | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | Structural shift/default/remove only |
| `jq`      | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Future — via adapter |
| `jsonata` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Future — via adapter |
| `dataweave` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Future — being open-sourced by MuleSoft (BSD-3), via adapter |

If a spec declares `lang: jolt` with a `when` predicate or `$headers` / `$headers_all` reference,
the engine MUST reject the spec at load time with a diagnostic message (e.g.,
"engine 'jolt' does not support predicates — use 'jslt' or 'jq'").

| Aspect | Detail |
|--------|--------|
| Success path | Spec declares `lang: jslt` → engine registry resolves → expression compiled |
| Validation path | Unknown engine id → reject spec at load time with clear message |
| Validation path | Spec uses capability engine doesn't support → reject at load time |
| Failure path | Engine evaluation exceeds time/size budget → abort, return error response (ADR-0022) |
| Source | ADR-0004, ADR-0009, ADR-0010 |

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
**direction-agnostic** — it is a pure input→output function (ADR-0016). The profile's
`direction` field (which is **always required**) tells the engine which HTTP message
phase to apply the expression during:
- `direction: response` → expression runs on the response body (from backend).
- `direction: request` → expression runs on the request body (from client).
The same unidirectional spec MAY be bound to both directions in different profile
entries.

| Aspect | Detail |
|--------|--------|
| Success path | Forward transform applied to response, reverse transform applied to request |
| Validation path | Both forward and reverse compile successfully at load time |
| Validation path | Profile entry without `direction` → reject at load time |
| Failure path | Reverse expression errors at evaluation time → abort, return error response (ADR-0022) |
| Source | Novel — no existing gateway transformer supports bidirectional, ADR-0016 |

### FR-001-04: Message Envelope

**Requirement:** The engine MUST operate on a generic **message envelope** that
abstracts the HTTP message, regardless of which gateway it came from:

```typescript
/** Immutable message envelope — abstracts an HTTP message regardless of gateway origin. */
type Message = {
  readonly body: MessageBody;
  readonly headers: HttpHeaders;
  readonly statusCode: number | null;     // null for requests (ADR-0020)
  readonly requestPath: string;
  readonly requestMethod: string;
  readonly queryString: string | null;
  readonly session: SessionContext | null;

  /** Convenience: media type from body. */
  mediaType(): MediaType;
  /** Convenience: content type string from body (e.g. "application/json"). */
  contentType(): string | null;

  // Copy constructors — return a new Message with one field replaced
  withBody(newBody: MessageBody): Message;
  withHeaders(newHeaders: HttpHeaders): Message;
  withStatusCode(newStatusCode: number | null): Message;
};
```

Note: the body field uses `MessageBody` (a `byte[]` + `MediaType` pair), NOT
`JsonNode`. The engine internally parses bytes to `JsonNode` using its own
bundled, relocated Jackson — adapters never touch Jackson types. This is the
Anti-Corruption Layer pattern defined in ADR-0032 and ADR-0033. The raw
byte[] ↔ JsonNode conversion is the **core engine's internal** responsibility.

The `Message` record replaces two separate header maps (single-value
`Map<String, String>` and multi-value `Map<String, List<String>>`) with a
single `HttpHeaders` object that provides both views via `toSingleValueMap()`
and `toMultiValueMap()`. The `contentType` field is removed — it is derived
from `body.mediaType().value()`. The `sessionContext` field is renamed to
`session` and uses `SessionContext` instead of `JsonNode`.

Gateway adapters are responsible for constructing `Message` instances using
the port type factory methods:
- PingAccess: `Exchange.getRequest()` / `Exchange.getResponse()` → `MessageBody.json(bytes)`, `HttpHeaders.of(map)`, `SessionContext.of(map)`
- PingGateway: `Request` / `Response` → same factory pattern
- Standalone: `HttpServletRequest` / `HttpServletResponse` → same factory pattern

**Copy-on-wrap semantics:** Adapters MUST create a **mutable copy** of the
gateway-native message when wrapping (snapshot body bytes, copy headers,
snapshot of status code). The core engine receives the `Message` as an immutable
record and produces a new `Message` (via `with*()` methods) during transformation.
After transformation completes successfully (including all chain steps), the
adapter reads the result `Message` fields and applies them back to the native
message.

On abort-on-failure, the adapter **does not apply changes** — the native
message remains completely untouched. This provides clean rollback semantics for
pipeline chaining (FR-001-05) and ensures no partial mutations leak to other
gateway filters.

```
wrapResponse(native) → Message (copy)
  ↓
Engine processes Message → new Message (immutable result)
  ↓
if success: adapter reads result Message fields → writes back to native
if failure: discard result Message → native untouched
```

| Aspect | Detail |
|--------|--------|
| Success path | Adapter creates Message(MessageBody, HttpHeaders, ...) → engine processes → adapter reads result Message |
| Validation path | Message body is not valid JSON → skip body transformation, apply header transforms only |
| Failure path | Adapter cannot read body (e.g., streaming) → log warning, return error response (ADR-0022) |
| Failure path | Transform fails → result discarded, adapter returns error response (ADR-0022) |
| Source | PingAccess `Exchange`, gateway adapter pattern, ADR-0011, ADR-0032, ADR-0033 |

**Body buffering (ADR-0018):** The core engine does NOT mandate body-size limits or
buffering strategies. The `MessageBody` contract assumes complete body bytes
are provided. How the adapter buffers the body, and any size limits, are gateway-level
NFRs handled by the adapter's gateway configuration (e.g., PingAccess request body limits,
Kong/NGINX `client_max_body_size`). Adapter feature specs SHOULD document their gateway's
body-size configuration.


### FR-001-05: Transform Profiles (Backend-Specific Definitions)

**Requirement:** Backend-specific transformation details MUST be packaged as
**transform profiles** — self-contained YAML files that compose transform specs with
match criteria and direction. Profiles are NOT part of the core engine; they are
user-supplied configuration.

Profiles reference specs using the **`id@version`** syntax. Multiple versions of the
same spec MAY be loaded concurrently, allowing gradual migration across routes:

```yaml
# Profile: PingAM Callback Prettification
profile: pingam-callback-prettify
description: "Transforms PingAM callback responses into clean JSON for frontend consumption"
version: "1.0.0"

transforms:
  - spec: callback-prettify@2.0.0      # pinned to spec version 2.0.0
    direction: response
    match:
      path: "/json/alpha/authenticate"
      method: POST
      # content-type is optional here — the spec's prerequisite (ADR-0015)
      # already declares it handles JSON. Profile can further narrow.

  - spec: callback-prettify@1.0.0      # legacy route still on v1
    direction: response
    match:
      path: "/json/bravo/authenticate"
      method: POST
```

The engine MUST:
1. Load all versions of a spec (e.g., `callback-prettify@1.0.0` and `@2.0.0`) as
   separate compiled `TransformSpec` instances.
2. Resolve `spec: id@version` references at profile load time — fail fast if the
   referenced version is not found.
3. If a profile references `spec: callback-prettify` **without** a version suffix,
   the engine MUST resolve to the **latest** loaded version (highest semver).
4. Verify that the spec's prerequisite `match` block (ADR-0015) is compatible with
   the profile entry's `match` criteria. Both MUST be satisfied for a transform to
   apply — the spec prerequisite is ANDed with the profile match. A spec prerequisite
   that logically conflicts with the profile match (e.g., spec requires
   `application/xml` but profile matches `application/json`) SHOULD be reported as
   a load-time warning.

When multiple entries **within a single profile** match the same request, the engine
uses **most-specific-wins** resolution (ADR-0006):

1. **Specificity score**: count literal (non-wildcard) path segments. Higher score wins.
   - `/json/alpha/authenticate` (3 literals) beats `/json/*/authenticate` (2 literals).
   - `/json/*/authenticate` (2 literals) beats `/json/*` (1 literal).
2. **Tie-breaking**: if two entries have the same specificity score:
   a. More `match` constraints (method, content-type) wins.
   b. If still tied → **load-time error** (ambiguous match detected, must be resolved
      by the operator).
3. **Structured logging**: every matched entry MUST be logged (NFR-001-08) so
   operators can trace exactly which entry was selected.

**Cross-profile routing is product-defined (ADR-0023).** Whether multiple profiles can apply to
the same request — and in what order — is determined by the **gateway product's
deployment model**, not by the core engine. Examples:
- In PingAccess, an adapter instance is bound to a specific rule at the API operation,
  context root, or global level. Each instance has its own profile configuration.
- In Kong, a plugin instance is attached to a specific route or service.
- In a standalone proxy, the deployment configuration determines routing.

The core engine does not detect or resolve cross-profile conflicts. Each engine
invocation processes one profile. If the gateway product routes a single request
to multiple engine invocations (e.g., multiple adapter instances), the resulting
behaviour is **product-defined**.

**Profile-level chaining (pipeline semantics):** When multiple transform entries
within a single profile match the same request (same path, method, content-type),
they form an **ordered pipeline** executed in declaration order:

1. Specs execute in the order they appear in the profile YAML.
2. The **output body** of spec N becomes the **input body** of spec N+1.
3. `TransformContext` (headers, status) is **re-read** from the `Message` envelope
   before each step — so header changes applied by spec N's `headers` block are
   visible to spec N+1's `$headers` variable.
4. **Abort-on-failure**: if any spec in the chain fails at evaluation time (evaluation error,
   budget exceeded), the **entire chain aborts**. The engine returns a configurable
   error response to the caller (ADR-0022). No partial pipeline results reach the
   client or the downstream service.
5. Structured logging MUST include the chain step index (e.g., `chain_step: 2/3`)
   alongside the spec id for each evaluated step.
6. **Direction consistency**: all entries in a chain MUST share the same `direction`.
   The direction is determined by the adapter's invocation point (request phase or
   response phase). A profile with matching entries that declare conflicting
   directions (e.g., one `response`, one `request` on the same path/method) MUST
   be rejected at load time.

This is the mechanism that enables mixed-engine composition (ADR-0008), e.g., JOLT
structural shift → JSLT conditional enrichment on the same route.

| Aspect | Detail |
|--------|--------|
| Success path | Profile loaded → versioned specs resolved → bound to URL patterns |
| Validation path | Missing spec reference → fail at load time with descriptive error |
| Validation path | Referenced version not loaded → fail at load time |
| Validation path | Two profiles with identical specificity and constraints → load-time error |
| Failure path | Duplicate profile IDs → reject, no silent override |
| Failure path | Chain step fails → entire chain aborts, error response returned (ADR-0022) |
| Source | Kong route/plugin binding, Apigee flow attachment, ADR-0005, ADR-0006, ADR-0008, ADR-0015 |

### FR-001-06: Passthrough Behavior

**Requirement:** When a message does not match any transform profile, the engine
MUST pass the message through **completely unmodified**. No headers, body, or status
code changes. This is the default behavior for non-matching requests.

**Important:** Passthrough applies **only** when no profile matches. When a profile
matches but the transformation fails (expression error, schema violation, etc.), the
engine returns a **configurable error response** — NOT passthrough (ADR-0022). The
downstream service expects the transformed schema; sending the untransformed message
would cause a downstream failure with no clear error signal.

| Aspect | Detail |
|--------|--------|
| Success path | Non-matching request → forwarded unmodified |
| Validation path | N/A |
| Failure path | N/A — passthrough only applies to non-matching requests |
| Source | Fundamental safety principle, ADR-0022 |

### FR-001-07: Error Handling

**Requirement:** When a transformation fails at evaluation time (e.g., JSLT evaluation
error, missing field, output size exceeded, schema validation failure in strict mode),
the engine MUST:
1. Log a structured error entry with the spec ID, engine id, error detail, and chain
   step index (if applicable).
2. Produce a **configurable error response** and signal the adapter to return it to
   the caller.
3. Never pass the untransformed message through to the downstream service — the
   downstream expects the transformed schema and will fail anyway (ADR-0022).
4. Never return an empty, corrupted, or partially-evaluated message.

All exceptions are typed according to the **Error Catalogue** (below, ADR-0024).
Evaluation-time exceptions (`TransformEvalException` subtypes) are caught by the
engine and translated into error responses. Load-time exceptions
(`TransformLoadException` subtypes) propagate to the adapter for startup/reload
handling.

**Error response format** is configurable via `error-response.format`:
- `rfc9457` (default): RFC 9457 Problem Details for HTTP APIs (`application/problem+json`).
- `custom`: operator-defined JSON template with `{{error.detail}}`, `{{error.specId}}`,
  `{{error.type}}` placeholders.

```yaml
# Engine-level error response config
error-response:
  format: rfc9457                # "rfc9457" (default) or "custom"
  status: 502                    # default HTTP status for transform failures
  custom-template: |             # only used when format: custom
    {
      "errorCode": "TRANSFORM_FAILED",
      "message": "{{error.detail}}",
      "specId": "{{error.specId}}"
    }
```

**Example RFC 9457 error response:**
```json
{
  "type": "urn:message-xform:error:expression-eval-failed",
  "title": "Transform Failed",
  "status": 502,
  "detail": "JSLT evaluation error in spec 'callback-prettify@1.0.0': undefined variable at line 3",
  "instance": "/json/alpha/authenticate"
}
```

| Aspect | Detail |
|--------|--------|
| Success path | Transform fails → log error → return configurable error response to caller |
| Validation path | Error response config validated at engine startup |
| Failure path | Engine itself crashes → gateway adapter catches exception, returns error response |
| Source | Defensive design, ADR-0022, ADR-0024 |

### Error Catalogue (ADR-0024)

The engine defines a **two-tier exception hierarchy** that separates load-time
configuration errors from per-request evaluation errors. All exceptions inherit
from the abstract `TransformException`.

```
TransformException (abstract — never thrown directly)
├── TransformLoadException (abstract — load-time parent)
│   ├── SpecParseException
│   ├── ExpressionCompileException        ← FR-001-02
│   ├── SchemaValidationException
│   ├── ProfileResolveException
│   └── SensitivePathSyntaxError
└── TransformEvalException (abstract — evaluation-time parent)
    ├── ExpressionEvalException           ← FR-001-02
    ├── EvalBudgetExceededException
    └── InputSchemaViolation
```

#### Load-Time Exceptions (`TransformLoadException`)

Thrown during `TransformEngine.loadSpec()` or `TransformEngine.loadProfile()`.
These indicate **configuration problems** that must be resolved before traffic flows.

| Exception | Cause | Example |
|-----------|-------|---------|
| `SpecParseException` | Invalid YAML syntax in a spec file | Missing colon, bad indentation |
| `ExpressionCompileException` | Expression syntax error in any engine | `if (.x "bad"` — missing paren |
| `SchemaValidationException` | JSON Schema block is invalid (2020-12) | `type: not-a-type` |
| `ProfileResolveException` | Missing spec ref, unknown version, ambiguous match | `spec: foo@3.0.0` not loaded |
| `SensitivePathSyntaxError` | Invalid RFC 9535 path in `sensitive` list | Missing `$` prefix |

**Adapter contract:** Adapters MUST surface `TransformLoadException` during startup
or reload. The engine MUST NOT silently accept broken configuration.

#### Evaluation-Time Exceptions (`TransformEvalException`)

Thrown per-request during `TransformEngine.transform()`. The engine catches these
internally and produces a **configurable error response** (ADR-0022).

| Exception | Cause | Example |
|-----------|-------|---------|
| `ExpressionEvalException` | Expression execution error | Undefined variable, type error |
| `EvalBudgetExceededException` | `max-eval-ms` or `max-output-bytes` exceeded | 200ms vs 50ms budget |
| `InputSchemaViolation` | Strict-mode input validation failure | Missing `required` field |

**Error response `type` URNs** (RFC 9457):

| Exception | URN |
|-----------|-----|
| `ExpressionEvalException` | `urn:message-xform:error:expression-eval-failed` |
| `EvalBudgetExceededException` | `urn:message-xform:error:eval-budget-exceeded` |
| `InputSchemaViolation` | `urn:message-xform:error:schema-validation-failed` |

#### Common Exception Fields

All exceptions carry:
- `specId` (String, nullable) — the spec that triggered the error.
- `detail` (String) — human-readable error description.
- `phase` (enum: `LOAD`, `EVALUATION`) — derived from the tier.

`TransformLoadException` additionally:
- `source` (String, nullable) — file path or resource identifier.

`TransformEvalException` additionally:
- `chainStep` (Integer, nullable) — pipeline chain step index.

### FR-001-08: Reusable Mappers

**Requirement:** Transform specs MAY define reusable named expressions under a
`mappers` block. Mappers are invoked via a declarative `apply` list that sequences
named mappers with the main `expr` in declaration order (ADR-0014).

**Invocation model:** Transform-level sequential directive only. Mappers are NOT
callable as inline functions within JSLT expressions. This preserves engine-agnosticism
— the `apply` directive works identically for JSLT, JOLT, jq, and any future engine.

```yaml
id: order-transform
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

transform:
  lang: jslt
  expr: |
    {
      "orderId": .orderId,
      "amount": .amount,
      "currency": .currency
    }

  # Declarative pipeline: run these steps in order
  apply:
    - mapperRef: strip-internal         # step 1: strip internal fields from input
    - expr                              # step 2: run the main transform expression
    - mapperRef: add-gateway-metadata   # step 3: add metadata to output
```

**Evaluation flow:**
1. Engine takes input body → feeds to `strip-internal` mapper → cleaned body.
2. Cleaned body → feeds to main `expr` → restructured output.
3. Restructured output → feeds to `add-gateway-metadata` → final output.

**Rules:**
- Each step in `apply` receives the previous step's output as its input.
- `expr` refers to the main `transform.expr` expression. It MUST appear exactly once.
- When `apply` is **absent**, the transform behaves as before: only `expr` is evaluated
  (backwards-compatible).
- The `apply` list is **spec-internal** — it does NOT interact with profile-level
  chaining (ADR-0012). Profile chaining composes across specs; `apply` composes
  within a single spec.
- Each mapper compiles to a `CompiledExpression` at load time. Unknown mapper ids or
  duplicate mapper references in the `apply` list are rejected at load time.

| Aspect | Detail |
|--------|--------|
| Success path | `apply` steps executed in order; each step's output feeds the next |
| Validation path | Missing mapper id → reject at load time |
| Validation path | `expr` missing from `apply` list → reject at load time |
| Validation path | `expr` appears more than once in `apply` → reject at load time |
| Failure path | Duplicate mapperRef in `apply` list → reject at load time |
| Source | ADR-0014 |

### FR-001-09: Input/Output Schema Validation

**Requirement:** Every transform spec MUST declare `input.schema` and `output.schema`
using JSON Schema 2020-12. The engine MUST validate these schemas at **load time**
(spec compilation), not at evaluation time.

```yaml
id: callback-prettify
version: "1.0.0"

input:
  schema:
    type: object
    required: [callbacks, authId]
    properties:
      callbacks: { type: array }
      authId: { type: string }

output:
  schema:
    type: object
    required: [fields, type]
    properties:
      fields: { type: array }
      type: { type: string }
```

At load time, the engine:
1. Parses the JSON Schema blocks from the spec YAML.
2. Validates that the schemas themselves are valid JSON Schema 2020-12.
3. Stores them alongside the compiled expression for optional evaluation-time validation
   (e.g., in development/debug mode).

At evaluation time (optional, configurable):
- In `strict` mode: validate input against `input.schema` before evaluation and
  output against `output.schema` after evaluation.
- In `lenient` mode: skip evaluation-time schema validation (production default for
  performance).

| Aspect | Detail |
|--------|--------|
| Success path | Schemas parse as valid JSON Schema 2020-12 → stored with spec |
| Validation path | Invalid JSON Schema syntax → reject spec at load time |
| Failure path | Evaluation-time validation failure (strict mode) → return error response (ADR-0022) |
| Source | ADR-0001 |

### FR-001-10: Header Transformations

**Requirement:** Transform specs MAY include an optional `headers` block with
declarative `add`, `remove`, and `rename` operations. Header transforms are processed
by the engine **independently** of the body JSLT expression.

```yaml
headers:
  add:
    X-Transformed-By: "message-xform"          # static value
    X-Spec-Version: "1.0.0"                     # static value
    X-Error-Code:                                # dynamic — from body
      expr: .error.code
    X-Auth-Method:                               # dynamic — from transformed body
      expr: .type
  remove: ["X-Internal-*", "X-Debug-*"]         # glob patterns supported
  rename:
    X-Old-Header: X-New-Header
```

Dynamic header values use an `expr` sub-key containing a JSLT expression evaluated
against the **transformed** body. This enables **body-to-header injection** (e.g.,
extract an error code from the body and emit it as a response header).

**Header name normalization:** The engine MUST normalize all header names to
**lowercase** when binding `$headers` and `$headers_all` (RFC 9110 §5.1 — HTTP
headers are case-insensitive; HTTP/2 mandates lowercase). Header names in
`headers.add`, `headers.remove`, and `headers.rename` MUST use case-insensitive
matching.

Additionally, the engine MUST expose request/response headers as a **read-only JSLT
variable** `$headers`, allowing JSLT body expressions to reference header values
(**header-to-body injection**):

```yaml
transform:
  lang: jslt
  expr: |
    {
      "requestId": $headers."X-Request-ID",
      "authId": .authId,
      "fields": [for (.callbacks) { "label": .output[0].value }]
    }
```

The `$headers` variable is a `JsonNode` object where each key is a header name and
each value is the first header value (string). For multi-value headers (e.g.,
`Set-Cookie`, `X-Forwarded-For`), use `$headers_all` which exposes **all** values
as arrays of strings (ADR-0026):

```yaml
transform:
  lang: jslt
  expr: |
    {
      "firstCookie": $headers."Set-Cookie",           # → "session=abc" (first only)
      "allCookies": $headers_all."Set-Cookie",         # → ["session=abc", "lang=en"]
      "clientIps": $headers_all."X-Forwarded-For",     # → ["1.1.1.1", "2.2.2.2"]
      "contentType": $headers_all."Content-Type"       # → ["application/json"]
    }
```

`$headers_all` is a `JsonNode` object where keys are header names and values are
**always arrays of strings** — even for single-value headers. Missing headers
evaluate to `null` (not empty array).

Processing order:
1. Engine reads headers from the `Message` envelope → binds as `$headers` (first
   value per name) and `$headers_all` (all values as arrays).
2. JSLT body expression evaluates with `$headers` and `$headers_all` available as
   read-only context.
3. Declarative header operations applied:
   a. `remove` — strip matching headers (glob patterns).
   b. `rename` — rename header keys.
   c. `add` (static) — set headers with literal string values.
   d. `add` (dynamic) — evaluate `expr` against the **transformed** body, set result as header value.

| Aspect | Detail |
|--------|--------|
| Success path | Headers block parsed → add/remove/rename applied after body transform |
| Validation path | Invalid glob in `remove` → reject at load time |
| Failure path | Referenced header missing → `$headers."X-Missing"` evaluates to `null` (JSLT default) |
| Failure path | Dynamic header `expr` returns non-string → coerce to JSON string representation |
| Source | Kong transformer add/remove/rename pattern, ADR-0002 |

**Dynamic header `expr` engine:** Dynamic header expressions are evaluated using
the **same engine** as the transform's `lang` declaration. For engines that do not
support context variables (e.g., JOLT), dynamic header expressions MUST be
rejected at load time.

### FR-001-11: Status Code Transformations

**Requirement:** Transform specs MAY include an optional `status` block to modify
the HTTP response status code. The engine MUST expose the current status code as a
**read-only JSLT variable** `$status`.

```yaml
status:
  set: 400                          # target status code
  when: '.error != null'            # optional JSLT predicate — only set if true
```

The `when` predicate is a JSLT expression evaluated against the **transformed** body.
If `when` is omitted, the status is set unconditionally. If present, the status is
only changed when the predicate evaluates to `true`.

The `$status` variable is an integer bound before JSLT body evaluation, allowing
status-aware body transforms. **For request transforms, `$status` is `null`** because
no HTTP status exists yet (ADR-0017). Authors who use `$status` in direction-agnostic
specs (ADR-0016) SHOULD guard with `if ($status)` or `$status != null`.

```yaml
transform:
  lang: jslt
  expr: |
    {
      "success": $status < 400,
      "httpStatus": $status,
      "data": .data
    }

status:
  set: 400
  when: '.error != null'
```

Processing order:
1. Engine reads status code from the `Message` envelope → binds as `$status` (integer
   for response transforms, `null` for request transforms).
2. JSLT body expression evaluates with `$status` and `$headers` available.
3. Declarative header operations applied (FR-001-10).
4. Status `when` predicate evaluated against the **transformed** body.
5. If `when` is true (or absent), status code set to `set` value.

| Aspect | Detail |
|--------|--------|
| Success path | Status block parsed → predicate evaluated → status updated |
| Validation path | `set` not a valid HTTP status code (100–599) → reject at load time |
| Failure path | `when` predicate evaluation error → abort, keep original status |
| Source | ADR-0003, ADR-0017 |

### FR-001-12: URL Rewriting

**Requirement:** Transform specs MAY include an optional `url` block to rewrite the
request path, modify query parameters, and override the HTTP method. URL rewriting
enables de-polymorphization of orchestration endpoints — converting a generic
`POST /dispatch` (where the operation is determined by a body field) into specific
REST-style URLs like `DELETE /api/users/123`.

```yaml
url:
  # Path rewrite — JSLT expression, MUST return a string
  path:
    expr: '"/api/" + .action + "/" + .resourceId'

  # Query parameter operations (same pattern as headers block)
  query:
    add:
      format: "json"                          # static value
      correlationId:
        expr: '$headers."X-Correlation-ID"'   # dynamic — from header context
    remove: ["_debug", "_internal"]           # glob patterns (like header remove)

  # HTTP method override (same set/when pattern as status block)
  method:
    set: "DELETE"
    when: '.action == "delete"'               # optional predicate
```

**Expression evaluation context (ADR-0027):** URL expressions (`url.path.expr`,
`url.query.add.*.expr`, `url.method.when`) evaluate against the **original**
(pre-transform) body, NOT the transformed body. This is a documented exception to
the convention used by `headers.add.expr` and `status.when` (which use transformed
body). Rationale: URL rewrite *routes the input* — routing fields like `action` and
`resourceId` are typically stripped by the body transform; they must remain available
for URL construction.

Context variables `$headers`, `$headers_all`, `$status`, `$requestPath`,
`$requestMethod`, `$queryParams`, `$cookies`, and `$session` are available in URL
expressions (same bindings as body expressions).

**Direction restriction:** URL rewriting is only meaningful for **request transforms**.
A `url` block on a response-direction transform is ignored with a warning logged at
load time.

**URL encoding:** The engine MUST percent-encode the result of `url.path.expr` per
RFC 3986 §3.3. Body field values containing spaces, `?`, `#`, or other reserved
characters are encoded. Path separators (`/`) are preserved as-is because the
expression is expected to construct a valid path structure.

Processing order (updated — see also FR-001-10, FR-001-11):
1. Engine reads metadata → binds `$headers`, `$headers_all`, `$status`,
   `$requestPath`, `$requestMethod`, `$queryParams`, `$cookies`, `$session`.
2. JSLT body expression evaluates (`transform.expr`) → transformed body.
3. **URL rewrite applied** (against **original** body):
   a. `path.expr` evaluated → new request path (string).
   b. `query.remove` — strip matching query parameters (glob patterns).
   c. `query.add` (static) — set query parameters with literal values.
   d. `query.add` (dynamic) — evaluate `expr` against original body.
   e. `method.when` predicate evaluated → if true (or absent), `method.set` applied.
4. Header operations applied (against **transformed** body):
   a. `remove` → `rename` → `add` (static) → `add` (dynamic).
5. Status `when` predicate evaluated (against **transformed** body) → status set.

| Aspect | Detail |
|--------|--------|
| Success path | URL block parsed → path/query/method applied after body transform |
| Validation path | `method.set` not a valid HTTP method → reject at load time |
| Validation path | Invalid glob in `query.remove` → reject at load time |
| Validation path | `url` block on response-direction transform → warning at load time |
| Failure path | `path.expr` returns null or non-string → `ExpressionEvalException` |
| Failure path | `path.expr` evaluation error → `ExpressionEvalException` → error response (ADR-0022) |
| Failure path | `method.when` evaluation error → abort, keep original method |
| Source | PingAccess URL Rewrite Rules, ADR-0027 |

**Method validation:** `method.set` MUST be one of: `GET`, `POST`, `PUT`, `DELETE`,
`PATCH`, `HEAD`, `OPTIONS`. Any other value is rejected at load time with a
`SpecParseException`.

**Query parameter encoding:** Query parameter values from `query.add` (both static
and dynamic) are percent-encoded per RFC 3986 §3.4.

### FR-001-13: Session Context Binding

**Requirement:** The engine MUST support an optional, gateway-provided **session
context** — an arbitrary JSON structure representing the state of a user or workload
session. When present, the session context is exposed as a read-only JSLT variable
`$session`, following the same pattern as `$headers`, `$status`, `$queryParams`, and
`$cookies`.

Session context is NOT part of the HTTP message. It comes from the gateway product's
session management layer — e.g., PingAccess SDK session API, gateway policy output,
token introspection result, or cookie-based session attributes. The shape of the
session context is gateway-specific and unpredictable (arbitrary JSON).

**`$session` variable:**
- Type: `JsonNode` (nullable).
- When the gateway does not provide session context, `$session` is `null`.
- JSLT handles null gracefully: `$session.sub` returns `null` when `$session` is null.
- Available in **both** request and response transforms — the session context does
  not change between request/response processing in the same request lifecycle.

```yaml
transform:
  lang: jslt
  expr: |
    . + {
      "userId": $session.sub,
      "tenantId": $session.tenantId,
      "roles": $session.roles
    }
```

**Adapter responsibilities:**
- Each gateway adapter populates `Message.session()` from its native session
  API during `wrapRequest()` / `wrapResponse()`, using `SessionContext.of(map)`
  for populated sessions.
- If the gateway has no session concept or no session exists, the adapter passes
  `null` or `SessionContext.empty()` — the `Message` canonical constructor
  normalizes `null` to `SessionContext.empty()`.
- Session context population is **optional** — adapters that do not support
  sessions simply pass `null` (normalized to `SessionContext.empty()`).

**Mutability:** Read-only. Transforms consume session data but do NOT modify it.
Writing to session state is a gateway-level concern, not a transform concern.
Session write-back (`payload-to-session`) is a potential future extension.

**Engine support matrix:** Engines that do not support context variables (JOLT)
cannot access `$session`. Specs declaring `lang: jolt` with `$session` references
MUST be rejected at load time with a diagnostic message.

| Aspect | Detail |
|--------|--------|
| Success path | Adapter populates `Message.session()` via `SessionContext.of(map)` → engine binds `$session` → JSLT accesses session fields |
| Success path | No session provided → `SessionContext.empty()` → `$session` is null in JSLT → null-safe access |
| Validation path | JOLT spec with `$session` reference → rejected at load time |
| Failure path | N/A — `$session` is always nullable; null access is safe in JSLT |
| Source | ADR-0030, ADR-0021 precedent |

### FR-001-14: Port Value Objects (Anti-Corruption Layer)

**Requirement:** Core's public API MUST NOT expose any third-party types
(Jackson `JsonNode`, SLF4J 2.x-only APIs, etc.) to adapters or callers.
Instead, core defines its own **port value objects** — simple, immutable
types using only standard library primitives (byte arrays, maps, strings,
enums). These types form the Anti-Corruption Layer boundary (ADR-0032,
ADR-0033, Level 2 Hexagonal Architecture).

Core defines four port types:

#### FR-001-14a: `MediaType` Enum

```typescript
/** Core-owned replacement for raw content-type strings. Zero dependencies. */
enum MediaType {
  JSON   = "application/json",
  XML    = "application/xml",
  FORM   = "application/x-www-form-urlencoded",
  TEXT   = "text/plain",
  BINARY = "application/octet-stream",
  NONE   = null                            // no content type
}

namespace MediaType {
  /** Returns the MIME type string, or null for NONE. */
  function value(): string | null;

  /**
   * Resolves a Content-Type header value to a MediaType.
   * Ignores parameters (charset, boundary, etc.).
   * Recognizes structured suffixes: +json → JSON, +xml → XML.
   * Returns NONE for null/blank, BINARY for unrecognized types.
   */
  function fromContentType(contentType: string | null): MediaType;
}
```

Core-owned replacement for raw `String contentType` and Jakarta `MediaType`.
Zero third-party dependencies.

#### FR-001-14b: `MessageBody` Record

```typescript
/** Immutable body container — opaque bytes + media type. */
type MessageBody = {
  readonly content: Uint8Array;           // body bytes (normalized: null → empty)
  readonly mediaType: MediaType;

  /** True when content is null or zero-length. */
  isEmpty(): boolean;
  /** Returns content as a UTF-8 string. */
  asString(): string;
  /** Content length in bytes. */
  size(): number;
};

namespace MessageBody {
  /** Factory methods — guide adapter developers */
  function json(content: Uint8Array | string): MessageBody;
  function empty(): MessageBody;
  function of(content: Uint8Array, mediaType: MediaType): MessageBody;
}
```

**Design constraints:**
- Custom `equals()`/`hashCode()` MUST use `Arrays.equals()` for byte
  comparison (records use reference equality for arrays by default).
- `null` content is normalized to empty `byte[0]` in the constructor.
- Factory methods set the correct `MediaType` automatically.

#### FR-001-14c: `HttpHeaders` Class

```typescript
/** Immutable HTTP header container — case-insensitive, dual-view (single/multi-value). */
type HttpHeaders = {
  /** First value for a header name (case-insensitive). */
  first(name: string): string | null;
  /** All values for a header name (case-insensitive). */
  all(name: string): string[];
  /** True if the header exists (case-insensitive). */
  contains(name: string): boolean;
  /** Returns true if no headers are present. */
  isEmpty(): boolean;

  /** First-value-per-name view (lowercase keys). */
  toSingleValueMap(): Record<string, string>;
  /** All-values-per-name view (lowercase keys). */
  toMultiValueMap(): Record<string, string[]>;
};

namespace HttpHeaders {
  function of(singleValue: Record<string, string>): HttpHeaders;
  function ofMulti(multiValue: Record<string, string[]>): HttpHeaders;
  function empty(): HttpHeaders;
}
```

**Design constraints:**
- Header names MUST be normalized to **lowercase** in all factory methods
  (RFC 9110 §5.1).
- The class is **immutable** — no add/remove/set mutations.
- Internal storage SHOULD use `TreeMap` for consistent iteration order.
- Replaces both `Map<String, String> headers` and
  `Map<String, List<String>> headersAll` — single type for both views.

#### FR-001-14d: `SessionContext` Class

```typescript
/** Immutable session attribute container — toString() prints keys only (no sensitive values). */
type SessionContext = {
  /** Raw attribute value by key. */
  get(key: string): unknown | null;
  /** String-typed attribute by key (returns null if absent or non-string). */
  getString(key: string): string | null;
  /** True if the key exists. */
  has(key: string): boolean;
  /** True if empty or no attributes. */
  isEmpty(): boolean;

  /** Returns a defensive copy of the underlying map. */
  toMap(): Record<string, unknown>;
};

namespace SessionContext {
  function of(attributes: Record<string, unknown>): SessionContext;
  function empty(): SessionContext;    // SHOULD return a singleton
}
```

**Design constraints:**
- `toString()` MUST print only key names (not values) to avoid leaking
  sensitive session data in logs.
- `empty()` SHOULD return a singleton instance.
- Replaces `JsonNode sessionContext` — adapters convert from their native
  session types to `Map<String, Object>` using their own Jackson/serializer.

#### Interaction with Existing FRs

- **FR-001-04** (Message Envelope): `Message` uses `MessageBody`, `HttpHeaders`,
  and `SessionContext` instead of `JsonNode`, `Map`, and raw `String contentType`.
- **FR-001-02** (Expression Engine SPI): `CompiledExpression.evaluate()` continues
  to accept `JsonNode` and `TransformContext` — but `JsonNode` is now an
  **internal** type (bundled via relocation). Adapters never see it.
- **FR-001-07** (Error Handling): `TransformResult.errorResponse()` returns
  `MessageBody` instead of `JsonNode`.
- **FR-001-13** (Session Context): `Message.session()` returns `SessionContext`
  instead of `JsonNode`.

| Aspect | Detail |
|--------|--------|
| Success path | Adapter creates `Message` using port factories → engine converts internally → result uses port types |
| Validation path | `MessageBody` null content normalized to empty bytes |
| Validation path | `HttpHeaders.of()` normalizes keys to lowercase |
| Failure path | N/A — port types are simple value objects, no external I/O |
| Source | ADR-0032, ADR-0033, Hexagonal Architecture |

## Non-Functional Requirements

| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-001-01 | The engine MUST be stateless per-request — no server-side session, cache, or shared mutable state between requests. JSLT `Expression` objects are immutable and shared across threads. | Horizontal scalability; gateway deployments must not require shared state. | Unit tests confirm no mutable static state or cross-request side effects. | None. | Architecture. |
| NFR-001-02 | The core engine library MUST have zero gateway-specific dependencies. Allowed dependencies: Jackson (JSON), SnakeYAML (YAML parsing), JSLT (transformation), and a JSON Schema validator (e.g., `networknt/json-schema-validator`). **Public API boundary (ADR-0032, ADR-0033):** Core's public types (`Message`, `TransformResult`, `TransformContext`, port value objects) MUST NOT reference any third-party types in their signatures. Third-party types (Jackson, JSLT, SnakeYAML) are bundled and relocated inside core's shadow JAR. The public API uses only Java standard library types and core-owned port value objects (FR-001-14). | Gateway-agnostic core enables adapter reuse across PingAccess, PingGateway, Kong, standalone. Eliminates Jackson version coupling between core and adapters. | Maven/Gradle dependency analysis confirms no gateway SDK imports in `core`. Static analysis confirms `Message`, `TransformResult`, `TransformContext` signatures contain zero `com.fasterxml` / `org.slf4j` / third-party type references. | Module structure, Shadow plugin relocation. | `PLAN.md`, ADR-0001, ADR-0032, ADR-0033. |
| NFR-001-03 | **Latency:** transformation p95 latency MUST be < 5 ms for payloads ≤ 50 KB. Compiled JSLT expressions are evaluated per-request; compilation happens once at spec load time. **Metrics:** benchmarks MUST report p50, p90, p95, p99, and max latency (ms), average latency (ms), and throughput (ops/sec). **Payload tiers:** benchmarks MUST cover at least three tiers — small (~1 KB, identity/passthrough), medium (~10 KB, representative field mapping), and large (~50 KB, nested structure with array iteration). **Throughput floor:** identity transforms SHOULD sustain ≥ 10,000 ops/sec on development hardware (informational, not a hard gate). **Environment:** benchmark results MUST be recorded with OS, CPU architecture, Java version, and available processors for reproducibility. | Gateway rules are in the critical path; latency budget is tight. Percentile-based targets prevent outlier masking. | Opt-in microbenchmark with representative payloads across payload tiers; soft-assertion on p95 target (ADR-0028). | Core only. | Performance, ADR-0028. |
| NFR-001-04 | Unknown/unrecognized fields in the input message MUST NOT cause transformation failure. JSLT supports `* : .` (object matching) to pass through unmentioned fields. | Upstream services may add fields at any time; transformation must be forward-compatible. | Test with input containing extra fields not in spec. | Core. | Robustness. |
| NFR-001-05 | The core engine MUST support **atomic registry swap**: the ability to replace the full set of compiled specs and profiles via `TransformEngine.reload()`. The swap MUST use an immutable `TransformRegistry` snapshot and `AtomicReference` (or equivalent) so that in-flight requests complete with their current registry while new requests pick up the new one. Reload trigger mechanisms (file watching, polling, gateway lifecycle hooks) are adapter concerns, NOT core. | Core must be designed for safe concurrent registry replacement. | Unit test: concurrent reads during swap observe either old or new registry, never a mix. | Core. | Architecture, ADR-0012. |
| NFR-001-06 | The engine MUST NOT log, cache, or inspect the content of fields marked as `sensitive` in the spec. Sensitive fields are declared via a top-level `sensitive` list of JSON path expressions (ADR-0019). Paths use RFC 9535 dot-notation with `[*]` wildcard. The engine validates path syntax at load time. Matched fields are replaced with `"[REDACTED]"` in structured logs and MUST NOT appear in cache keys or telemetry payloads. | Security — passwords, tokens, and secrets must never appear in logs. | Static analysis + code review to confirm no sensitive-field logging. | Core. | Security, ADR-0019. |
| NFR-001-07 | Expression evaluation MUST be bounded by configurable time (`max-eval-ms`) and output size (`max-output-bytes`) limits. Exceeding either budget MUST abort the evaluation. | Prevents runaway expressions from blocking gateway threads. | Test with deliberately slow/large expressions. | Core. | Safety. |
| NFR-001-08 | When a profile matches a request, the engine MUST emit a structured log entry containing: matched profile id, matched spec id@version, request path, match specificity score, and evaluation duration. Format: JSON structured log line. | Operational traceability — operators must always know which profile was selected and why. | Integration test: verify log output contains required fields for each matched request. | Core + SLF4J or equivalent. | Observability, ADR-0006. |
| NFR-001-09 | The core engine MUST define a `TelemetryListener` SPI interface for semantic transform events (started, completed, failed, matched, loaded, rejected). The SPI is a plain Java interface with zero external dependencies. Adapter modules provide concrete OTel/Micrometer bindings. Core metrics vocabulary: `transform_evaluations_total`, `transform_duration_seconds`, `profile_matches_total`, `spec_load_errors_total`. | Production-grade observability without violating NFR-001-02 (zero gateway deps). Consistent with ADR-0007 layered model. | Integration test: verify TelemetryListener receives events for each transform lifecycle stage. | Core (SPI interface only). | Observability, ADR-0007. |
| NFR-001-10 | The engine MUST propagate incoming trace context headers (`X-Request-ID`, `traceparent`) through all structured log entries and telemetry events. The engine participates in the caller's trace context but does NOT create new traces. | Enables end-to-end request correlation across gateway → engine → upstream services. | Integration test: send request with `X-Request-ID` → verify it appears in all log/telemetry output. | Core. | Observability, ADR-0007. |

## Branch & Scenario Matrix

> Full scenario definitions, test vectors, and coverage matrix are maintained in
> [`scenarios.md`](scenarios.md) (73 scenarios: S-001-01 through S-001-73).
> This summary lists representative scenarios for quick reference.

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-001-01 | JSLT transform: flat input → restructured output (rename, nest, derive) |
| S-001-02 | Bidirectional transform: forward applied to response, reverse applied to request |
| S-001-03 | Non-matching request → passed through unmodified |
| S-001-05 | Unknown fields in input → preserved in output via `* : .` (open-world) |
| S-001-08 | JSLT evaluation error → transformation aborted, error response returned (ADR-0022) |
| S-001-14 | Alternative engine: spec declares `lang: jolt` → JOLT engine handles transform |
| S-001-33 | Header add/remove/rename alongside body transforms |
| S-001-36 | Status code override via conditional `when` predicate |
| S-001-41 | Profile matches request by path, method, and content-type |
| S-001-49 | Profile-level chaining: JOLT → JSLT mixed-engine pipeline |
| S-001-50 | Reusable mapper: `mapperRef` resolves to named expression |
| S-001-53 | JSON Schema validated and stored at spec load time |
| S-001-55 | Strict-mode evaluation-time schema validation rejects non-conforming input |
| S-001-82 | `$session.sub` injected into request body (session context binding) |
| S-001-84 | `$session` is null (no session provided) → null-safe access |

## Test Strategy

- **Core (unit):** JUnit 5 tests for JSLT expression evaluation in isolation.
  Parameterized tests with input/output JSON pairs from `core/src/test/resources/test-vectors/`.
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
| DO-001-01 | `Message` — generic HTTP message envelope (`MessageBody` body, `HttpHeaders` headers, status, session — ADR-0032, ADR-0033) | core |
| DO-001-02 | `TransformSpec` — parsed spec (id, version, input/output JSON Schema, compiled JSLT expression, engine id) | core |
| DO-001-03 | `CompiledExpression` — immutable, thread-safe compiled expression handle | core |
| DO-001-04 | `TransformProfile` — user-supplied binding of specs to URL/method/content-type patterns | core |
| DO-001-05 | `TransformResult` — outcome of applying a spec (success/aborted + diagnostics); `errorResponse` uses `MessageBody` | core |
| DO-001-06 | `ExpressionEngine` — pluggable engine SPI (compile + evaluate) | core |
| DO-001-07 | `TransformContext` — read-only context passed to engines (`HttpHeaders` headers, status, queryParams, cookies, `SessionContext` session — ADR-0033) | core |
| DO-001-08 | `MediaType` — content type enum (JSON, XML, FORM, TEXT, BINARY, NONE) with `fromContentType()` factory (FR-001-14a) | core |
| DO-001-09 | `MessageBody` — body value object (`byte[]` content + `MediaType`); zero third-party deps (FR-001-14b) | core |
| DO-001-10 | `HttpHeaders` — case-insensitive header collection; single- and multi-value views (FR-001-14c) | core |
| DO-001-11 | `SessionContext` — gateway session attributes as `Map<String, Object>`; safe `toString()` (FR-001-14d) | core |

### Core Engine API

| ID | Method | Description |
|----|--------|-------------|
| API-001-01 | `TransformEngine.transform(Message, Direction)` | Apply matching profile's spec to the message |
| API-001-02 | `TransformEngine.loadProfile(Path)` | Load a transform profile from YAML |
| API-001-03 | `TransformEngine.loadSpec(Path)` | Load and compile a transform spec from YAML |
| API-001-04 | `TransformEngine.reload()` | Hot-reload: re-parse specs, recompile expressions, atomic swap |
| API-001-05 | `TransformEngine.registerEngine(ExpressionEngine)` | Register a pluggable expression engine |

**Profile match resolution (Q-028):** `transform(Message, Direction)` resolves the
matching profile entry by reading `Message.requestPath()`,
`Message.requestMethod()`, and `Message.mediaType()` internally. The engine
fully owns the matching logic — adapters simply construct a `Message` using port
type factories and call `transform()`. For response transforms, the adapter MUST
include request metadata (path, method) in the response `Message` from the gateway's
exchange/request context, since profile matching always operates on request criteria.

### Expression Engine SPI

| ID | Method | Description |
|----|--------|-------------|
| SPI-001-01 | `ExpressionEngine.id()` | Return engine identifier (e.g., `"jslt"`) |
| SPI-001-02 | `ExpressionEngine.compile(String expr)` | Compile expression string → `CompiledExpression` |
| SPI-001-03 | `CompiledExpression.evaluate(JsonNode input, TransformContext context)` | Evaluate expression against input + context → output `JsonNode`. **Note:** `JsonNode` is a core-internal type (bundled, relocated Jackson — ADR-0032). Adapters never reference it; only engine implementations see this SPI. |

### Gateway Adapter SPI

| ID | Method | Description |
|----|--------|-------------|
| SPI-001-04 | `GatewayAdapter.wrapRequest(native) → Message` | Wrap gateway-native request as Message |
| SPI-001-05 | `GatewayAdapter.wrapResponse(native) → Message` | Wrap gateway-native response as Message |
| SPI-001-06 | `GatewayAdapter.applyChanges(Message, native)` | Write Message changes back to native object |

> **Adapter Lifecycle Guidance (ADR-0025):** The `GatewayAdapter` SPI is intentionally
> limited to per-request operations. Lifecycle management — engine initialization,
> shutdown, reload triggering — is the adapter's responsibility. Each adapter calls
> the `TransformEngine` public API (`loadSpec`, `loadProfile`, `reload`,
> `registerEngine`) from within its gateway-native lifecycle hooks (e.g., PingAccess
> `configure()`, PingGateway `init()`, standalone `main()`). Adapters MUST catch
> `TransformLoadException` (ADR-0024) during initialization and translate it to the
> gateway's native error mechanism. Reload triggers are adapter concerns (NFR-001-05).

### Configuration

| ID | Config key | Type | Description |
|----|-----------|------|-------------|
| CFG-001-01 | `specs-dir` | path | Directory containing transform spec YAML files |
| CFG-001-02 | `profiles-dir` | path | Directory containing transform profile YAML files |
| CFG-001-03 | `error-response.format` | enum | `rfc9457` (default) or `custom` — error response format for transform failures (ADR-0022) |
| CFG-001-04 | `error-response.status` | int | Default HTTP status code for transform failure error responses (default: 502) |
| CFG-001-05 | `error-response.custom-template` | string | JSON template with `{{error.*}}` placeholders — used when `error-response.format: custom` |
| CFG-001-06 | `engines.defaults.max-eval-ms` | int | Per-expression evaluation time budget (default: 50ms) |
| CFG-001-07 | `engines.defaults.max-output-bytes` | int | Max output size per evaluation (default: 1MB) |
| CFG-001-08 | `engines.<id>.enabled` | boolean | Enable/disable a specific expression engine |
| CFG-001-09 | `schema-validation-mode` | enum | `strict` (validate input/output schemas at evaluation time) or `lenient` (skip, default). Separate from `error-mode` (CFG-001-03) which governs transform failure handling. |

### Fixtures & Sample Data

| ID | Path | Purpose |
|----|------|---------|
| FX-001-01 | `core/src/test/resources/test-vectors/jslt-simple-rename.yaml` | Simple JSLT: rename and restructure fields |
| FX-001-02 | `core/src/test/resources/test-vectors/jslt-conditional.yaml` | JSLT with `if/else` logic |
| FX-001-03 | `core/src/test/resources/test-vectors/jslt-array-reshape.yaml` | JSLT with `[for ...]` array transformation |
| FX-001-04 | `core/src/test/resources/test-vectors/bidirectional-roundtrip.yaml` | Forward + reverse JSLT with input/output pairs |
| FX-001-05 | `core/src/test/resources/test-vectors/passthrough.yaml` | Non-matching input → unmodified output |
| FX-001-06 | `core/src/test/resources/test-vectors/open-world.yaml` | Input with extra fields → preserved via `* : .` |
| FX-001-07 | `core/src/test/resources/test-vectors/error-strict-mode.yaml` | JSLT error → transformation aborted |

## Spec DSL

```yaml
domain_objects:
  - id: DO-001-01
    name: Message
    fields:
      - name: body
        type: MessageBody
        note: "Port value object: byte[] content + MediaType (ADR-0032, FR-001-14b)"
      - name: headers
        type: HttpHeaders
        note: "Port value object: case-insensitive, single+multi-value views (FR-001-14c)"
      - name: statusCode
        type: Integer
        constraints: "response only, nullable"
      - name: requestPath
        type: string
        note: "e.g. /json/alpha/authenticate"
      - name: requestMethod
        type: string
        note: "e.g. POST"
      - name: queryString
        type: string
        constraints: "nullable — raw query string without leading '?'"
      - name: session
        type: SessionContext
        constraints: "optional, nullable — gateway-provided (ADR-0030, FR-001-14d)"
        note: "Replaces JsonNode sessionContext"
  - id: DO-001-08
    name: MediaType
    fields:
      - name: value
        type: string
        note: "MIME type string (e.g., 'application/json'), null for NONE"
      - name: fromContentType
        type: "(String) → MediaType"
        note: "Factory: parses Content-Type header, strips params, handles +json/+xml suffixes"
  - id: DO-001-09
    name: MessageBody
    fields:
      - name: content
        type: "byte[]"
        note: "Raw body bytes; null normalized to byte[0]"
      - name: mediaType
        type: MediaType
      - name: isEmpty
        type: "() → boolean"
      - name: asString
        type: "() → String"
        note: "UTF-8 decode"
      - name: size
        type: "() → int"
      - name: json
        type: "(byte[] | String) → MessageBody"
        note: "Factory methods"
      - name: empty
        type: "() → MessageBody"
      - name: of
        type: "(byte[], MediaType) → MessageBody"
  - id: DO-001-10
    name: HttpHeaders
    fields:
      - name: first
        type: "(String) → String"
        note: "Case-insensitive lookup, first value"
      - name: all
        type: "(String) → List<String>"
        note: "Case-insensitive lookup, all values"
      - name: contains
        type: "(String) → boolean"
      - name: isEmpty
        type: "() → boolean"
      - name: toSingleValueMap
        type: "() → Map<String, String>"
        note: "Lowercase keys, first-value semantics"
      - name: toMultiValueMap
        type: "() → Map<String, List<String>>"
        note: "Lowercase keys, all values"
      - name: of
        type: "(Map<String, String>) → HttpHeaders"
        note: "Factory: normalizes keys to lowercase"
      - name: ofMulti
        type: "(Map<String, List<String>>) → HttpHeaders"
      - name: empty
        type: "() → HttpHeaders"
  - id: DO-001-11
    name: SessionContext
    fields:
      - name: get
        type: "(String) → Object"
      - name: getString
        type: "(String) → String"
      - name: has
        type: "(String) → boolean"
      - name: isEmpty
        type: "() → boolean"
      - name: toMap
        type: "() → Map<String, Object>"
        note: "Defensive copy"
      - name: of
        type: "(Map<String, Object>) → SessionContext"
      - name: empty
        type: "() → SessionContext"
        note: "Singleton"
  - id: DO-001-02
    name: TransformSpec
    fields:
      - name: id
        type: string
      - name: version
        type: string
      - name: description
        type: string
        constraints: "optional — human-readable summary"
      - name: lang
        type: string
        constraints: "default: jslt"
      - name: inputSchema
        type: JsonSchema
        constraints: "required — JSON Schema 2020-12"
        note: "Validated at load time; optional evaluation-time validation in strict mode"
      - name: outputSchema
        type: JsonSchema
        constraints: "required — JSON Schema 2020-12"
        note: "Validated at load time; optional evaluation-time validation in strict mode"
      - name: compiledExpr
        type: CompiledExpression
        note: "Immutable, thread-safe — compiled at load time"
      - name: forward
        type: CompiledExpression
        constraints: "optional, for bidirectional specs"
      - name: reverse
        type: CompiledExpression
        constraints: "optional, for bidirectional specs"
      - name: url
        type: UrlRewriteSpec
        constraints: "optional — path, query, method rewrite (ADR-0027)"
        note: "Only applied for request-direction transforms"
  - id: DO-001-06
    name: ExpressionEngine
    fields:
      - name: id
        type: string
        note: "e.g. jslt, jolt, jq, jsonata"
      - name: compile
        type: "(String) → CompiledExpression"
  - id: DO-001-03
    name: CompiledExpression
    fields:
      - name: evaluate
        type: "(JsonNode, TransformContext) → JsonNode"
        note: "Thread-safe, called per-request. JsonNode is core-internal (relocated, ADR-0032)."
  - id: DO-001-07
    name: TransformContext
    fields:
      - name: headers
        type: HttpHeaders
        note: "Port value object — engine converts to $headers/$headers_all JsonNode internally"
      - name: status
        type: Integer
        note: "$status — null for request transforms (ADR-0017, ADR-0020)"
      - name: queryParams
        type: "Map<String, String>"
        note: "$queryParams — keys are param names, values are first value (ADR-0021)"
      - name: cookies
        type: "Map<String, String>"
        note: "$cookies — request-side only, keys are cookie names (ADR-0021)"
      - name: session
        type: SessionContext
        note: "$session — port value object, nullable (ADR-0030, FR-001-14d)"
    # Future: $queryParams_all and $cookies_all (array-of-strings shape, like
    # $headers_all) may be added if multi-value query params or cookies are needed.
    # Not normative for Feature 001.
  - id: DO-001-05
    name: TransformResult
    fields:
      - name: status
        type: "enum: SUCCESS, ERROR, PASSTHROUGH"
        note: "Outcome of the transform invocation"
      - name: message
        type: Message
        constraints: "nullable — null when status is PASSTHROUGH or ERROR"
        note: "The transformed message (on SUCCESS)"
      - name: errorResponse
        type: MessageBody
        constraints: "nullable — present only when status is ERROR"
        note: "RFC 9457 or custom error body as bytes (ADR-0022, ADR-0032)"
      - name: errorStatusCode
        type: Integer
        constraints: "nullable — present only when status is ERROR"
        note: "HTTP status for the error response (CFG error-response.status)"
      - name: diagnostics
        type: "List<String>"
        constraints: "always present, may be empty"
        note: "Structured log entries for matched profile, chain steps, warnings"
      - name: specId
        type: String
        constraints: "nullable — null when status is PASSTHROUGH"
        note: "The matched spec's identifier (threaded from TransformSpec.id())"
      - name: specVersion
        type: String
        constraints: "nullable — null when status is PASSTHROUGH"
        note: "The matched spec's version (threaded from TransformSpec.version())"
  - id: DO-001-12
    name: TransformProfile
    fields:
      - name: id
        type: string
      - name: version
        type: string
      - name: description
        type: string
        constraints: "optional"
      - name: transforms
        type: "List<TransformEntry>"
      - name: entry.spec
        type: string
        note: "id@version reference to a loaded TransformSpec"
      - name: entry.direction
        type: "enum: request, response"
        constraints: "required"
      - name: entry.match.path
        type: string
        note: "URL path pattern (glob wildcards)"
      - name: entry.match.method
        type: string
        constraints: "optional"
      - name: entry.match.content-type
        type: string
        constraints: "optional"

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
  - id: API-001-04
    method: reload
    note: "Hot-reload: re-parse specs, recompile, atomic swap (NFR-001-05)"
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
    inputs: [JsonNode, TransformContext]
    outputs: [JsonNode]
    note: "JsonNode is core-internal (bundled, relocated — ADR-0032). Not visible to adapters."

adapter_spi:
  - id: SPI-001-04
    method: wrapRequest
    inputs: [native]
    outputs: [Message]
  - id: SPI-001-05
    method: wrapResponse
    inputs: [native]
    outputs: [Message]
  - id: SPI-001-06
    method: applyChanges
    inputs: [Message, native]
    note: "Write Message changes back to native object"
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
    direction: response         # forward expression applied to response body
    match:
      path: "/json/*/authenticate"
      method: POST
  - spec: callback-prettify
    direction: request          # reverse expression applied to request body
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
- Prior Art Research (DSL Patterns): `docs/research/journeyforge-dsl-patterns.md`
- Transformation Patterns: `docs/research/transformation-patterns.md`
- PingAccess Plugin API: `docs/research/pingaccess-plugin-api.md`
- PingAM Authentication API: `docs/research/pingam-authentication-api.md`
- Gateway Candidates: `docs/research/gateway-candidates.md`
