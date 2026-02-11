# ADR-0003 – Declarative Status Code Transforms with Read-Only JSLT Binding

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-04 defines the `Message` interface with `getStatusCode()` / `setStatusCode()`,
and G-001-03 states the engine operates on "body, headers, and status code." Scenario
S-001-11 (spec.md, line 460) specifies "map upstream 200 + error body → 400 response."
However, no spec format defined how status code transforms are expressed.

Status code changes can be conditional ("only change to 400 if the body contains an
error field") or unconditional. The approach must handle both cases and be consistent
with the header pattern established in ADR-0002.

### Options Considered

- **Option A – `$status` JSLT variable + declarative `status` block** (chosen)
  - Read-only `$status` integer in JSLT context + declarative `status` block with `set`
    and optional `when` predicate evaluated against the transformed body.
  - Pros: consistent with ADR-0002 header pattern, no magic fields in body JSON,
    conditional and unconditional both supported cleanly.
  - Cons: two mechanisms (variable + block), `when` predicate is another expression site.

- **Option B – Magic `_status` field in body output** (rejected)
  - JSLT expression sets a `_status` field in the output JSON; engine reads and removes it.
  - Pros: no new block — status change is part of the JSLT expression.
  - Cons: `_status` key collision risk, pollutes body output, non-obvious side effects,
    cannot set status without producing a body field.

- **Option C – Adapter-level only** (rejected)
  - No status code transform in core engine. Adapters handle status natively.
  - Pros: simplest core.
  - Cons: breaks G-001-03 (full envelope), non-portable across gateways, loses the key
    use case (200 + error body → 400).

Related ADRs:
- ADR-0002 – Header Transforms (mirrors this pattern)

## Decision

We adopt **Option A – `$status` JSLT variable + declarative `status` block**.

1. **Read-only `$status` variable** — integer bound before JSLT body evaluation.
2. **Declarative `status` block** with `set` (target code) and optional `when` (JSLT
   predicate evaluated against the transformed body).

Processing order: bind `$status` → evaluate JSLT body → apply header operations →
evaluate `when` predicate → set status code.

## Consequences

Positive:
- Consistent pattern: `$status` mirrors `$headers`. Same hybrid approach across all
  three envelope dimensions (body, headers, status).
- Conditional logic via `when` enables the key use case without magic fields or output
  pollution.
- Clean: status code is never mixed into the body JSON.

Negative / trade-offs:
- `when` predicate is another expression evaluation site — adds a small runtime cost.
- Predicate evaluates against the **transformed** body, which may confuse authors who
  expect it to reference the original input.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-11)
- Header pattern precedent: ADR-0002
- Validating scenarios: S-001-36 (conditional), S-001-37 ($status in body), S-001-38 (unconditional)
