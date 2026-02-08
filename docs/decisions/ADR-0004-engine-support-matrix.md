# ADR-0004 – Engine Support Matrix with Load-Time Capability Validation

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-02 defines a pluggable expression engine SPI with engines identified by string
ids (jslt, jolt, jq, jsonata, dataweave). Not all engines support the same operations:
JOLT can only do structural transforms (shift/default/remove) — it cannot evaluate
predicates, access `$headers`/`$status` variables, or support bidirectional specs.

Without an explicit capability matrix, a spec author could write `lang: jolt` with a
`when` predicate (FR-001-11), which would fail at runtime rather than at load time.

Other transformation engines define similar capability matrices that block JOLT from
predicate contexts. DataWeave is being open-sourced by
MuleSoft under BSD-3 license.

### Options Considered

- **Option A – Engine support matrix in spec** (chosen)
  - Define a capability matrix table in FR-001-02 listing each engine and its supported
    operations. Engine loads validate capabilities at spec load time.
  - Pros: load-time safety (catch "JOLT cannot do predicates" before runtime), clear
    authoring guidance, proven pattern from prior art research.
  - Cons: matrix must be maintained as engines evolve.

- **Option B – Per-engine in adapter specs** (rejected)
  - Each engine adapter documents its own capabilities in its own spec. No central matrix.
  - Pros: decentralized — each engine owns its documentation.
  - Cons: no single reference for spec authors, no cross-engine comparison, no load-time
    validation in core engine, easy to miss capability gaps.

Related ADRs:
- Prior art research: `docs/research/journeyforge-dsl-patterns.md`

## Decision

We adopt **Option A – Engine support matrix in spec**.

A formal matrix documents per-engine capabilities across five dimensions: body transform,
predicates (`when`), context variables (`$headers` / `$status`), bidirectional support,
and availability status.

The engine MUST validate at spec load time that the declared engine supports all
capabilities used by the spec. A spec using an unsupported capability MUST be rejected
with a clear diagnostic message (e.g., "engine 'jolt' does not support predicates —
use 'jslt' or 'jq'").

Baseline engine: `jslt` (always available, supports all capabilities).

## Consequences

Positive:
- Load-time safety: capability mismatches caught at deploy time, not runtime.
- Clear authoring guidance: one table to consult for engine capabilities.
- Consistent with proven approaches in existing transformation engines.

Negative / trade-offs:
- Matrix must be updated when new engines are added or existing engines gain capabilities.
- Each engine adapter is responsible for declaring its capabilities via the SPI.
- Future engines (jq, jsonata, dataweave) added as adapter plugins — the matrix must
  be extended.

Follow-ups:
- The `ExpressionEngine` SPI should be extended with a `capabilities()` method that
  returns supported operations, enabling dynamic matrix validation.
- Consider a CLI/lint tool that validates spec compatibility against engine capabilities.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-02, engine matrix)
- Prior art research: `docs/research/journeyforge-dsl-patterns.md`
- Validating scenarios: S-001-39 (JOLT predicate rejected), S-001-40 (JOLT $headers rejected)
