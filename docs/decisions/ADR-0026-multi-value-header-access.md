# ADR-0026 – Multi-Value Header Access via `$headers_all`

Date: 2026-02-08 | Status: Accepted

## Context

The `$headers` JSLT variable (FR-001-10, ADR-0002) exposes each header as a single
string — the first value only. The underlying `Message` interface stores headers as
`Map<String, List<String>>`, so all values are available internally, but the JSLT
binding loses multi-value information.

Real-world HTTP headers that carry multiple values:
- `Set-Cookie` — each cookie is a separate header line; browsers rely on receiving
  all of them.
- `X-Forwarded-For` — chains multiple IP addresses through proxy layers.
- `Via`, `Cache-Control`, `Accept` — can carry comma-separated or multi-line values.

ADR-0002 originally stated: "Multi-value headers: `$headers` exposes the first value
only in v1. Full multi-value support is deferred to a future extension."

The project owner has decided that multi-value header support is **not deferred** —
it is a normative requirement of Feature 001.

### Options Considered

- **Option A – Add `$headers_all` variable** (chosen)
  - Define a new `$headers_all` context variable where every value is an array of
    strings. `$headers` remains unchanged (first-value strings) for ergonomics.
  - Pros: backward-compatible, zero breaking changes, clear naming convention,
    adapters can prepare because the shape is defined.
  - Cons: two variables for headers — slight cognitive overhead.

- **Option B – Defer entirely** (rejected)
  - Ship single-value `$headers` only. Workaround: adapter-level header injection.
  - Pros: zero effort.
  - Cons: `Set-Cookie` transforms silently lose data. Future API shape unknown.

- **Option C – Change `$headers` to always use arrays** (rejected)
  - Redefine `$headers` so every value is `["value"]`.
  - Pros: single truthful model.
  - Cons: breaking change to every existing scenario and JSLT expression.
    `$headers."X-Request-ID"[0]` is ugly for the 95% single-value case.

Related ADRs:
- ADR-0002 – Header Transforms (original `$headers` definition)
- ADR-0021 – Query Params and Cookies in Context (similar context variable pattern)

## Decision

We adopt **Option A – Add `$headers_all` as a normative Feature 001 context variable**.

### Contract

- **Variable name:** `$headers_all`
- **Type:** `JsonNode` (object)
- **Shape:** Keys are header names (case-preserved). Values are **arrays of strings**,
  always — even for single-value headers.
- **Binding point:** Same as `$headers` — bound by the engine before JSLT body
  expression evaluation.
- **Availability:** All engines that support `$headers` also support `$headers_all`
  (same engine capability column in the support matrix).

### Examples

```jslt
// Single-value header
$headers."Content-Type"         // → "application/json"
$headers_all."Content-Type"     // → ["application/json"]

// Multi-value header (Set-Cookie with 2 cookies)
$headers."Set-Cookie"           // → "session=abc"  (first only)
$headers_all."Set-Cookie"       // → ["session=abc", "lang=en"]

// X-Forwarded-For with 3 IPs
$headers."X-Forwarded-For"      // → "1.1.1.1"
$headers_all."X-Forwarded-For"  // → ["1.1.1.1", "2.2.2.2", "3.3.3.3"]

// Missing header
$headers_all."X-Missing"        // → null (not empty array)
```

### Processing Order (updated)

1. Engine reads headers from `Message.getHeaders()` (which returns
   `Map<String, List<String>>`).
2. Binds `$headers` — first value per header name (existing, unchanged).
3. Binds `$headers_all` — all values per header name as JSON arrays.
4. JSLT body expression evaluates with both variables available.
5. Declarative header operations applied.

### TransformContext Update

`TransformContext` gains a new field:

| Field | Type | JSLT Variable | Description |
|-------|------|---------------|-------------|
| `headers` | `JsonNode` | `$headers` | First value per header (string) — unchanged |
| `headersAll` | `JsonNode` | `$headers_all` | All values per header (array of strings) — **new** |

## Consequences

Positive:
- No data loss for multi-value headers — adapters and specs can access all values.
- Backward-compatible — `$headers` unchanged, existing expressions work as-is.
- Consistent with the `$queryParams` / `$cookies` pattern (ADR-0021).
- Enables `Set-Cookie`, `X-Forwarded-For`, `Via` transforms in JSLT.

Negative / trade-offs:
- Two header variables (`$headers` and `$headers_all`) adds minor cognitive overhead.
  Mitigated by clear naming: `$headers` = simple/first, `$headers_all` = full/array.
- Engine must bind both variables at evaluation time — negligible performance impact.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-10)
- Q-023: Multi-value header access (resolved by this ADR)
