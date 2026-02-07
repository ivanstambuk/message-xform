# ADR-0019 – Sensitive Field Marking via Top-Level `sensitive` List

Date: 2026-02-07 | Status: Accepted

## Context

NFR-001-06 requires the engine to NOT log, cache, or inspect content of fields marked
as `sensitive` in the spec. However, no YAML syntax was defined for how to mark fields
as sensitive.

### Options Considered

- **Option A – Top-level `sensitive` list with JSON path expressions** (chosen)

  A new optional `sensitive` block in the spec YAML lists JSON path expressions
  pointing to fields that must be redacted from logs.

  ```yaml
  id: callback-prettify
  version: "1.0.0"

  sensitive:
    - "$.authId"
    - "$.callbacks[*].input[*].value"

  transform:
    lang: jslt
    expr: |
      { "authId": .authId, "fields": [for (.callbacks) { "value": .input[0].value }] }
  ```

  Pros:
  - ✅ Simple, explicit, and visible — easy to audit
  - ✅ JSON path syntax is well-understood and precise
  - ✅ Separate from schema — security concern, not validation concern
  - ✅ Works with or without input/output schemas

  Cons:
  - ❌ Paths must be kept in sync with schema changes

- **Option B – JSON Schema `x-sensitive` annotation** (rejected)

  Mixes security concerns into the validation schema. Requires schemas to be present
  (but schemas are optional per FR-001-01). Buried deep in schema — hard to audit.

- **Option C – Mark at profile level** (rejected)

  Violates spec self-containment. Every profile binding must remember to add markings —
  error-prone. Inconsistent with ADR-0015 (spec = capability).

### Comparison Matrix

| Aspect                | A: Spec `sensitive` list | B: Schema `x-sensitive` | C: Profile level   |
|-----------------------|--------------------------|-------------------------|---------------------|
| Self-contained spec   | ✅                        | ⚠️ Only with schema     | ❌                   |
| Works without schema  | ✅                        | ❌                       | ✅                   |
| Visibility / audit    | ✅ Top-level              | ❌ Buried                | ⚠️ Spread            |
| ADR-0015 alignment    | ✅                        | ⚠️                      | ❌                   |

Related ADRs:
- ADR-0015 – Spec-Level Match as Prerequisite (spec = capability pattern)

## Decision

We adopt **Option A – top-level `sensitive` list with JSON path expressions**.

The spec YAML gains an optional `sensitive` block:

```yaml
sensitive:
  - "$.fieldPath"
  - "$.nested[*].secretField"
```

Concrete rules:
1. `sensitive` is an optional top-level array of JSON path expressions (string).
2. Paths use the JSON Path dot-notation (RFC 9535) with wildcard `[*]` support.
3. The engine extracts the list at spec load time and builds a redaction filter.
4. Any field matching a sensitive path MUST be redacted in structured logs (replaced
   with `"[REDACTED]"` or equivalent) and MUST NOT appear in cache keys or
   telemetry payloads.
5. The engine MUST validate path syntax at load time — invalid paths are a load-time
   error.
6. The `sensitive` block applies to both input and output data flowing through the
   spec, regardless of direction (ADR-0016).

## Consequences

Positive:
- NFR-001-06 now has a concrete, implementable syntax.
- Security-sensitive fields are visible at the top level — easy to audit.
- Works independently of schemas (which are optional).

Negative / trade-offs:
- Paths must be manually kept in sync with schema evolution. Mitigated by load-time
  validation (a path that matches nothing could produce a warning).

Follow-ups:
- Update FR-001-01 to include `sensitive` in the spec schema definition.
- Update NFR-001-06 to reference this syntax.
- Add scenarios for sensitive field redaction.
