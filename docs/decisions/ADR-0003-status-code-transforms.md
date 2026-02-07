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

## Decision

Status code transforms use the same **hybrid approach** as headers (ADR-0002):

1. **Read-only `$status` variable** — integer bound into the JSLT evaluation context
   before body expression evaluation. Allows status-aware body transforms (e.g.,
   `"success": $status < 400`).
2. **Declarative `status` block** with `set` (target code) and optional `when` (JSLT
   predicate evaluated against the transformed body). If `when` is omitted, the status
   is set unconditionally.

Processing order: bind `$status` → evaluate JSLT body → apply header operations →
evaluate `when` predicate → set status code.

## Consequences

- **Consistent pattern**: `$status` mirrors `$headers` — same hybrid variable +
  declarative block approach across all three envelope dimensions (body, headers, status).
- **Conditional logic**: `when` predicate enables the key use case: "200 + error body →
  400" without magic fields or output pollution.
- **No magic fields**: Status code is never mixed into the body JSON. No `_status`
  field collision risk.
- **Predicate scope**: `when` evaluates against the **transformed** body, so predicates
  can reference fields produced by the JSLT expression.
- **FR-001-11** added to encode this requirement.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-11)
- Header pattern precedent: ADR-0002
- Validating scenarios: S-001-36, S-001-37, S-001-38
