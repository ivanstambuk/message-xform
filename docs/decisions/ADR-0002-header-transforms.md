# ADR-0002 – Declarative Header Transforms with Bidirectional JSLT Binding

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-04 defines the `Message` interface with header getters/setters, and G-001-03
states the engine operates on "body, headers, and status code." However, JSLT expressions
operate on `JsonNode` (the body), not on HTTP headers (`Map<String, List<String>>`).

Two concerns must be addressed:
1. **Header manipulation** — add, remove, rename headers (e.g., strip `X-Internal-*`).
2. **Header ↔ body injection** — reference header values inside the JSLT body expression
   (header-to-body), and extract body values into headers (body-to-header).

### Options Considered

- **Option A – Separate `headers` block with add/remove/rename + bidirectional binding** (chosen)
  - Declarative header operations + `$headers` read-only JSLT variable for header-to-body +
    dynamic `expr` in `add` for body-to-header.
  - Pros: clean separation of concerns, bidirectional bridge without polluting body JSON,
    consistent with Kong transformer pattern.
  - Cons: two mechanisms (declarative block + JSLT variable) — slightly more surface area.

- **Option B – Headers injected into JSLT body as `_headers`** (rejected)
  - Merge headers into the body JSON input under a reserved `_headers` key. JSLT operates
    on the merged object.
  - Pros: unified — one JSLT expression handles everything.
  - Cons: pollutes body input with header data, risk of `_headers` key collision with real
    body fields, cannot do declarative add/remove/rename without JSLT.

- **Option C – Headers are adapter-level only** (rejected)
  - No header transform support in core engine. Adapters handle headers natively.
  - Pros: simplest core.
  - Cons: breaks G-001-03 (full envelope), no portable header transforms across gateways.

Related ADRs:
- ADR-0003 – Status Code Transforms (mirrors this pattern)

## Decision

We adopt **Option A – Separate `headers` block with bidirectional JSLT binding**.

Header transforms use a hybrid approach:

1. **Declarative `headers` block** for add/remove/rename operations (glob patterns for removal).
2. **Read-only `$headers` variable** for header-to-body injection in JSLT expressions.
3. **Dynamic `add` values** using `expr` sub-keys — JSLT evaluated against the transformed
   body for body-to-header injection.

Processing order: read headers → bind `$headers` → evaluate JSLT body → apply declarative
header operations (remove → rename → static add → dynamic add).

## Consequences

Positive:
- Clean separation: body transforms use JSLT, header transforms use declarative rules.
- Bidirectional bridge: `$headers` for header-to-body, dynamic `expr` for body-to-header.
- Consistent with Kong/Apigee transformer patterns.

Negative / trade-offs:
- Multi-value headers: `$headers` exposes the first value only. Full multi-value
  access is available via `$headers_all` (ADR-0026) — normative in Feature 001.
- Dynamic header `expr` evaluated against the **transformed** body — could return non-string
  values that need coercion.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-10)
- Kong transformer pattern: `docs/research/transformation-patterns.md`
- Validating scenarios: S-001-33 (header-to-body), S-001-34 (body-to-header), S-001-35 (missing header)
