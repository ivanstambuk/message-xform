# ADR-0017 – `$status` is Null for Request Transforms

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-11 defines `$status` as a read-only integer variable bound before JSLT body
evaluation. However, request-phase transforms have no HTTP status code — the response
hasn't happened yet. The spec had an inconsistent mention of `$status returns -1 for
request transforms` but no normative contract.

With ADR-0016 (direction-agnostic specs), the same spec can now be bound to both
request and response directions, making this ambiguity a practical problem.

### Options Considered

- **Option A – `$status` is `null` for request transforms** (chosen)

  `$status` is always bound, but its value is `null` during request-phase transforms.
  Authors guard with `if ($status)`.

  ```yaml
  transform:
    lang: jslt
    expr: |
      {
        "body": .data,
        "statusKnown": $status != null,
        "httpStatus": if ($status) $status else "N/A"
      }
  ```

  Pros:
  - ✅ No special cases — `$status` always exists, value reflects reality
  - ✅ JSLT handles `null` naturally
  - ✅ Preserves direction-agnostic spec reusability (ADR-0016)

  Cons:
  - ❌ Silent `null` requires documentation — authors must know to guard

- **Option B – Reference is a load-time error** (rejected)

  Kills reusability — contradicts ADR-0016.

- **Option C – Default to `0`** (rejected)

  Misleading sentinel — `0` is not a valid HTTP status code.

### Comparison Matrix

| Aspect              | A: null           | B: Load-time error | C: Default 0     |
|---------------------|-------------------|--------------------|-------------------|
| Spec reusability    | ✅ Both directions | ❌ Response only    | ✅ Both directions |
| ADR-0016 alignment  | ✅                 | ❌                  | ✅                 |
| JSLT naturalness    | ✅ null idiomatic  | ❌ Cross-validation | ⚠️ 0 misleading   |

Related ADRs:
- ADR-0003 – Status Code Transforms
- ADR-0016 – Unidirectional Spec Direction (direction-agnostic specs)

## Decision

We adopt **Option A – `$status` is `null` for request transforms**.

- `$status` is always bound in the expression evaluation context.
- For response transforms: `$status` is an integer (the HTTP status code).
- For request transforms: `$status` is `null` (no status exists yet).
- Authors who reference `$status` in direction-agnostic specs SHOULD guard with
  `if ($status)` or `$status != null`.

## Consequences

Positive:
- Direction-agnostic specs (ADR-0016) work seamlessly with `$status`.
- No load-time restrictions — maximum reusability.
- `null` is the natural JSLT representation of "not available."

Negative / trade-offs:
- Authors must be aware `$status` can be `null`. FR-001-11 documentation must call
  this out prominently.

Follow-ups:
- Update FR-001-11 in spec.md with normative `$status` = `null` for requests.
- Fix inconsistent `-1` reference in spec.md line 886.
