# ADR-0010 – Pluggable Expression Engine SPI

Date: 2026-02-07 | Status: Accepted (retroactive)

> This ADR retroactively documents a foundational decision made during initial spec
> drafting. The decision is already in effect across the spec. Created per Q-009
> resolution.

## Context

The message-xform engine uses JSLT as its default expression language (ADR-0009). However,
different organizations have existing transformation logic in other languages (JOLT specs
from NiFi, jq scripts, DataWeave from MuleSoft). Requiring all users to rewrite their
existing transformations in JSLT would block adoption.

The question was whether the engine should be JSLT-only (monolithic), or support a
pluggable model where alternative expression engines can be registered.

### Options Considered

- **Option A – Pluggable Expression Engine SPI** (chosen)
  - Define a Java SPI (`ExpressionEngine` + `CompiledExpression`) that any expression
    engine can implement. Engines register via a string id (e.g., `jslt`, `jolt`, `jq`).
    Specs declare their engine via `lang: <engineId>`. The engine registry resolves the
    id at load time.
  - Inspired by prior art in pluggable expression engine models (see `docs/research/journeyforge-dsl-patterns.md`).
  - Pros:
    - Users can bring existing JOLT/jq/JSONata/DataWeave specs without rewriting.
    - Each engine is a separate module — no bloated core dependency tree.
    - Engine support matrix (ADR-0004) validates capabilities at load time.
    - Enables gradual migration: run legacy JOLT specs alongside new JSLT specs.
  - Cons:
    - More complex engine implementation than JSLT-only.
    - Not all engines support the same capabilities (headers, status, predicates) —
      requires capability validation at load time (ADR-0004).

- **Option B – JSLT-only (monolithic)** (rejected)
  - The engine only supports JSLT. No SPI, no engine registry.
  - Pros:
    - Simpler implementation — no registry, no capability matrix.
    - Guaranteed feature parity across all specs.
  - Cons:
    - Forces all users to rewrite existing transformation logic in JSLT.
    - Blocks adoption for teams with large JOLT/jq/DataWeave libraries.
    - Removes future extensibility — adding a new engine requires core changes.

Related ADRs:
- ADR-0004 – Engine Support Matrix (capability validation per engine)
- ADR-0008 – Single Expression per Direction (mixed-engine via profile chaining)
- ADR-0009 – JSLT as Default Expression Engine

## Decision

We adopt **Option A – Pluggable Expression Engine SPI**.

The core engine defines two interfaces:

```java
public interface ExpressionEngine {
    String id();
    CompiledExpression compile(String expr) throws ExpressionCompileException;
}

public interface CompiledExpression {
    JsonNode evaluate(JsonNode input, TransformContext context)
        throws ExpressionEvalException;
}

public interface TransformContext {
    JsonNode getHeaders();       // $headers — read-only
    int getStatusCode();         // $status — -1 for request transforms
    String getRequestPath();     // request path
    String getRequestMethod();   // HTTP method
}
```

Key design properties:
1. **Pure functions**: Engine implementations MUST be pure (no I/O), thread-safe, and
   respect configurable time/size limits (NFR-001-07).
2. **Compile-once, evaluate-many**: `compile()` is called once at spec load time;
   `evaluate()` is called per-request on the hot path.
3. **Engine id resolution**: The registry maps string ids to engine instances. Unknown
   ids are rejected at load time.
4. **Capability validation**: The engine support matrix (ADR-0004) defines per-engine
   capabilities. Specs that use capabilities their engine doesn't support are rejected
   at load time.
5. **Separate modules**: Each engine adapter is a separate Maven/Gradle module
   (`engine-jslt`, `engine-jolt`, etc.) with its own dependencies.

## Consequences

Positive:
- Users with existing JOLT, jq, or DataWeave specs can use them directly.
- Core engine stays lean — one Jackson dependency, no expression-language bloat.
- New engines can be added without modifying the core module.
- Profile-level chaining (ADR-0008) enables mixed-engine composition.

Negative / trade-offs:
- Capability asymmetry: not all engines support predicates, `$headers`, `$status`, or
  bidirectional transforms. The engine support matrix (ADR-0004) and load-time
  validation mitigate this, but users must be aware of per-engine limitations.
- SPI implementations must handle `JsonNode` input/output. Engines with their own
  internal models (e.g., JSONata) incur a serialization roundtrip.

