# ADR-0009 – JSLT as Default Expression Engine

Date: 2026-02-07 | Status: Accepted (retroactive)

> This ADR retroactively documents a foundational decision made during initial spec
> drafting. The decision is already in effect across the spec, scenarios, and test
> vectors. Created per Q-009 resolution.

## Context

The message-xform engine needs a JSON-to-JSON transformation language for expressing
field mappings, conditional logic, array reshaping, default values, and structural
transformations. The language is embedded in transform specs (`transform.expr`) and
evaluated per-request on the gateway hot path.

Six JSON transformation engines were evaluated for gateway-inline use. The evaluation
prioritized: readability (specs are read more than written), Jackson-native integration
(the engine uses `JsonNode` internally — see ADR-0011), conditional logic support,
structural reshaping capability, and production maturity.

### Options Considered

- **Option A – JSLT (Schibsted)** (chosen)
  - Template-style JSON transformation language. The spec looks like the desired output
    JSON, with expressions where values need to be computed. Native Jackson `JsonNode`
    in/out.
  - Production maturity: 9 billion transforms/day at Schibsted since 2018.
  - Features: `if/else` conditionals, `[for ...]` array iteration, `let` variable
    bindings, function definitions, `* : .` open-world passthrough.
  - Dependencies: Jackson only.
  - Pros:
    - Best readability — spec looks like the output JSON.
    - Native Jackson integration — zero serialization overhead with `JsonNode` body.
    - Rich conditional and array support covers gateway transformation use cases.
    - `* : .` passthrough enables forward-compatible specs (unknown fields preserved).
    - Compiled `Expression` objects are immutable and thread-safe.
    - `let` bindings enable staged computation within a single expression (ADR-0008).
  - Cons:
    - Niche language — smaller community than jq or JSONata.
    - No built-in schema validation (handled separately via ADR-0001).

- **Option B – JOLT** (rejected as default, available as alternative engine)
  - Structural transformation via operation chains (shift, default, remove, sort).
  - Pros: Well-known in Apache NiFi / Spring ecosystem.
  - Cons: No conditional logic, no `$headers`/`$status` support, poor readability for
    complex transforms, operation-based model is harder to reason about.

- **Option C – jq** (rejected as default, available as future engine)
  - Pipe-based JSON query and transformation language.
  - Pros: Powerful filtering and slicing. Strong community.
  - Cons: Not natively Jackson-compatible (`jackson-jq` adapter exists but is a preview).
    Readability for structural reshaping is worse than JSLT. Pipe syntax is less
    intuitive for non-jq users.

- **Option D – JSONata** (rejected as default, available as future engine)
  - Functional JSON query language, originally from IBM.
  - Pros: Powerful, supports variables and chaining.
  - Cons: Not Jackson-native (own internal model — requires serialization roundtrip).
    Smaller Java ecosystem than JSLT.

- **Option E – DataWeave (MuleSoft)** (rejected as default, available as future engine)
  - MuleSoft's transformation language, recently open-sourced under BSD-3.
  - Pros: Powerful conditional logic, pattern matching, passthrough via `-` operator.
    Strong in enterprise integration (MuleSoft Anypoint).
  - Cons: Not Jackson-native (own internal model — serialization roundtrip). Primarily
    associated with MuleSoft ecosystem. Open-source version is new and immature
    compared to JSLT.

- **Option F – JMESPath** (rejected)
  - JSON query language used in AWS CLI.
  - Pros: Simple query syntax.
  - Cons: No conditional logic, no structural reshaping, no passthrough — too limited
    for gateway transformation use cases.

Related ADRs:
- ADR-0004 – Engine Support Matrix (capability validation for all engines)
- ADR-0008 – Single Expression per Direction (JSLT `let` as staged computation)
- ADR-0010 – Pluggable Expression Engine SPI (JSLT is default but not the only option)

## Decision

We adopt **JSLT (Schibsted)** as the default and baseline expression engine.

JSLT is always enabled (`engines.jslt.enabled: true` is the default). Specs that omit
the `lang` key default to `lang: jslt`. JSLT is the only engine guaranteed to be
available in all deployments.

Alternative engines (JOLT, jq, JSONata, DataWeave) are available via the pluggable
Expression Engine SPI (ADR-0010) but are opt-in and may not support all capabilities
(see ADR-0004 engine support matrix).

### Evaluation Summary

| Criterion             | JSLT       | JOLT     | jq           | JSONata    | DataWeave  | JMESPath |
|-----------------------|:----------:|:--------:|:------------:|:----------:|:----------:|:--------:|
| Readability           | ⭐⭐⭐⭐⭐ | ⭐⭐     | ⭐⭐⭐⭐     | ⭐⭐⭐⭐   | ⭐⭐⭐⭐   | ⭐⭐⭐   |
| Jackson-native        | ✅         | ✅       | ✅ (adapter) | ❌         | ❌         | ❌       |
| Conditional logic     | ✅         | ❌       | ✅           | ✅         | ✅         | ❌       |
| Structural reshaping  | ✅         | ✅       | ✅           | ✅         | ✅         | ❌       |
| Passthrough (`* : .`) | ✅         | ❌       | manual       | ❌         | ✅ (`-`)   | ❌       |
| Production maturity   | 9B/day     | NiFi     | preview      | IBM z/OS   | MuleSoft   | AWS CLI  |
| Dependencies          | Jackson    | Jackson  | Jackson      | own model  | own model  | Jackson  |

Full comparison: `docs/research/expression-engine-evaluation.md`.

## Consequences

Positive:
- All spec examples, scenarios, and test vectors use JSLT as the default language.
- Spec authors get the most readable and capable engine out of the box.
- Zero serialization overhead — JSLT operates directly on `JsonNode` (ADR-0011).

Negative / trade-offs:
- Teams with existing JOLT or jq specs need to either convert to JSLT or register their
  preferred engine via the SPI.
- JSLT's smaller community means fewer third-party resources compared to jq.

Follow-ups:
- None — decision is already fully implemented in the spec.
