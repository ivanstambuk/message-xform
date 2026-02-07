# ADR-0001 – Mandatory Input/Output JSON Schema on Transform Specs

Date: 2026-02-07 | Status: Accepted

## Context

Transform specs define JSLT expressions that map an input JSON shape to an output JSON
shape. Without a formal contract for what the input looks like and what the output should
produce, structural mismatches (e.g., upstream API renames a field) are only caught at
runtime in production — the expression silently produces `null` or missing fields.

JourneyForge (ADR-0027) supports optional inline JSON Schema 2020-12 on its transform
states. The question for message-xform was whether schemas should be mandatory, optional,
or deferred to a future version.

### Options Considered

- **Option A – Mandatory schemas** (chosen)
  - Every transform spec MUST declare `input.schema` and `output.schema` using JSON
    Schema 2020-12. Schemas are required fields on `TransformSpec`.
  - Pros: deploy-time safety, self-documenting specs, enables future tooling
    (compatibility checks, migration diffs).
  - Cons: raises the authoring barrier — every spec needs two extra schema blocks.

- **Option B – Optional schemas** (rejected)
  - Schemas are optional. If present, validated at load time; if absent, skipped.
  - Pros: lower authoring friction for simple specs.
  - Cons: no guarantee of deploy-time safety — specs without schemas silently break
    at runtime. Defeats the purpose of contract-driven design.

- **Option C – Deferred to v2** (rejected)
  - No schema support in v1, add it later.
  - Pros: fastest to implement.
  - Cons: retrofitting mandatory schemas later is a breaking change to all specs.

Related ADRs:
- JourneyForge ADR-0027 – Expression Engines and `lang` Extensibility

## Decision

We adopt **Option A – Mandatory schemas**.

Every transform spec MUST declare `input.schema` and `output.schema` using JSON Schema
2020-12. Schemas are required fields on the `TransformSpec` domain object.

At load time, the engine validates that schemas are syntactically valid JSON Schema.
At evaluation time, runtime schema validation is configurable:
- `strict` mode: validate input before evaluation, output after evaluation.
- `lenient` mode: skip runtime schema validation (production default for performance).

This adds `json-schema-validator` (e.g., `networknt/json-schema-validator`) to the
allowed core dependencies alongside Jackson, SnakeYAML, and JSLT.

## Consequences

Positive:
- Spec authors produce self-documenting, contract-driven specs.
- Deploy-time safety: structural mismatches caught when the spec is loaded, not at runtime.
- Future tooling: schemas enable compatibility checking, diff reports, and automated
  migration tooling between spec versions.

Negative / trade-offs:
- Every spec now requires two JSON Schema blocks, raising the authoring barrier.
- One additional library (`json-schema-validator`) in the core module. Must be
  Jackson-native (operates on `JsonNode`) for consistency.

Follow-ups:
- NFR-001-02 updated to include JSON Schema validator in allowed dependencies.
- FR-001-09 added to encode this requirement.
- DO-001-02 (`TransformSpec`) updated with `inputSchema` and `outputSchema` fields.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-09, NFR-001-02)
- JourneyForge schema pattern: `docs/research/journeyforge-dsl-patterns.md`, lines 333–358
