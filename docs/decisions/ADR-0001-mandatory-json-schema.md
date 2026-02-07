# ADR-0001: Mandatory Input/Output JSON Schema on Transform Specs

| Field | Value |
|-------|-------|
| Status | Accepted |
| Date | 2026-02-07 |
| Feature | F-001 – Message Transformation Engine |

## Context

Transform specs define JSLT expressions that map an input JSON shape to an output JSON
shape. Without a formal contract for what the input looks like and what the output should
produce, structural mismatches (e.g., upstream API renames a field) are only caught at
runtime in production — the expression silently produces `null` or missing fields.

JourneyForge (ADR-0027) supports optional inline JSON Schema 2020-12 on its transform
states. The question for message-xform was whether schemas should be mandatory, optional,
or deferred to a future version.

## Decision

**Every transform spec MUST declare `input.schema` and `output.schema` using JSON Schema
2020-12.** Schemas are required fields on the `TransformSpec` domain object.

At load time, the engine validates that schemas are syntactically valid JSON Schema.
At evaluation time, runtime schema validation is configurable:
- `strict` mode: validate input before evaluation, output after evaluation.
- `lenient` mode: skip runtime schema validation (production default for performance).

This adds `json-schema-validator` (e.g., `networknt/json-schema-validator`) to the
allowed core dependencies alongside Jackson, SnakeYAML, and JSLT.

## Consequences

- **Spec authors** must write JSON Schema for every transform spec. This raises the
  authorship barrier but produces self-documenting, contract-driven specs.
- **Deploy-time safety**: structural mismatches between the spec's assumptions and
  the actual upstream API are caught when the spec is loaded, not at runtime.
- **Dependency**: one additional library in the core module. The chosen library must
  be Jackson-native (operates on `JsonNode`) for consistency.
- **Future tooling**: schemas enable compatibility checking, diff reports, and
  automated migration tooling between spec versions.
- **NFR-001-02** updated to include JSON Schema validator in allowed dependencies.
- **FR-001-09** added to encode this requirement.
- **DO-001-02** (`TransformSpec`) updated with `inputSchema` and `outputSchema` fields.

## Related

- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-09, NFR-001-02)
- JourneyForge schema pattern: `docs/research/journeyforge-dsl-patterns.md`, lines 333–358
