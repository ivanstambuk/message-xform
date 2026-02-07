# JourneyForge DSL — Research Notes for message-xform

> Researched: 2026-02-07
> Context: Evaluating patterns from JourneyForge that could be leveraged by message-xform.

---

## Overview

JourneyForge is a **spec-first API journey orchestrator** that defines state-machine
workflows in YAML. Its DSL supports multiple state types including `task` (HTTP calls),
`choice` (branching), `transform` (data shaping), `succeed`/`fail` (terminal), and more.

The key insight for message-xform is that JourneyForge's `kind: Api` mode and its
`transform` state already implement a **zero-step workflow** pattern — essentially a
request/response transformation pipeline with no I/O, which is precisely what
message-xform does at its core.

---

## Key Patterns

### 1. `kind: Api` — Synchronous Single-Request-Response

JourneyForge has two modes:
- `kind: Journey` — long-lived, multi-step workflows with waits and webhooks
- `kind: Api` — **synchronous, stateless HTTP endpoints** with no journey ID

```yaml
apiVersion: v1
kind: Api
metadata:
  name: my-transform
  version: 0.1.0
spec:
  bindings:
    http:
      route:
        path: /apis/my-transform
        method: POST
  input:
    schema: { type: object, required: [userId], properties: { userId: { type: string } } }
  output:
    schema: { type: object, required: [result], properties: { result: { type: string } } }
  start: doTransform
  states:
    doTransform:
      type: transform
      transform:
        mapper:
          lang: dataweave
          expr: |
            { result: context.userId ++ "-processed" }
        target:
          kind: var
        resultVar: out
      next: done
    done:
      type: succeed
      outputVar: out
```

**Relevance to message-xform:** A `kind: Api` with only `transform` states and no HTTP
calls is effectively a **pure transformation endpoint**. This is the "zero-step workflow"
pattern the user mentioned. The HTTP request body becomes the `context`, transforms run,
and the result is the HTTP response body.

### 2. Transform State — The Core Building Block

JourneyForge's `transform` state is:

```yaml
type: transform
transform:
  mapper:
    lang: dataweave    # or jsonata, jolt, jq
    expr: |
      {
        id: context.id,
        status: 'OK'
      }
  target:
    kind: context      # context | var | attributes
    path: data.enriched
  resultVar: enriched
next: <stateId>
```

Key design decisions:
- **Pluggable expression engines** via `lang` selector (dataweave, jsonata, jolt, jq)
- **Pure evaluation** — transforms compute values, no I/O
- **Target selection** — write to context root, a subtree, or a variable
- **Chainable** — output of one transform feeds the next state

### 3. Pluggable Expression Engine SPI (ADR-0027)

JourneyForge defines a clean SPI for expression engines:

```java
public interface ExpressionEnginePlugin {
    String id();                    // "dataweave", "jsonata", "jolt", "jq"
    int majorVersion();

    ExpressionResult evaluate(
        String expr,
        ExpressionEvaluationContext ctx
    ) throws ExpressionEngineException;
}
```

With a consistent evaluation context:
- `context` — mutable journey context (= the message body in our case)
- `payload` — current request/response body
- `platform` — read-only metadata (environment, config)
- `error` — error object (only in error-handling)

**Key constraints:**
- Expressions MUST be **pure** (no I/O, no side effects)
- Engines MUST enforce **timeouts** (`maxEvalMs`) and **output size limits** (`maxOutputBytes`)
- Engine configs live under `plugins.expressions.engines.<id>`

### 4. Engine Support Matrix

Not all engines are valid in all contexts:

| Context | DataWeave | JSONata | JOLT | jq |
|---------|-----------|---------|------|-----|
| Predicates (choice/branching) | ✅ | ✅ | ❌ | ✅ |
| Transform/mapper | ✅ | ✅ | ✅ | ✅ |
| Task mappers | ✅ | ✅ | ✅ | ✅ |
| Error normalisers | ✅ | ✅ | ✅ | ✅ |
| Status expressions | ✅ | ✅ | ❌ | ✅ |

JOLT is deliberately blocked from predicate contexts because it returns data, not
booleans. This is a wise architectural distinction that message-xform should adopt.

### 5. Transform Pipeline Example (Zero I/O)

JourneyForge has a canonical `transform-pipeline` example — a pure transform chain
with zero HTTP calls:

```yaml
# transform-pipeline.journey.yaml
apiVersion: v1
kind: Journey
metadata:
  name: transform-pipeline
  version: 0.1.0
spec:
  start: normalise
  states:
    normalise:
      type: transform
      transform:
        mapper:
          lang: dataweave
          expr: |
            {
              order: {
                id: context.id,
                amount: context.amount,
                currency: context.currency default: 'USD'
              }
            }
        target: { kind: context, path: "" }
      next: enrich

    enrich:
      type: transform
      transform:
        mapper:
          lang: dataweave
          expr: |
            context ++ {
              order: context.order ++ {
                tax: (context.amount * 0.25)
              }
            }
        target: { kind: context, path: "" }
      next: project

    project:
      type: transform
      transform:
        mapper:
          lang: dataweave
          expr: |
            {
              id: context.order.id,
              total: context.order.amount + context.order.tax,
              currency: context.order.currency
            }
        target: { kind: var }
        resultVar: summary
      next: done

    done:
      type: succeed
      outputVar: summary
```

This is a 3-stage pipeline: normalise → enrich → project. The pattern is:
1. Each stage reads from `context`
2. Each stage writes its result back (to context or a var)
3. The final stage's result becomes the output

### 6. Reusable Mappers (`spec.mappers` + `mapperRef`)

JourneyForge allows defining mappers once and reusing them:

```yaml
spec:
  mappers:
    buildProfile:
      lang: dataweave
      expr: |
        { id: context.user.id, email: context.remote.body.email }

# Usage:
type: transform
transform:
  mapperRef: buildProfile
  target: { kind: var }
  resultVar: profile
```

This avoids duplicating the same expression across multiple states.

---

## What message-xform Can Leverage

### A. The Expression Engine Plugin Model

**Adopt the `ExpressionEnginePlugin` SPI pattern.** Instead of message-xform inventing
its own pipeline operations (shift, default, remove, derive, etc.), consider:

1. Define a `TransformEngine` SPI similar to JourneyForge's `ExpressionEnginePlugin`
2. Allow pluggable engines: JOLT, JSONata, jq, DataWeave, or a custom DSL
3. Each transform spec selects its engine via `lang`

This means message-xform's pipeline could look like:

```yaml
id: callback-prettify
version: "1.0.0"
pipeline:
  - lang: jolt
    expr: |
      [{ "operation": "shift", "spec": { ... } }]
  - lang: jsonata
    expr: |
      $ ~> | fields | { "sensitive": type = "password" } |
```

**Trade-off:** This defers the "what operations are available" question to the
expression engine. JOLT provides shift/default/remove internally;
JSONata provides everything inline. message-xform wouldn't need its own operation
vocabulary — it delegates to the engine.

**Counter-argument:** message-xform's own operation vocabulary (shift, default, remove,
derive) gives better validation, error messages, and tooling than opaque engine
expressions. A middle ground is to support both: a built-in operation vocabulary for
simple cases AND pluggable engines for complex ones.

### B. The `kind: Api` / Zero-Step Pattern

A JourneyForge `kind: Api` definition with only `transform` states is functionally
identical to what message-xform does:
- Accepts an HTTP request
- Transforms the body through a pipeline
- Returns the transformed body as an HTTP response

**Could message-xform adopt the JourneyForge DSL directly?** In theory, yes — write
transform profiles as `kind: Api` journey specs and execute them via JourneyForge's
runtime. This would give message-xform:
- Schema validation (input/output schemas via JSON Schema 2020-12)
- HTTP binding (path, method)
- Error mapping (apiResponses, Problem Details)
- Expression engine pluggability

**However,** JourneyForge is designed for **workflows**, not **gateway inline
transformation**. Key differences:
1. JourneyForge runs as a service; message-xform should run **inside** a gateway
2. JourneyForge manages journey instances; message-xform is stateless
3. JourneyForge expects a JSON context object; message-xform operates on raw HTTP
   envelopes (body + headers + status)
4. JourneyForge's overhead (state machine, persistence, API surface) is unnecessary
   for inline transformation

### C. Reusable Mappers

message-xform SHOULD adopt the `mapperRef` pattern for spec reuse. Define common
transformations once, reference them in profiles:

```yaml
# specs/common-mappers.yaml
mappers:
  strip-internal-headers:
    lang: builtin
    operation: remove
    spec:
      headers: ["X-Internal-*", "X-Debug-*"]

  add-gateway-headers:
    lang: builtin
    operation: add
    spec:
      headers:
        X-Transformed-By: message-xform
        X-Transform-Version: "${spec.version}"
```

### D. Engine Configuration / Limits Model

message-xform SHOULD adopt JourneyForge's expression engine configuration pattern:

```yaml
plugins:
  expressions:
    defaults:
      maxEvalMs: 50       # stricter than JourneyForge — gateway latency budget
      maxOutputBytes: 1048576  # 1MB
    engines:
      jolt:
        maxEvalMs: 100
      jsonata:
        maxEvalMs: 50
```

This is especially important for gateway-inline execution where latency matters.

### E. Schema Validation Pattern

JourneyForge's inline JSON Schema 2020-12 for input/output validation is directly
applicable to message-xform:

```yaml
id: callback-prettify
version: "1.0.0"
input:
  schema:
    type: object
    required: [callbacks]
    properties:
      callbacks: { type: array }
      authId: { type: string }
output:
  schema:
    type: object
    required: [fields]
    properties:
      fields: { type: array }
      type: { type: string }
```

This enables compile-time validation that the transform spec is structurally sound
before deployment.

---

## Verdict: What to Adopt vs. What to Skip

| Pattern | Adopt? | Rationale |
|---------|--------|-----------|
| Expression Engine Plugin SPI | ✅ Adopt | Clean extensibility; same JVM ecosystem |
| Pure expression constraint | ✅ Adopt | Essential for gateway-inline safety |
| Engine config / limits | ✅ Adopt | Critical for latency-sensitive gateways |
| Engine support matrix | ✅ Adopt | JOLT can't do predicates; wise constraint |
| Reusable mappers (`mapperRef`) | ✅ Adopt | Reduces duplication in profiles |
| Input/output schema validation | ✅ Adopt | Compile-time safety for specs |
| `kind: Api` DSL | ⚠️ Partial | Inspiration for spec shape, but too heavy for inline transforms |
| JourneyForge runtime | ❌ Skip | Workflow engine overhead inappropriate for gateway-inline |
| State machine model | ❌ Skip | Sequential pipeline is simpler and sufficient |
| Journey persistence / IDs | ❌ Skip | message-xform is stateless per-request |
| Compensation / lifecycle | ❌ Skip | Irrelevant for stateless transformation |

---

## Design Implications for message-xform

If we adopt the Expression Engine SPI pattern, message-xform's pipeline model changes:

### Before (custom operation vocabulary only)
```yaml
pipeline:
  - operation: shift
    spec: { ... }
  - operation: default
    spec: { ... }
  - operation: derive
    spec: { ... }
```

### After (pluggable engines + optional built-in operations)
```yaml
pipeline:
  # Option A: Use a pluggable engine (JOLT, JSONata, jq)
  - lang: jolt
    expr: |
      [{ "operation": "shift", "spec": { ... } }]

  # Option B: Use the built-in mini-DSL for simple cases
  - lang: builtin
    operation: default
    spec:
      type: "challenge"

  # Option C: Mix engines — JOLT for structure, JSONata for logic
  - lang: jsonata
    expr: |
      $ ~> | fields | { "sensitive": type = "password" } |
```

This gives message-xform the best of both worlds:
1. A **simple built-in** for common operations (add/remove/rename/default)
2. **Pluggable engines** for complex transformations (JOLT shifts, JSONata logic)
3. The ability to **mix engines** in a single pipeline

The SPI from ADR-0027 is directly reusable (same Java + Jackson ecosystem), and the
engine config model (`maxEvalMs`, `maxOutputBytes`) is essential.

---

*Status: COMPLETE — JourneyForge research done*
