# ADR-0006 – Most-Specific-Wins Profile Match Resolution

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-05 defines profiles with `match` criteria (path glob, method, content-type) that
bind transform specs to URL patterns. When multiple **entries within a single profile**
match the same request (e.g., `/json/alpha/authenticate` matches both
`/json/*/authenticate` and `/json/alpha/*` in the same profile), the engine needs a
deterministic resolution strategy.

This is critical for production — ambiguous matching leads to unpredictable behaviour
that is nearly impossible to debug in live systems.

**Scope:** This ADR applies to match resolution **within a single profile** only.
Cross-profile routing (whether multiple profiles can apply to the same request) is
**product-defined** — determined by the gateway product's deployment model (e.g.,
PingAccess rule binding, Kong route/plugin attachment, context root vs per-operation
configuration). The core engine does not define or detect cross-profile conflicts.
See ADR-0023.

### Options Considered

- **Option A – First-match-wins by explicit priority** (rejected)
  - Profiles declare an integer `priority` field. Lowest number wins. Equal priority on
    overlapping patterns is a load-time error.
  - Pros: deterministic, simple mental model ("lowest wins"), used by Kong and Apigee.
  - Cons: requires coordination of priority numbers across profile authors. Priority
    numbers are arbitrary and hard to maintain as the number of profiles grows. Doesn't
    leverage the natural specificity of URL patterns.

- **Option B – Most-specific-wins (longest path / fewest wildcards)** (chosen)
  - The profile with the most specific match pattern wins. Specificity is measured by
    counting literal (non-wildcard) path segments. Ties are broken by constraint count.
  - Pros: automatic — leverages the natural specificity of URL patterns, no arbitrary
    numbers to coordinate, intuitive ("more specific wins"), matches HTTP router
    conventions (e.g., Go `http.ServeMux`, Spring MVC).
  - Cons: requires a well-defined specificity algorithm, edge cases with equal-length
    patterns.

- **Option C – Reject ambiguous matches at load time** (rejected)
  - If two profiles CAN match the same request, reject both at load time.
  - Pros: safest — zero runtime ambiguity.
  - Cons: overly restrictive — glob overlaps are common and intentional (e.g., a
    catch-all `/api/*` alongside specific `/api/auth/*`). High false positive rate
    would frustrate operators.

## Decision

We adopt **Option B – Most-specific-wins**.

Resolution algorithm:
1. **Specificity score**: count literal (non-wildcard) path segments in the match pattern.
   Higher score = more specific = wins.
   - `/json/alpha/authenticate` → 3 literals → score 3
   - `/json/*/authenticate` → 2 literals → score 2
   - `/json/*` → 1 literal → score 1
2. **Tie-breaking** (same specificity score):
   a. More `match` constraints (method, content-type) wins.
   b. If still tied → **load-time error** with diagnostic listing the conflicting
      profiles. Operator must resolve the ambiguity.
3. **Structured logging** (NFR-001-08): every matched profile MUST be logged with
   profile id, spec id@version, request path, and specificity score, so operators
   can trace exactly which profile was selected.

## Consequences

Positive:
- Intuitive resolution: more specific patterns naturally win without arbitrary numbers.
- Self-diagnosing: ambiguous ties are caught at load time, not at runtime.
- Structured match logging (NFR-001-08) ensures full traceability in production.
- Consistent with HTTP router conventions developers already know.

Negative / trade-offs:
- Specificity algorithm must be well-defined and documented — edge cases around
  path parameter styles (`:id` vs `*`) need consideration.
- Equal-specificity ties with equal constraints → load-time error. This is strict
  but necessary; operators must resolve ambiguity explicitly.

Follow-ups:
- NFR-001-08 added: structured JSON log line for every profile match.
- Consider a `--dry-run` mode that reports match resolution for a given request path
  without executing the transform, for operator debugging.
- Document the specificity scoring algorithm with examples in the operator guide.
- Cross-profile conflict handling is product-defined (ADR-0023).

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-05, NFR-001-08)
- Validating scenarios: S-001-44, S-001-45, S-001-46
