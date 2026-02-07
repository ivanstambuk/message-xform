# ADR-0011 – Jackson JsonNode as Internal Body Representation

Date: 2026-02-07 | Status: Accepted (retroactive)

> This ADR retroactively documents a foundational decision made during initial spec
> drafting. The decision is already in effect across the spec. Created per Q-009
> resolution.

## Context

The message-xform engine processes HTTP message bodies as JSON. The engine needs an
internal representation for the body that flows through the entire pipeline: adapter
wraps native message → engine evaluates expression → adapter applies result.

The internal body representation determines:
- Serialization overhead (conversion cost between adapter and engine)
- Expression engine compatibility (what the JSLT/JOLT/jq engine can consume/produce)
- API ergonomics (what `Message.getBodyAsJson()` returns, what `CompiledExpression.evaluate()` takes)

### Options Considered

- **Option A – Jackson `JsonNode`** (chosen)
  - The `Message` interface exposes `JsonNode getBodyAsJson()` and
    `void setBodyFromJson(JsonNode body)`. Expression engines accept and return `JsonNode`.
  - Pros:
    - JSLT operates natively on `JsonNode` — zero serialization overhead for the
      default engine (ADR-0009). JSLT's `Expression.apply(JsonNode)` returns `JsonNode`.
    - JOLT also operates on Jackson `JsonNode` — zero overhead for the second engine.
    - `jackson-jq` (jq adapter) operates on Jackson `JsonNode`.
    - Jackson is already a required dependency for YAML parsing (SnakeYAML integration).
    - `JsonNode` is immutable-ish and well-understood in the Java ecosystem.
    - Enables zero-copy passthrough: if no transformation matches, the original `JsonNode`
      passes through without re-serialization.
  - Cons:
    - Engines with their own internal models (JSONata, DataWeave) need to convert
      `JsonNode` ↔ their internal representation — adds a serialization roundtrip.
    - Locks the core API to Jackson — swapping JSON libraries would be a breaking change.

- **Option B – Raw `byte[]`** (rejected)
  - The `Message` interface works with raw bytes. Each engine parses bytes into its
    own internal model.
  - Pros:
    - Engine-agnostic — each engine handles its own parsing.
    - Avoids Jackson coupling in the core API.
  - Cons:
    - Every engine must parse JSON on every request — duplicated work. JSLT, JOLT, and
      jq all use Jackson internally anyway, so they'd all independently parse the same
      bytes into `JsonNode`.
    - No zero-copy passthrough: even non-matching messages would need to be serialized
      back to bytes.
    - Headers and status code would need separate handling (they can't be bytes).

- **Option C – `Map<String, Object>`** (rejected)
  - The `Message` interface returns a generic map representation of the JSON body.
  - Pros:
    - Jackson-independent API.
    - Simple, no special types.
  - Cons:
    - Loses type safety — numbers, booleans, nulls, and nested structures are all
      `Object`. Casting errors are discovered at runtime, not compile time.
    - JSLT, JOLT, and jq all need `JsonNode` internally — every engine would
      immediately convert `Map` → `JsonNode`, adding overhead for zero benefit.
    - No standard representation for JSON arrays (is it `List<Object>` or `Object[]`?).

- **Option D – Java POJOs** (rejected)
  - The engine operates on strongly-typed domain objects (e.g., `CallbackResponse`,
    `TokenResponse`).
  - Pros:
    - Compile-time type safety.
  - Cons:
    - Fundamentally incompatible with a generic transformation engine — the engine
      must handle arbitrary JSON shapes, not predefined classes. This option would
      require a POJO per input/output schema, defeating the purpose of declarative
      YAML specs.

Related ADRs:
- ADR-0009 – JSLT as Default Expression Engine (JSLT operates on `JsonNode`)
- ADR-0010 – Pluggable Expression Engine SPI (`CompiledExpression` interface uses `JsonNode`)

## Decision

We adopt **Jackson `JsonNode`** as the internal body representation.

The `Message` interface (FR-001-04, DO-001-01) exposes:

```java
JsonNode getBodyAsJson();
void setBodyFromJson(JsonNode body);
```

The `CompiledExpression` interface (FR-001-02, DO-001-04) uses:

```java
JsonNode evaluate(JsonNode input) throws ExpressionEvalException;
```

The raw `byte[]` ↔ `JsonNode` conversion is the **adapter's responsibility**. Gateway
adapters (PingAccess, PingGateway, standalone) parse the HTTP body into `JsonNode` on
entry and serialize back to the response stream on exit. The core engine never touches
raw bytes.

## Consequences

Positive:
- Zero serialization overhead for JSLT, JOLT, and jackson-jq — all native `JsonNode`.
- Consistent API across all expression engines and adapters.
- Jackson is already required for YAML config parsing — no new dependency.
- Zero-copy passthrough for non-matching messages.

Negative / trade-offs:
- Engines with non-Jackson internal models (JSONata, DataWeave) incur a conversion cost.
  This is acceptable because: (a) JSLT is the default engine and handles the majority
  of use cases, (b) the conversion cost is small relative to network I/O.
- Core API is coupled to Jackson. A future migration away from Jackson would require
  a major version bump. This is unlikely given Jackson's dominance in the Java JSON
  ecosystem.

Follow-ups:
- None — decision is already fully implemented in the spec (FR-001-04, DO-001-01,
  DO-001-04, SPI-001-03).
