# ADR-0036 – Conditional Response Routing via Profile Match Predicates

Date: 2026-02-15 | Status: Accepted

## Context

Transform profiles (FR-001-05, ADR-0006) currently route messages based on **envelope
metadata only** — path glob, HTTP method, content-type, and direction. This is
sufficient for request-direction transforms (the request shape is known a priori) but
inadequate for response-direction transforms where the backend may return **different
response shapes** for the same URL and method.

Real-world cases that require response-aware routing:
- **Error vs. success routing:** Backend returns 200 with a success body OR 4xx with an
  error body. Each needs a different transform spec.
- **Polymorphic responses:** Same endpoint returns different JSON structures
  (e.g., `{role: "admin", ...}` vs `{role: "user", ...}`). Each shape needs a
  different transform.
- **Status-conditional transforms:** Apply transforms only to specific HTTP status
  codes or classes (e.g., only rewrite 5xx bodies, leave 2xx untouched).

Without this capability, operators must write a single, complex JSLT expression with
conditionals that effectively re-implements a routing layer inside the expression,
defeating the purpose of profile-based routing.

### Options Considered

- **Option A – Two new profile match predicates: `match.status` + `match.when`**
  (chosen)

  Extend the profile `match` block with two new optional fields:
  - `match.status` — envelope predicate matching HTTP status codes (exact, class,
    range, negation, list).
  - `match.when` — body predicate using a compiled JSLT expression evaluated against
    the response body.

  Evaluation order (short-circuit):
  1. Direction → 2. Path → 3. Method → 4. Content-type → 5. Status → 6. `when`

  Pros:
  - ✅ Composable with existing routing — adds two optional fields, zero existing
    profile changes required.
  - ✅ Cheap first: envelope predicates (direction, path, method, content-type, status)
    are evaluated before the expensive body predicate.
  - ✅ Body is parsed only when needed — short-circuit prevents unnecessary JSON parsing.
  - ✅ Status matching is pure metadata — no body access needed.
  - ✅ `when` reuses existing JSLT infrastructure (`CompiledExpression` SPI).

  Cons:
  - ❌ `when` predicates require early body parsing before spec matching.
  - ❌ Two entries with identical envelope but different `when` predicates can't be
    statically validated for mutual exclusivity (runtime concern).

- **Option B – Conditional routing inside the spec expression** (rejected)

  No profile changes. Operators use JSLT `if`/`else` within a single spec.

  Rejected: conflates routing with transformation. Produces monolithic expressions
  that are hard to test, version, and maintain independently.

- **Option C – Response-direction sub-profiles** (rejected)

  A new `response-profile` concept with its own resolution semantics.

  Rejected: introduces a separate routing abstraction, doubles the matching
  infrastructure, and breaks the "one profile per deployment context" model.

- **Option D – Spec-level `match.status`** (rejected)

  Add status matching to the spec's own `match` block (alongside the existing
  `content-type` prerequisite from ADR-0015).

  Rejected: specs should be routing-agnostic (ADR-0015, ADR-0016). A spec declares
  what it *can process*, not *when* it should be applied. Routing decisions belong
  in profiles.

### Comparison Matrix

| Aspect                      | A: Profile predicates | B: Spec conditionals | C: Sub-profiles | D: Spec match |
|-----------------------------|----------------------|---------------------|-----------------|---------------|
| Routing/transform separation | ✅ Clean              | ❌ Conflated          | ✅ Clean         | ❌ Spec does routing |
| Backward compatibility      | ✅ Additive           | ✅ No changes         | ❌ New concept    | ⚠️ Spec schema change |
| Testability                 | ✅ Per-spec testing   | ❌ Monolithic         | ✅ Per-spec       | ✅ Per-spec    |
| Implementation complexity   | Medium               | Low                  | High             | Medium        |
| ADR-0015/0016 alignment     | ✅ Profile routes     | ⚠️ Implicit          | ⚠️ New layer      | ❌ Violates    |

Related ADRs:
- ADR-0003 – Status Code Transforms (`status.when` predicate is spec-level; this ADR's
  `match.status` is profile-level — see §Disambiguation below)
- ADR-0006 – Profile Match Resolution (specificity scoring updated)
- ADR-0012 – Pipeline Chaining (body predicate timing clarified)
- ADR-0015 – Spec Match Prerequisite (spec = capability, profile = routing)
- ADR-0016 – Profile Direction Binding (direction is a profile concern)
- ADR-0027 – Route the Input (body predicates use original body)

## Decision

We adopt **Option A – Two new profile match predicates**, implemented in two phases.

### Phase 1: `match.status` — Envelope Predicate

Profile entries MAY include `match.status` to match HTTP status codes on
**response-direction** entries only. Supported patterns:

| Pattern | Syntax | Example | Matches |
|---------|--------|---------|---------|
| Exact | integer or string | `status: 404` | 404 only |
| Class | `Nxx` | `status: "4xx"` | 400–499 |
| Range | `"low-high"` | `status: "400-499"` | 400 through 499 inclusive |
| Negation | `!` prefix | `status: "!5xx"` | Everything except 500–599 |
| List | YAML array | `status: [200, 201]` | 200 or 201 |

Rules:
1. `match.status` on a `direction: request` entry → **load-time error**.
2. Absent `match.status` → matches any status code (backward compatible).
3. Invalid patterns (outside 100–599, `low > high`, bad syntax) → load-time error.
4. Status patterns participate in specificity scoring via weighted contribution
   (see §Specificity below).

**YAML type handling:** The parser MUST accept both quoted strings (`status: "404"`)
and unquoted integers (`status: 404`), as well as YAML arrays. The `optionalString()`
helper must NOT be used — it silently ignores integer nodes.

### Phase 2: `match.when` — Body Predicate

Profile entries MAY include `match.when` with a compiled expression evaluated against
the **original** message body. Allowed on **both** request and response directions.

```yaml
match:
  path: "/api/users/**"
  status: "2xx"
  when:
    lang: jslt
    expr: '.role == "admin"'
```

Rules:
1. `match.when` uses `{lang, expr}` block format. The `lang` field is mandatory
   because profiles don't inherit a default expression engine (unlike specs).
2. Only expression engines that produce boolean/truthful results are valid (JSLT).
   JOLT in `match.when` → load-time error.
3. The expression is compiled at load time via the `EngineRegistry` SPI.
4. `when` is evaluated against the **original body** (before any transforms), following
   the "route the input" convention (ADR-0027).
5. Non-JSON body with `when` predicate → entry treated as non-matching (debug log).
6. `when` evaluation failure → entry treated as non-matching (warn log, fail-safe).

**DSL format note:** The `{lang, expr}` block format differs from the existing
`status.when` plain-string format in specs. This is intentional — specs inherit their
`lang` from the `transform` block; profiles do not. A future retrofit to support block
format in `status.when` is recommended but out of scope.

### Specificity and Ambiguity

Status patterns contribute **weighted** values to `ProfileEntry.constraintCount()`:

| Pattern Type | Weight | Rationale |
|-------------|--------|-----------|
| Exact `404` | +2 | Matches exactly one code |
| Range `400-410` | +2 | Small set |
| Class `4xx` | +1 | 100-code range |
| Negation `!404` | +1 | Broad exclusion |
| `when` predicate | +1 | Body discrimination |

This ensures `status: 404` beats `status: "4xx"` for a 404 response, which is the
intuitive resolution.

**Ambiguity with `when` predicates:** Entries with `when` predicates are **exempt from
load-time ambiguity rejection** (ADR-0006 §2b). The engine cannot statically determine
whether two JSLT predicates are mutually exclusive. At runtime, if multiple entries pass
all checks including `when`, they pipeline per ADR-0012.

### Evaluation Order

The matching chain guarantees this fixed evaluation order:

```
direction → path → method → content-type → status → when
     (cheap envelope checks first)          (expensive body check last)
```

This order is a **contract** — operators can rely on it for reasoning about which
checks are skipped.

### `status.when` vs `match.status` Disambiguation

These are **different features** at different architectural layers:

| | `status.when` (ADR-0003) | `match.status` (this ADR) |
|---|---|---|
| **Layer** | Spec (transform definition) | Profile (routing) |
| **Purpose** | Conditionally apply a status code override | Route to the correct spec |
| **Evaluated against** | Transformed body | Incoming HTTP status code |
| **Timing** | After body transform | Before body transform |
| **Scope** | Single spec's status block | Profile entry selection |

### Body Parsing Optimization

Body parsing for `when` predicates uses a conditional early-parse strategy:
- If NO profile entries have `when` predicates → body is parsed after matching
  (current behavior, zero overhead).
- If ANY entry has a `when` predicate → body is parsed before matching. The pre-parsed
  `JsonNode` is reused for all subsequent processing (body transform, schema validation,
  URL rewriting, pipeline input) — eliminating up to 3 redundant JSON parses per request.

The pre-parse decision is driven by `TransformProfile.hasWhenPredicates()`, a computed
method on the profile record.

### Pipeline Interaction

When `when`-bearing entries form a pipeline (multiple match), each `when` predicate
is evaluated against the **original body**, not intermediate pipeline output. This
follows ADR-0027's "route the input" convention — the routing decision is based on what
arrived, not what was produced partway through the pipeline.

### Logging

New structured log fields for conditional routing:

| Field | Value | When |
|-------|-------|------|
| `match.status_pattern` | Raw status pattern string | Status-filtered entry matches |
| `match.when_result` | `true`/`false`/`skipped`/`error` | `when` predicate evaluated |
| `match.body_parsed` | `true`/`false` | Early body parse occurred |
| `match.candidates_after_status` | integer | After status filtering, before `when` |

## Consequences

Positive:
- **Full response-aware routing** — operators can route based on status codes and body
  content without complex conditional expressions.
- **Zero impact on existing profiles** — both `match.status` and `match.when` are
  optional, defaulting to match-all.
- **Cheap-first evaluation** — status matching is a metadata comparison (no I/O).
  Body predicates are evaluated only for entries that pass all cheaper checks.
- **Pre-parsed body reuse** — the JSON parsing cost is paid at most once per request.
- **Consistent with ADR-0015/0016** — routing remains a profile concern; specs remain
  routing-agnostic.
- **Reuses existing infrastructure** — `CompiledExpression` SPI, `TransformContext`,
  JSLT engine.

Negative / trade-offs:
- Early body parsing adds latency on the first request that uses `when` predicates.
  Mitigated by reusing the parsed body for the transform itself (net zero on typical
  requests).
- `when` predicates can't be statically validated for mutual exclusivity. Operators
  must ensure their predicates are discriminating. Partially mitigated by structured
  logging showing `when_result` for each evaluated entry.
- `ProfileParser` gains a new dependency on `EngineRegistry` for compiling `when`
  expressions. This is a constructor-level API change, backward-compatible via
  overloading.
- `TransformProfile.hasWhenPredicates()` is a computed method (not cached) due to
  Java record constraints. O(n) over a small entries list per request — acceptable.
- The `{lang, expr}` block format in `match.when` diverges from the plain-string
  `status.when` in specs. This inconsistency is intentional (profiles lack a default
  engine) but may confuse authors familiar with the spec format.

## References

- Research: `docs/research/conditional-response-routing.md` (Revision 4, retired — content captured in this ADR, spec FR-001-15/16, and scenarios S-001-87–100)
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-15, FR-001-16)
- Related ADRs: ADR-0003 (disambiguation), ADR-0006 (specificity update), ADR-0012
  (pipeline timing), ADR-0015 (spec vs profile routing), ADR-0027 (original body)
- Validating scenarios: S-001-87 through S-001-100
