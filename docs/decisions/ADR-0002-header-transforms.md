# ADR-0002 – Declarative Header Transforms with Read-Only JSLT Binding

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-04 defines the `Message` interface with header getters/setters, and G-001-03
states the engine operates on "body, headers, and status code." However, JSLT expressions
operate on `JsonNode` (the body), not on HTTP headers (`Map<String, List<String>>`).

Two concerns must be addressed:
1. **Header manipulation** — add, remove, rename headers (e.g., strip `X-Internal-*`).
2. **Header-to-body injection** — reference header values inside the JSLT body expression
   (e.g., inject `X-Request-ID` into the response body).

## Decision

Header transforms use a **hybrid approach**:

1. **Declarative `headers` block** in the transform spec for add/remove/rename operations,
   processed independently of the body expression. Glob patterns are supported for removal.
2. **Read-only `$headers` variable** bound into the JSLT evaluation context, allowing body
   expressions to reference header values via `$headers."X-Request-ID"`. This uses JSLT's
   native external variable binding — no body input pollution.

Processing order: read headers → bind `$headers` → evaluate JSLT body → apply declarative
header operations (remove → rename → add).

## Consequences

- **Clean separation**: body transforms use JSLT, header transforms use declarative rules.
  No mixing of concerns inside the expression.
- **Header-to-body bridge**: `$headers` binding enables cross-domain injection without
  merging headers into the body JSON input.
- **No body-to-header bridge** (by design): JSLT output cannot set headers. Header values
  are static or derived from the original request/response headers only.
- **Multi-value headers**: `$headers` exposes the first value only in v1. Full multi-value
  support is deferred to a future extension.
- **FR-001-10** added to encode this requirement.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-10)
- Kong transformer pattern: `docs/research/transformation-patterns.md`
