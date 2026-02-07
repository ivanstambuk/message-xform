# ADR-0004 – Engine Support Matrix with Load-Time Capability Validation

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-02 defines a pluggable expression engine SPI with engines identified by string
ids (jslt, jolt, jq, jsonata, dataweave). Not all engines support the same operations:
JOLT can only do structural transforms (shift/default/remove) — it cannot evaluate
predicates, access `$headers`/`$status` variables, or support bidirectional specs.

Without an explicit capability matrix, a spec author could write `lang: jolt` with a
`when` predicate (FR-001-11), which would fail at runtime rather than at load time.

JourneyForge (ADR-0027) defines a similar matrix that blocks JOLT from predicate
contexts. DataWeave is JourneyForge's canonical engine; it is being open-sourced by
MuleSoft under BSD-3 license.

## Decision

Define a formal engine support matrix in FR-001-02 documenting per-engine capabilities
across five dimensions: body transform, predicates (`when`), context variables
(`$headers` / `$status`), and bidirectional support.

The engine MUST validate at spec load time that the declared engine supports all
capabilities used by the spec. A spec using an unsupported capability MUST be rejected
with a clear diagnostic message (e.g., "engine 'jolt' does not support predicates").

Baseline engine: `jslt` (always available, supports all capabilities).

## Consequences

- **Load-time safety**: Capability mismatches caught at deploy time, not runtime.
- **Clear authoring guidance**: Spec authors can consult a single table for engine
  capabilities, avoiding trial-and-error.
- **Maintenance cost**: Matrix must be updated when new engines are added or existing
  engines gain capabilities. Each engine adapter is responsible for declaring its
  capabilities via the SPI.
- **Future engines** (jq, jsonata, dataweave): added as adapter plugins with their
  capabilities declared in the matrix.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-02, engine matrix)
- JourneyForge ADR-0027: Expression Engines and `lang` Extensibility
- Validating scenario: S-001-39 (JOLT with unsupported predicate rejected)
