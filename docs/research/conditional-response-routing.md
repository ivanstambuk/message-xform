# Design Proposal: Conditional Response Routing

Date: 2026-02-15 | Status: **Accepted** | Revision: 4

## Problem Statement

The current profile routing DSL supports matching on **path**, **method**, **content-type**,
and **direction**. These are all **envelope metadata** — available before the engine
parses the message body. This works well for request routing (where the path and method
are the primary discriminators) but falls short for **response routing**, where the key
discriminators are the **HTTP status code** and the **response body shape**.

### Real-World Use Cases

1. **Error vs. success response rewriting.** An API returns `200 OK` with the normal
   payload or `4xx/5xx` with an error payload. The operator needs different transforms
   for each case — e.g., enrich the error payload with a correlation ID and override the
   status to `502`, while reshaping the success payload to a leaner frontend contract.

2. **Polymorphic endpoints.** A single endpoint returns different JSON shapes depending
   on a discriminator field (e.g., `type: "admin"` vs `type: "user"`). Each shape needs
   a different transform spec targeting its unique fields.

3. **Status class routing.** Route `2xx` responses to a success prettifier and `4xx`/`5xx`
   responses to an error normalizer — without using JSLT if/else logic crammed into a
   single expression.

4. **Conditional status override.** The existing `status.when` predicate allows
   conditional status changes within a *single spec*, but there's no way to select
   *which spec* runs based on the incoming status code.

### Current Workarounds and Their Limitations

| Workaround | Limitation |
|---|---|
| JSLT `if`/`else` in a single spec | Works for simple cases but doesn't scale — a single expression doing error + success handling becomes unwieldy. Can't use different headers/status/URL rules per branch. |
| `content-type` routing | Only works if the backend returns different content types for errors (`application/problem+json`) vs. success (`application/json`). Many APIs don't. |
| `status.when` predicate | Only controls whether a *status override* fires. Can't control which *body transform* or *header operations* run. |

## Design Principles

1. **Profile concern, not spec concern.** Routing decisions belong in the profile
   (ADR-0015, ADR-0016). Specs remain portable, direction-agnostic, pure functions.
2. **Lazy evaluation.** Status matching is cheap (integer comparison). Body-predicate
   matching requires JSON parsing but the body is already parsed for transformation.
   No extra parse cost.
3. **Composable with existing features.** Status matching, body-predicate matching,
   path/method/content-type matching, pipeline chaining — all combine freely.
4. **Fail-safe defaults.** No new match criteria → existing behaviour unchanged.
   Missing match fields → match-all (same semantics as today).
5. **No engine API changes.** The engine's `transform(Message, Direction, Context)` API
   is unchanged. All changes are in ProfileEntry model + ProfileMatcher + ProfileParser.
6. **Reuse existing vocabulary.** `when` is already an established keyword in the DSL
   (`status.when`, `url.method.when`). The new `match.when` reuses this vocabulary at
   the profile layer, maintaining consistency.

## Proposed DSL Extension

### 1. `match.status` — Status Code Pattern Matching

Add an optional `status` field to the profile entry `match` block. Supports:
- **Exact code:** `200`, `404`, `502`
- **Class pattern:** `2xx`, `4xx`, `5xx` (first digit match)
- **Range:** `400-499` (inclusive)
- **List:** `[200, 201, 204]` (any of)
- **Negation:** `!404`, `!5xx` (NOT matching) — see §Negation Patterns below

```yaml
transforms:
  # ── Error response transform ──
  - spec: error-normalizer@1.0.0
    direction: response
    match:
      path: "/api/users/**"
      method: POST
      status: "4xx"                    # matches 400–499

  # ── Success response transform ──
  - spec: user-prettifier@1.0.0
    direction: response
    match:
      path: "/api/users/**"
      method: POST
      status: "2xx"                    # matches 200–299
```

**Semantics:**

- `status` is **only evaluated for response-direction entries**. For request-direction
  entries, `status` is rejected at load time (requests don't have status codes).
- When `match.status` is absent → matches any status code (backward-compatible default).
- `status` participates in specificity scoring: an entry with `status` has +1
  constraint count (used for tie-breaking per ADR-0006).
- **Status specificity hierarchy** (for tie-breaking when multiple status patterns match
  the same code): exact (`404`) > range (`400-499`) > class (`4xx`) > absent (match-all).
  See §Specificity Implications.

**Why status matching is efficient:** The status code is already on the `Message`
envelope (`message.statusCode()`). ProfileMatcher already reads `message.contentType()`
from the Message — adding `message.statusCode()` is zero-cost.

### 2. `match.when` — Body-Predicate Matching

Add an optional `when` field to the profile entry `match` block. This is a JSLT
expression evaluated against the **original** (pre-transform) body. It returns a boolean;
the entry only matches if the predicate evaluates to `true`.

**IMPORTANT — `when` is a reused keyword, not new.** It already exists in the spec-level
DSL (`status.when`, `url.method.when`). This proposal extends `when` to a new location
(`match.when` in profiles) while preserving its semantic meaning: "only apply if this
predicate is true."

#### DSL Format: Block vs. String

The existing `status.when` and `url.method.when` use a **plain string** format:
```yaml
# Existing spec-level status.when (plain string — implicitly JSLT)
status:
  set: 502
  when: '.severity == "critical"'
```

The new `match.when` uses a **block format** with explicit `lang` + `expr`:
```yaml
# New profile-level match.when (block format)
match:
  path: "/api/users/**"
  when:
    lang: jslt
    expr: '.role == "admin"'
```

**Rationale for block format:** The spec-level `when` can assume the spec's
default engine (resolved from the `lang` field on the spec root). Profile entries
don't have a default engine — the profile doesn't declare a `lang`. The block format
makes the engine explicit. This also future-proofs for expression engines other than
JSLT that may support boolean predicates.

**NOTE — Retrofit opportunity:** The existing `status.when` and `url.method.when`
should ideally accept BOTH plain string (backward-compatible shorthand assuming JSLT)
and block format for consistency. This is out of scope for this proposal but should
be tracked as a follow-up.

```yaml
transforms:
  # ── Admin user response ──
  - spec: admin-prettifier@1.0.0
    direction: response
    match:
      path: "/api/users/**"
      status: "2xx"
      when:
        lang: jslt
        expr: '.role == "admin"'

  # ── Regular user response ──
  - spec: user-prettifier@1.0.0
    direction: response
    match:
      path: "/api/users/**"
      status: "2xx"
      when:
        lang: jslt
        expr: '.role != "admin"'
```

**Semantics:**

- `when` is evaluated **after** path/method/content-type/direction/status matching
  (short-circuit: if envelope metadata doesn't match, `when` is never evaluated).
- `when` evaluates against the **original** (pre-transform) body — same convention
  as `url.path.expr` (ADR-0027: "route the input").
- The `when` expression receives the full `TransformContext` available to body
  expressions: `$headers`, `$headers_all`, `$status`, `$queryParams`, `$cookies`,
  `$session`. **NOTE:** This diverges from `status.when`, which currently passes
  `TransformContext.empty()` (no context variables). The `match.when` design is
  intentionally richer because routing predicates often need to discriminate on
  multiple signals (e.g., body shape + header values). See §`status.when` Context Gap.
- `when` predicate failure (evaluation error) → entry does NOT match (fail-safe).
  A warning is logged.
- `when` is **optional**. When absent → no body predicate filtering (backward-compatible).
- `when` participation in specificity: an entry with `when` has +1 constraint count.
- `when` is allowed on **both request and response** direction entries. The body is
  already parsed for transformation regardless of direction. See §Request-Direction
  `when`.

**Performance consideration:** Body-predicate evaluation requires JSON parsing.
However, in the response path, the body will be parsed anyway for the body transform.
The ProfileMatcher can receive the already-parsed `JsonNode` to avoid double parsing.
In practice, most profiles won't use `when` — the short-circuit on envelope metadata
means `when` is only evaluated for entries that already passed path/method/status checks.

### Negation Patterns

Status patterns support a `!` prefix for negation:

```yaml
# Apply to everything EXCEPT 404
- spec: generic-transform@1.0.0
  direction: response
  match:
    path: "/api/legacy/**"
    status: "!404"

# Apply to all non-success responses
- spec: error-normalizer@1.0.0
  direction: response
  match:
    path: "/api/**"
    status: "!2xx"
```

Negation is implemented as a `StatusPattern.Not` wrapper:
```java
record Not(StatusPattern inner) implements StatusPattern {
    @Override
    public boolean matches(int statusCode) {
        return !inner.matches(statusCode);
    }
}
```

**Rationale:** Without negation, matching "everything except 404" requires enumerating
`[100-403, 405-599]`, which is error-prone. Negation patterns are common in production
gateway configurations.

### Combined Example: Full Polymorphic Response Routing

```yaml
profile: user-api
version: "1.0.0"
description: "Polymorphic response routing for /api/users"

transforms:
  # Request: always rewrite the body for the backend
  - spec: user-request-rewrite@1.0.0
    direction: request
    match:
      path: "/api/users/**"
      method: POST

  # Response: error case
  - spec: error-normalizer@1.0.0
    direction: response
    match:
      path: "/api/users/**"
      method: POST
      status: "4xx"

  # Response: admin user (200 with role=admin)
  - spec: admin-user-transform@1.0.0
    direction: response
    match:
      path: "/api/users/**"
      method: POST
      status: "2xx"
      when:
        lang: jslt
        expr: '.role == "admin"'

  # Response: regular user (200 with role != admin)
  - spec: regular-user-transform@1.0.0
    direction: response
    match:
      path: "/api/users/**"
      method: POST
      status: "2xx"
      when:
        lang: jslt
        expr: '.role != "admin"'

  # Response: 5xx server error
  - spec: server-error-transform@1.0.0
    direction: response
    match:
      path: "/api/users/**"
      status: "5xx"
```

### 3. Status-Only Matching (No Body Transform)

Operators may want to change only the status code without touching the body. The
existing `status.set` in a spec works for this, but with the new `match.status`, we
can now route to a minimal spec that only overrides the status:

```yaml
# Spec: just override status, identity body transform
id: remap-404-to-200
version: "1.0.0"
transform:
  lang: jslt
  expr: '.'          # identity — pass body through unchanged
status:
  set: 200
```

```yaml
# Profile: only apply when backend returns 404
- spec: remap-404-to-200@1.0.0
  direction: response
  match:
    path: "/api/legacy/**"
    status: 404
```

## Specificity Implications

### Status Pattern Specificity

ADR-0006 uses constraint count (method + content-type) for tie-breaking. Adding
`match.status` introduces a new question: **not all status patterns are equally
specific.** `status: 404` is more specific than `status: "4xx"`, which is more
specific than no status constraint.

**Proposed specificity hierarchy:**

| Pattern | Constraint Count Contribution | Rationale |
|---------|--:|---|
| `status: 404` (exact) | +2 | Most specific — matches exactly one status code |
| `status: "400-410"` (range) | +2 | Nearly exact — matches a small set |
| `status: "4xx"` (class) | +1 | Class-level — matches a 100-code range |
| `status: "!404"` (negation) | +1 | Broad exclusion — matches almost everything |
| absent (match-all) | 0 | Matches any status — no additional specificity |

**Why weighted contribution instead of flat +1:** Consider this profile:

```yaml
# Entry A — catch-all for any error
- spec: generic-error@1.0.0
  direction: response
  match:
    path: "/api/**"
    status: "4xx"

# Entry B — specific handler for 404
- spec: not-found-error@1.0.0
  direction: response
  match:
    path: "/api/**"
    status: 404
```

If both contribute +1 to constraint count, they tie for a 404 response. With weighted
contributions, `status: 404` (+2) beats `status: "4xx"` (+1), which is the correct
and intuitive resolution.

**Implementation note:** The `ProfileEntry.constraintCount()` method is updated to
use weighted scoring:

```java
public int constraintCount() {
    int count = 0;
    if (method != null) count++;
    if (contentType != null) count++;
    if (statusPattern != null) count += statusPattern.specificityWeight();
    if (whenPredicate != null) count++;
    return count;
}
```

Where `StatusPattern.specificityWeight()` returns:
- `Exact` → 2
- `Range` → 2
- `Class` → 1
- `Not` → 1
- `AnyOf` → max weight of its children

### Ambiguity Detection with `when` Predicates

ADR-0006 states that equal specificity + equal constraints → **load-time error**.
With `match.when`, this rule needs refinement.

**Problem:** Two entries with identical path + status but *different* `when` predicates
have the same specificityScore and constraintCount. ADR-0006 would reject them at load
time as ambiguous. But they are *intentionally discriminating* — their `when` predicates
are meant to route different body shapes to different specs.

```yaml
# These two entries are NOT ambiguous — they discriminate on body shape
- spec: admin-transform@1.0.0
  direction: response
  match:
    path: "/api/users/**"
    status: "2xx"
    when: { lang: jslt, expr: '.role == "admin"' }

- spec: user-transform@1.0.0
  direction: response
  match:
    path: "/api/users/**"
    status: "2xx"
    when: { lang: jslt, expr: '.role != "admin"' }
```

**Decision:** Entries with `when` predicates are **exempt from load-time ambiguity
rejection**. The reason is fundamental: `when` predicates are evaluated at runtime,
not load time. The engine cannot statically determine whether two predicates are
mutually exclusive (this would require solving the halting problem for JSLT
expressions). Instead:

- **At load time:** If two entries have equal specificity + constraints AND both have
  `when` predicates → **accept** (warn, don't reject). The predicates are expected
  to be discriminating. If neither has a `when` predicate → **reject** (current
  ADR-0006 behaviour).
- **At runtime:** If multiple entries pass all checks including `when`, they pipeline
  per ADR-0012. If this is undesirable, the operator must ensure their predicates are
  mutually exclusive.
- **Documentation:** Strongly recommend mutually exclusive `when` predicates. Provide
  examples of common patterns (field equality, field presence, boolean flags).

**NOTE:** ADR-0006 ambiguity detection is *not currently implemented* in
`ProfileMatcher` — it only sorts by specificity. The load-time rejection is prescribed
by the ADR but not yet enforced in code. When implementing this proposal, the
ambiguity detection should be added to `ProfileParser` during profile loading,
applying these refined rules.

## Interaction with Existing Features

### Pipeline Chaining (ADR-0012) + Status/When Matching

ADR-0012 states that when multiple entries match the same request, they form an
ordered pipeline. With conditional routing, the interaction requires clarification:

**Scenario:** Two entries match on path + status BUT have different `when` predicates.

```yaml
- spec: enricher@1.0.0
  direction: response
  match:
    path: "/api/data/**"
    status: "2xx"
    when: { lang: jslt, expr: '.data != null' }

- spec: formatter@1.0.0
  direction: response
  match:
    path: "/api/data/**"
    status: "2xx"
    when: { lang: jslt, expr: '.format == "pretty"' }
```

If a response has *both* `.data != null` AND `.format == "pretty"`, both entries match.
Per ADR-0012, they pipeline: enricher → formatter in declaration order. This is correct
and intentional — the predicates are NOT mutually exclusive in this case.

**KEY INVARIANT:** `when` predicates are always evaluated against the **original**
(pre-transform) body, even in a pipeline. The enricher's output does NOT affect the
formatter's `when` evaluation. This is critical for correctness — if `when` predicates
were evaluated against the intermediate pipeline state, the formatter's predicate
might fail because the enricher removed `.format`.

**Implementation:** In `ProfileMatcher.findMatches()`, all entries are evaluated
against the original body. The matched list is returned and then passed to
`transformChain()` which feeds each step's *output body* to the next step's
*transform expression* (not its `when` predicate). The `when` predicate is already
evaluated and done at match time.

### `status.when` (Spec-Level) vs. `match.status` (Profile-Level)

The existing `status.when` in `StatusSpec` (ADR-0003) and the new `match.status` in
the profile are different mechanisms at different layers:

| Aspect | `status.when` (spec) | `match.status` (profile) |
|--------|-----------|-------------|
| **Layer** | Spec-level | Profile-level |
| **Purpose** | Conditionally override the status *after* transform | Route to a spec *before* transform |
| **Input** | Evaluated against the **transformed** body | Evaluated against the **incoming** status code |
| **Effect** | Sets a new status code (or keeps original) | Includes/excludes the entry from matching |
| **Can control body transform?** | No — body transform already ran | Yes — controls *which* spec runs |

These features are **composable, not redundant**:

```yaml
# Profile: route to error spec when backend returns 4xx
- spec: error-normalizer@1.0.0
  direction: response
  match:
    path: "/api/**"
    status: "4xx"                    # profile-level: WHICH spec runs

# Spec: error-normalizer transforms the error body and conditionally sets status
status:
  set: 502
  when:
    lang: jslt
    expr: '.severity == "critical"'  # spec-level: WHETHER to override status
```

In this example, `match.status` controls routing (only 4xx responses reach this spec),
and `status.when` fine-tunes the status override within the spec (only critical errors
become 502).

### Non-JSON Body Handling

**Gap identified in v1:** The proposal didn't address what happens when `match.when`
is specified but the response body is not JSON (e.g., `text/html`, `application/xml`,
or empty body).

**Decision:** If the body cannot be parsed as JSON, `match.when` evaluation:
1. **Logs a debug message** (with the content-type and entry details).
2. **Treats the entry as non-matching** (fail-safe, same as evaluation error).

This is consistent with the fail-safe principle and with how the engine already handles
non-JSON bodies (it skips body transforms for non-JSON content per the spec prerequisite
filter, ADR-0015).

**Implementation detail — `bodyToJson()` throws, not returns null:** ⚠️ The v1/v2
proposal incorrectly stated that `bodyToJson()` returns null for non-JSON bodies.
Actually, `bodyToJson()` **throws `IllegalArgumentException`** on non-JSON input
(see `TransformEngine.java:74`). It only returns a null node for empty/null bodies.

This means the Phase 2 early-parse logic MUST catch `IllegalArgumentException`:
```java
JsonNode parsedBody = null;
if (profile.hasWhenPredicates()) {
    try {
        parsedBody = bodyToJson(message.body());
    } catch (IllegalArgumentException e) {
        // Non-JSON body — when predicates cannot evaluate.
        // Entries without when predicates can still match.
        LOG.debug("Body is not JSON — when predicates will not match: {}",
            e.getMessage());
        parsedBody = null;  // explicit: treated as non-JSON
    }
}
```
The `ProfileMatcher` treats `parsedBody == null` as "no entries with `when` predicates
can match."

### Hot Reload Interaction

**Gap identified in v1:** When a profile is hot-reloaded (NFR-001-05), what happens
to in-flight requests matched against the old profile?

**Answer:** This is already handled by the existing `registryRef.get()` snapshot
pattern in `transformInternal()`. The in-flight request captures the registry snapshot
at the start of processing. Even if a reload swaps in a new registry (with updated
profile entries including new `status`/`when` constraints), the in-flight request
continues using the old snapshot. No race condition. No change needed.

### Request-Direction `when` Predicates

**Resolved from v1 Open Question #1:** `match.when` is allowed on both request and
response direction entries. Use cases for request-direction `when`:

- Route different request shapes to different specs (e.g., bulk requests vs. single-item)
- Apply different header operations based on request body content
- Select different URL rewrite rules based on request payload

**No restriction on direction for `when`.** `match.status` remains response-only
(requests don't have status codes).

### TransformContext Availability During Matching

**Gap identified in v1:** The `match.when` expression receives `TransformContext`
variables (`$headers`, `$status`, `$queryParams`, etc.). But `TransformContext` is
currently built *after* matching (inside `transformWithSpec`).

**Fix:** The `TransformContext` is actually built by the adapter (PingAccess or
standalone proxy) and passed to `engine.transform()`. Looking at the code flow:

> **v3 clarification:** Even the 2-arg `transform(Message, Direction)` overload builds
> a `TransformContext` from message metadata (`message.headers()`, `message.statusCode()`,
> `message.session()`) before calling `transformInternal()`. So the context is always
> available at the `findMatches()` callsite. The adapter 3-arg path provides a **richer**
> context (with parsed cookies, query params), but the 2-arg path still passes a valid
> context — it just has empty cookies/queryParams maps.

1. Adapter builds `TransformContext` from the raw exchange.
2. Adapter calls `engine.transform(message, direction, context)`.
3. Engine calls `transformInternal(message, direction, context)`.
4. Engine calls `ProfileMatcher.findMatches(...)`.

Currently, `context` is NOT passed to `findMatches`. For Phase 2, it must be passed
so that `when` predicates can access `$headers`, `$status`, etc. This is a straightforward
change — the context is already available at the callsite.

```java
// Current:
List<ProfileEntry> matches = ProfileMatcher.findMatches(
    profile, message.requestPath(), message.requestMethod(),
    message.contentType(), direction);

// Phase 1:
List<ProfileEntry> matches = ProfileMatcher.findMatches(
    profile, message.requestPath(), message.requestMethod(),
    message.contentType(), direction,
    message.statusCode());

// Phase 2:
JsonNode parsedBody = profile.hasWhenPredicates()
    ? bodyToJson(message.body())
    : null;

List<ProfileEntry> matches = ProfileMatcher.findMatches(
    profile, message.requestPath(), message.requestMethod(),
    message.contentType(), direction,
    message.statusCode(), parsedBody, context);
```

**Optimization:** `profile.hasWhenPredicates()` is a computed method on
`TransformProfile` (see §v4 Record Field Issue for why it's a method, not a
record component). The iteration over entries is O(n) over a small list
(typically 2–10 entries) and is called once per request.

```java
// TransformProfile — computed method (not a record component)
public record TransformProfile(
    String id, String description, String version,
    List<ProfileEntry> entries
) {
    public boolean hasWhenPredicates() {
        return entries.stream().anyMatch(e -> e.whenPredicate() != null);
    }
}
```

### Structured Logging Extension

**Gap identified in v1:** The proposal didn't specify the logging delta. The existing
structured logging (NFR-001-08) must be extended:

New log fields for transform match events:

| Field | Value | When |
|-------|-------|------|
| `match.status_pattern` | e.g., `"4xx"`, `"404"`, `"!5xx"` | When `match.status` is present on matched entry |
| `match.when_result` | `true`/`false`/`error` | When `match.when` is present on candidate entry |
| `match.when_expr` | The predicate expression text | When `match.when` is present (for debuggability) |
| `match.candidates_evaluated` | Integer count | Total entries evaluated before finding matches |
| `match.when_evaluations` | Integer count | Number of `when` predicates actually evaluated (after envelope short-circuit) |

This aids production debugging — when a transform isn't firing, the operator can see
whether the entry was rejected at the status check or the `when` check.

### `status.when` Context Gap (v3)

**Gap identified in v3:** The existing `StatusTransformer.apply()` passes
`TransformContext.empty()` to the `status.when` predicate evaluation
(`StatusTransformer.java:62`). This means `$headers`, `$status`, `$queryParams`,
`$cookies`, and `$session` are all **unavailable** inside `status.when` expressions
today.

The new `match.when` provides the full `TransformContext`. This creates a subtle
inconsistency: `when` at the profile layer can see `$headers` but `when` at the spec
layer cannot.

**Decision:** This is a known limitation of `status.when`, not a blocker for
`match.when`. The fix is to pass the actual `TransformContext` to
`StatusTransformer.apply()` — but that's an orthogonal change (pre-existing bug/
limitation, not introduced by this proposal). File as a separate follow-up task.

### `isTruthy()` Duplication (v3)

**Gap identified in v3:** The `isTruthy(JsonNode)` helper is duplicated in both
`StatusTransformer` and `UrlTransformer` with identical logic. The `match.when`
evaluation will need the same helper, creating a third copy.

**Recommendation:** Extract `isTruthy()` into a shared utility (e.g.,
`JsonNodeUtils.isTruthy()` or add it to `TransformEngine` as a package-private
static method) before or during Phase 2 implementation. This is a minor refactor
but avoids triple-duplication.

### ProfileParser Unknown-Key Detection Gap (v3)

**Gap identified in v3:** `SpecParser` has strict unknown-key detection for every
YAML block (via `rejectUnknownKeys()`), which catches typos like `headers.request.add`
at load time. However, `ProfileParser` has **no** unknown-key detection at all —
not for the entry-level keys, and not for the `match` block keys.

This means if an operator writes `match.staus: "4xx"` (typo), it will be silently
ignored and the entry will match all status codes. This is dangerous.

**Recommendation:** Add unknown-key detection to `ProfileParser` as part of the
Phase 1 implementation:

```java
private static final Set<String> KNOWN_ENTRY_KEYS = Set.of(
    "spec", "direction", "match");
private static final Set<String> KNOWN_MATCH_KEYS = Set.of(
    "path", "method", "content-type", "status", "when");
```

This is a **pre-existing gap** (not introduced by this proposal) but becomes
critically important when adding new match keys — a typo in `status` or `when`
silently degrades to match-all behavior.

### Fast-Path: Single When Match (v3)

**Gap identified in v3:** When status+when routing is configured for mutually exclusive
branches (the common case — e.g., admin vs. regular user), we know at most ONE entry
will match. Currently `findMatches()` evaluates ALL entries and returns a list. For
the common polymorphic routing case, we evaluate N predicates when only 1 can match.

**Optimization (future):** After all envelope+status filtering, if the remaining
candidates have `when` predicates, evaluate them in declaration order and **stop at
the first match**. This is safe because:
- If entries are mutually exclusive → only one matches anyway.
- If entries are NOT mutually exclusive → the operator has explicitly set up a
  pipeline. But evaluating sequentially and stopping early changes semantics.

**Decision:** Do NOT implement this optimization in the initial release. The current
"evaluate all" approach is correct for all cases. If profiling shows `when` evaluation
is a bottleneck, add an opt-in `exclusive: true` hint on the profile or match block
that enables early-exit. This avoids a correctness risk for a speculative performance
gain.

### YAML Integer-vs-String Silent Failure in Status Parsing (v4)

**Gap identified in v4:** The proposal's parser code (§Phase 1 Parser Changes) uses
`optionalString(matchNode, "status")` to read the status field. The `optionalString()`
helper (ProfileParser line 233) checks `child.isTextual()` — it only returns a value
for YAML string nodes.

**The problem:** When an operator writes `status: 404` (unquoted) in YAML, the Jackson
YAML parser produces an **IntNode**, not a TextNode. `optionalString()` returns `null`,
and the status constraint is **silently ignored** — the entry matches ALL status codes.

Only `status: "404"` (quoted) works correctly. This is a critical UX trap because:
- YAML's unquoted integer syntax is the natural way to write status codes.
- The proposal's own examples use unquoted integers (line 303: `status: 404`).
- The failure mode is silent — no error, no warning, just wrong behaviour.

**Fix:** The status parser must handle **all YAML node types**:

```java
private StatusPattern parseStatusField(JsonNode matchNode, Direction direction,
        String profileId, int index, String source) {
    JsonNode statusNode = matchNode.get("status");
    if (statusNode == null || statusNode.isNull()) {
        return null;  // no status constraint
    }

    // Direction validation FIRST
    if (direction == Direction.REQUEST) {
        throw new ProfileResolveException(
            String.format("Profile '%s' entry[%d]: 'match.status' is only valid " +
                "for response-direction entries", profileId, index),
            null, source);
    }

    if (statusNode.isInt() || statusNode.isLong()) {
        // Unquoted integer: status: 404 → Exact
        return StatusPatternParser.parse(
            String.valueOf(statusNode.asInt()), profileId, index, source);
    }
    if (statusNode.isTextual()) {
        // Quoted string: status: "4xx" or status: "400-499" etc.
        return StatusPatternParser.parse(
            statusNode.asText(), profileId, index, source);
    }
    if (statusNode.isArray()) {
        // List: status: [200, 201] or status: ["2xx", 404]
        List<StatusPattern> patterns = new ArrayList<>();
        for (JsonNode element : statusNode) {
            String val = element.isInt() ? String.valueOf(element.asInt())
                : element.asText();
            patterns.add(StatusPatternParser.parse(val, profileId, index, source));
        }
        return new StatusPattern.AnyOf(patterns);
    }

    throw new ProfileResolveException(
        String.format("Profile '%s' entry[%d]: 'match.status' must be a " +
            "string, integer, or list — got %s", profileId, index,
            statusNode.getNodeType()),
        null, source);
}
```

This replaces the original `optionalString()` + separate array check approach, which
had **dead code** (the array branch could never be reached because `optionalString()`
already consumed the field without error).

### ProfileParser Compilation Dependency Gap (v4)

**Gap identified in v4:** The `match.when` block requires expression compilation at
profile load time. The proposal (§Phase 2 Parser Changes, item 14) states the expression
is "compiled at load time via the expression engine SPI" — but `ProfileParser` currently
has **no access** to `EngineRegistry` or any expression compilation infrastructure.

`ProfileParser` is constructed with only a `Map<String, TransformSpec>` spec registry:
```java
public ProfileParser(Map<String, TransformSpec> specRegistry) { ... }
```

The `EngineRegistry` lives in `TransformEngine` and is passed to `SpecParser`, but
never flows to `ProfileParser`. Both `loadProfile()` and `reload()` in TransformEngine
create ProfileParsers without an EngineRegistry.

**Fix:** Extend `ProfileParser`'s constructor to accept `EngineRegistry`:

```java
public ProfileParser(Map<String, TransformSpec> specRegistry,
                     EngineRegistry engineRegistry) {
    this.specRegistry = Objects.requireNonNull(specRegistry);
    this.engineRegistry = Objects.requireNonNull(engineRegistry);
}
```

Update callers:
- `TransformEngine.loadProfile()`: `new ProfileParser(current.allSpecs(), engineRegistry)`
  — but `TransformEngine` stores `SpecParser`, not `EngineRegistry` directly. The
  `EngineRegistry` must either be stored as a field on `TransformEngine`, or extracted
  from the `SpecParser` (which currently wraps it privately).
- `TransformEngine.reload()`: Same issue.

**Recommendation:** Add an `EngineRegistry` field to `TransformEngine` (passed at
construction time or extracted from `SpecParser`). This is a constructor API change but
is backward-compatible via overloading.

### `TransformProfile.hasWhenPredicates` — Java Record Field Issue (v4)

**Gap identified in v4:** The proposal (§Phase 2 Model Changes, item 10) adds
`hasWhenPredicates` as a record component:

```java
public record TransformProfile(
    String id, String description, String version,
    List<ProfileEntry> entries,
    boolean hasWhenPredicates  // ←  computed field
) { ... }
```

This is **semantically wrong** for a Java record. Record components are data fields
that must be provided at construction time and form part of the equals/hashCode/toString
contract. A computed flag shouldn't be a constructor parameter because:
1. It creates a broken API — callers could pass `false` even if entries have predicates.
2. It bloats the canonical constructor signature.
3. It makes deserialization fragile (if profiles are ever serialized).

**Fix — Option A (preferred): Computed method, not field.**
```java
public record TransformProfile(
    String id, String description, String version,
    List<ProfileEntry> entries
) {
    // Computed lazily on first call (records are final, so entries won't change)
    public boolean hasWhenPredicates() {
        return entries.stream().anyMatch(e -> e.whenPredicate() != null);
    }
}
```

**Concern:** This iterates the list on every call. For hot paths, this matters.

**Fix — Option B: Cache via transient field (not a record component).**
Java records can't have instance fields outside the component list. So caching requires
either:
- A `List`-wrapping holder that carries the flag alongside entries, OR
- Precomputing the flag in `ProfileParser` and storing it alongside the profile in
  `TransformRegistry`, OR
- Using a static WeakHashMap cache (overkill).

**Recommendation:** Use Option A (computed method). The iteration is O(n) over a small
list (typical profiles have 2–10 entries) and is called once per request at the top of
`transformInternal()`. If profiling shows it's hot, replace with Option B.

### Pre-Parsed Body Reuse Covers Only 1 of 4 `bodyToJson()` Calls (v4)

**Gap identified in v4:** The proposal's reuse optimization (§Phase 2 Engine Changes,
item 15) shows passing `preParsedBody` to `transformWithSpec` to avoid re-parsing:

```java
// In transformWithSpec:
JsonNode originalBody = preParsedBody != null ? preParsedBody : bodyToJson(message.body());
```

But `transformWithSpec()` calls `bodyToJson(message.body())` at **4 separate sites**:
1. Line 541: `JsonNode originalBody = bodyToJson(message.body());` — for URL rewriting
2. Line 551: `validateInputSchema(bodyToJson(message.body()), spec);` — schema validation
3. Line 559: `JsonNode pipelineInput = bodyToJson(message.body());` — pipeline start
4. Line 572: `transformedBody = expr.evaluate(bodyToJson(message.body()), context);` — non-pipeline

All four parse the **same** `message.body()`. The proposal's optimization covers only
site #1. Sites 2–4 still re-parse.

**Fix:** Pass `preParsedBody` once, and use it for ALL four sites:

```java
private TransformResult transformWithSpec(
        TransformSpec spec, Message message, Direction direction,
        LogContext logCtx, TransformContext context,
        JsonNode preParsedBody) {  // NEW optional parameter

    JsonNode originalBody = preParsedBody != null
        ? preParsedBody
        : bodyToJson(message.body());

    // Use originalBody for ALL sites instead of calling bodyToJson() again:
    // - Schema validation: validateInputSchema(originalBody, spec);
    // - Pipeline input: JsonNode pipelineInput = originalBody;
    // - Non-pipeline: transformedBody = expr.evaluate(originalBody, context);
}
```

This eliminates **3 redundant JSON parses per request** when `when` predicates are
active. Even without `when`, consolidating to a single `bodyToJson()` call is a
worthwhile cleanup.

**Note:** This consolidation is safe because all four sites parse the same
`message.body()` which is immutable (Message is a record).

### `StatusPattern.matches(int)` vs `Message.statusCode()` Nullability (v4)

**Gap identified in v4:** The `StatusPattern` interface declares:
```java
boolean matches(int statusCode);  // primitive int
```

But `Message.statusCode()` returns `Integer` (nullable). For request-direction messages,
`statusCode` is typically `null`. Although the proposal says `match.status` is
response-only (validated at parse time), the `ProfileMatcher.matches()` method receives
the status code for ALL entries (request and response).

The proposal's matcher code (§Phase 1 Model Changes, item 3) correctly guards:
```java
if (statusCode == null) return false;  // defensive
```

But the `StatusPattern.matches(int)` signature uses a **primitive** `int`. If someone
bypasses the guard and calls `statusPattern.matches(statusCode)` where `statusCode` is
`null`, Java auto-unboxing will throw a `NullPointerException`.

**Recommendation:** Change the signature to accept `Integer` for belt-and-suspenders
safety:
```java
boolean matches(Integer statusCode);
// With default NPE-safe behavior:
default boolean matchesSafe(Integer statusCode) {
    return statusCode != null && matches(statusCode);
}
```

Alternatively, keep `int` and rely on the guard in `ProfileMatcher`. Document the
contract explicitly: "callers MUST null-check before calling `matches()`."

**Decision:** Keep `matches(int)` (primitive) for clarity and performance — the guard
in `ProfileMatcher` is sufficient. But add a `@param` Javadoc note: "must not be called
with null — caller is responsible for null-checking."

## Implementation Plan

### Phase 1: `match.status` (Core Engine — Feature 001)

**Scope:** Status-code pattern matching in ProfileEntry and ProfileMatcher.

#### Model Changes

1. **`ProfileEntry`** — add `statusPattern` field:
   ```java
   public record ProfileEntry(
       TransformSpec spec,
       Direction direction,
       String pathPattern,
       String method,
       String contentType,
       StatusPattern statusPattern    // NEW — null means "match any status"
   ) { ... }
   ```

2. **`StatusPattern`** — new value object for status code matching:
   ```java
   public sealed interface StatusPattern {
       boolean matches(int statusCode);
       int specificityWeight();   // for ADR-0006 constraint scoring

       record Exact(int code) implements StatusPattern {
           public boolean matches(int sc) { return sc == code; }
           public int specificityWeight() { return 2; }
       }
       record Class(int classDigit) implements StatusPattern {
           public boolean matches(int sc) { return sc / 100 == classDigit; }
           public int specificityWeight() { return 1; }
       }
       record Range(int low, int high) implements StatusPattern {
           public boolean matches(int sc) { return sc >= low && sc <= high; }
           public int specificityWeight() { return 2; }
       }
       record Not(StatusPattern inner) implements StatusPattern {
           public boolean matches(int sc) { return !inner.matches(sc); }
           public int specificityWeight() { return 1; }
       }
       record AnyOf(List<StatusPattern> patterns) implements StatusPattern {
           public boolean matches(int sc) {
               return patterns.stream().anyMatch(p -> p.matches(sc));
           }
           public int specificityWeight() {
               return patterns.stream().mapToInt(StatusPattern::specificityWeight).max().orElse(1);
           }
       }
   }
   ```

3. **`ProfileMatcher.matches()`** — add status code check:
   ```java
   // After direction, path, method, content-type checks:
   if (entry.statusPattern() != null) {
       if (statusCode == null) {
           // Response direction but no status code on message — should not happen
           // in practice. Defensive: treat as non-matching.
           return false;
       }
       if (!entry.statusPattern().matches(statusCode)) {
           return false;
       }
   }
   ```

   **Note:** The `statusCode != null` guard handles an edge case where the adapter fails
   to populate the status code. This should never happen in practice (both PingAccess and
   standalone adapters always set it on response-direction Messages) but is defensive
   programming against future adapters.

4. **`ProfileEntry.constraintCount()`** — weighted for status pattern:
   ```java
   public int constraintCount() {
       int count = 0;
       if (method != null) count++;
       if (contentType != null) count++;
       if (statusPattern != null) count += statusPattern.specificityWeight();
       return count;
   }
   ```

#### Parser Changes

5. **`ProfileParser.parseEntry()`** — parse `match.status`:
   - String `"200"` → `StatusPattern.Exact(200)`
   - String `"4xx"` → `StatusPattern.Class(4)`
   - String `"400-499"` → `StatusPattern.Range(400, 499)`
   - String `"!404"` → `StatusPattern.Not(Exact(404))`
   - String `"!5xx"` → `StatusPattern.Not(Class(5))`
   - List `[200, 201]` → `StatusPattern.AnyOf([Exact(200), Exact(201)])`

   **Parser integration point:** The `parseEntry` method currently extracts `path`,
   `method`, `content-type` from the `match` node with `optionalString()`. The
   `status` field **must NOT use `optionalString()`** — see §YAML Integer-vs-String
   Silent Failure for details. Instead, use the type-aware `parseStatusField()`:

   ```java
   // In parseEntry(), after existing match field extraction:
   // ⚠️ v4 fix: DO NOT use optionalString() for status — YAML parses
   // unquoted 404 as IntNode, which optionalString() silently ignores.
   // Use parseStatusField() instead (see §YAML Integer-vs-String fix).
   StatusPattern statusPattern = parseStatusField(
       matchNode, direction, profileId, index, source);
   ```

6. **Load-time validation:**
   - `match.status` on a `direction: request` entry → **reject** with diagnostic
     ("status matching is only valid for response-direction entries").
   - Invalid status code (outside 100–599) → reject.
   - Invalid pattern syntax → reject.
   - Range with `low > high` → reject.
   - Range with `low == high` → warn, suggest exact match.

#### Engine Changes

7. **`TransformEngine.transformInternal()`** — pass status code to ProfileMatcher:
   ```java
   List<ProfileEntry> matches = ProfileMatcher.findMatches(
       profile, message.requestPath(), message.requestMethod(),
       message.contentType(), direction,
       message.statusCode());  // NEW parameter
   ```

   This is a non-breaking change: the existing 5-arg overload remains, the new 6-arg
   overload adds `statusCode`. **Both PA and standalone adapters already populate
   `message.statusCode()`** — verified in `PingAccessAdapter.wrapResponse()` (line 114)
   and `ProxyHandler` step 7.

#### Unit Tests

- `StatusPatternTest` — all pattern types: exact, class, range, negation, anyOf,
  edge cases (boundary codes 100/599, code 0, negative codes)
- `StatusPatternParserTest` — parsing: valid strings, class patterns, ranges, negation,
  invalid syntax, request-direction rejection
- `ProfileMatcherTest` — status matching, status + path, status + method, status on
  request direction (rejected at parse time), missing status (match-all), specificity
  ordering: exact beats class beats absent
- `TransformEngineTest` — integration: response with 404 matches status: "4xx" entry,
  response with 200 doesn't match "4xx"

#### E2E Tests (PingAccess — Feature 002)

New E2E scenarios:

| Test | Description | Spec/Profile Config |
|------|-------------|---------------------|
| **Status routing: error vs. success** | POST → backend returns 200 → success spec fires (body rewritten, status preserved). POST → backend returns configured error → error spec fires (body rewritten differently, status may override). | Two response entries on same path: `status: "2xx"` → success-rewrite spec, `status: "4xx"` → error-rewrite spec. |
| **Status routing: exact code** | Backend returns 404 → spec remaps to 200 with custom body. | Response entry with `status: 404`. |
| **Status routing: passthrough for unmatched status** | Backend returns 301 → no response entry matches (only 2xx and 4xx configured) → passthrough. | Validates that unmatched status codes don't accidentally trigger transforms. |
| **Status specificity: exact beats class** | Backend returns 404 → exact-match spec fires, not the class-match spec. Verifies weighted constraint scoring. | Two entries: `status: "4xx"` → generic error, `status: 404` → specific 404 handler. |

### Phase 2: `match.when` (Core Engine — Feature 001)

**Scope:** Body-predicate matching in ProfileEntry and ProfileMatcher.

#### Model Changes

8. **`ProfileEntry`** — add `whenPredicate` field:
   ```java
   public record ProfileEntry(
       TransformSpec spec,
       Direction direction,
       String pathPattern,
       String method,
       String contentType,
       StatusPattern statusPattern,
       CompiledExpression whenPredicate  // NEW — null means "always match"
   ) { ... }
   ```

9. **`ProfileEntry.constraintCount()`** — increment for `when`:
   ```java
   if (whenPredicate != null) count++;
   ```

10. **`TransformProfile`** — add `hasWhenPredicates()` computed method:
    ```java
    public record TransformProfile(
        String id, String description, String version,
        List<ProfileEntry> entries
    ) {
        // ⚠️ v4 fix: NOT a record component — computed method instead.
        // Java records can't have non-component fields. See §Record Field Issue.
        public boolean hasWhenPredicates() {
            return entries.stream()
                .anyMatch(e -> e.whenPredicate() != null);
        }
    }
    ```

#### ProfileMatcher Changes

11. **Two-phase matching:** The matcher needs the parsed body for `when` evaluation.
    Introduce a new overload that accepts the body:
    ```java
    public static List<ProfileEntry> findMatches(
        TransformProfile profile, String requestPath, String method,
        String contentType, Direction direction, Integer statusCode,
        JsonNode body, TransformContext context)  // NEW parameters
    ```

    Evaluation order (short-circuit):
    1. Direction → 2. Path → 3. Method → 4. Content-type → 5. Status → 6. `when` predicate

    This ensures `when` (the most expensive check) is only evaluated for entries that
    already passed all cheap envelope checks.

12. **`when` evaluation failure:** If the `when` predicate throws, log a warning and
    treat the entry as non-matching (fail-safe, same semantics as `status.when`
    failure in StatusTransformer).

13. **Non-JSON body guard:** If `body` is `null` (non-JSON) and the entry has a
    `when` predicate, treat the entry as non-matching with a debug-level log.

#### Parser Changes

14. **`ProfileParser`** — parse `match.when`:
    ```yaml
    match:
      path: "/api/users/**"
      when:
        lang: jslt
        expr: '.type == "admin"'
    ```
    The `when` block uses the same `lang`/`expr` structure as transform and status
    predicates. The expression is compiled at load time via the expression engine SPI.

    **Important:** The `when` expression is compiled against the JSLT engine at load
    time. If the profile references a `when` with `lang: jolt` — reject with a clear
    diagnostic. JOLT doesn't produce boolean predicate results. Only JSLT (or future
    expression engines that support boolean evaluation) are valid for `when`.

#### Engine Changes

15. **`TransformEngine.transformInternal()`** — pass body and context to ProfileMatcher:
    The body must be parsed before profile matching when any profile entry has a `when`
    predicate. This is a minor overhead change:
    - If **no** profile entries have `when` predicates → parse body after matching
      (current behaviour, zero overhead).
    - If **any** profile entry has a `when` predicate → parse body before matching,
      pass parsed body to ProfileMatcher. The parsed body is reused for the transform
      expression evaluation (no double parse).

    ```java
    // Optimization: only parse body early if any entry needs when-predicate
    JsonNode parsedBody = null;
    if (profile.hasWhenPredicates()) {
        try {
            parsedBody = bodyToJson(message.body());
        } catch (IllegalArgumentException e) {
            // bodyToJson() throws on non-JSON content (NOT returns null)
            // Non-JSON body — when predicates cannot evaluate.
            // Entries without when predicates can still match.
            LOG.debug("Body is not JSON — when predicates will not match: {}",
                e.getMessage());
            parsedBody = null;
        }
    }

    List<ProfileEntry> matches = ProfileMatcher.findMatches(
        profile, message.requestPath(), message.requestMethod(),
        message.contentType(), direction, message.statusCode(),
        parsedBody, context);
    ```

    **Reuse optimization (v4 — expanded scope):** When the matched entry's spec
    transforms the body, the already-parsed `parsedBody` should be reused by
    `transformWithSpec` instead of re-parsing. `transformWithSpec()` currently calls
    `bodyToJson(message.body())` at **4 separate sites** (original body, schema
    validation, pipeline input, non-pipeline eval). All four parse the same immutable
    `message.body()`. Passing `preParsedBody` to `transformWithSpec` consolidates
    all four into a single parse — see §Pre-Parsed Body Reuse for details.

    ```java
    // In transformWithSpec — accept pre-parsed body:
    JsonNode originalBody = preParsedBody != null
        ? preParsedBody
        : bodyToJson(message.body());
    // Use originalBody for ALL sites: URL rewrite, schema validation,
    // pipeline input, and non-pipeline eval.
    ```

#### Unit Tests

- `ProfileMatcherTest` — `when` predicate matches, doesn't match, error (fail-safe),
  combined with status + path matching, non-JSON body (null parsedBody), multiple
  non-exclusive `when` predicates (pipeline result)
- `TransformEngineTest` — e2e: polymorphic response body routes to different specs,
  pre-parsed body reuse (no double parsing), non-JSON body with `when` entries
  (graceful fallback)

#### E2E Tests (PingAccess — Feature 002)

| Test | Description | Spec/Profile Config |
|------|-------------|---------------------|
| **Body predicate: field presence** | Backend returns `{"type": "admin", ...}` → admin spec. Backend returns `{"type": "user", ...}` → user spec. | Two response entries on same path + same status, different `when`. |
| **Body predicate with status** | 200 + `type=admin` → admin spec. 200 + `type=user` → user spec. 4xx → error spec. | Three entries: two with `status: "2xx"` + different `when`, one with `status: "4xx"`. |
| **Body predicate fallthrough** | Response body doesn't match any `when` predicate → passthrough. | Validates fail-safe behaviour. |
| **Body predicate pipeline** | Two non-exclusive `when` predicates both match → pipeline fires. | Two entries with overlapping conditions, both match, verifying ADR-0012 pipeline. |
| **Non-JSON body with when** | Backend returns HTML body with `when` predicate configured → graceful skip. | Validates non-JSON handling. |

### Implementation Tracker

> Visual progress tracker. Updated as each step is committed.

#### Phase 1: `match.status` — ✅ Complete (2026-02-15, commit `7fd5ddb`)

- [x] Create `StatusPattern` sealed interface + implementations (Exact, Class, Range, Not, AnyOf)
- [x] Create `StatusPatternParser` — type-aware YAML parsing (handles IntNode gotcha)
- [x] Update `ProfileEntry` — add `statusPattern` field (backward-compatible 5-arg ctor)
- [x] Update `ProfileEntry.constraintCount()` — weighted specificity (Exact=2, Class/Not=1)
- [x] Update `ProfileParser.parseEntry()` — integrate `parseStatusField()` (NOT `optionalString`)
- [x] Update `ProfileMatcher.findMatches()` — new 6-arg overload accepting `statusCode`
- [x] Update `TransformEngine.transformInternal()` — pass `message.statusCode()`
- [x] Add `StatusPatternTest` — all pattern types, validation, boundaries, defensive copy
- [x] Add `StatusPatternParserTest` — string/int/array parsing, YAML gotcha, validation errors
- [x] Add `ProfileMatcherTest` status tests — class routing, exact match, specificity, null-safety, passthrough
- [x] All 524 tests pass, 0 failures

#### Phase 1b: Unknown-key detection in `ProfileParser`

- [x] Add known-key sets for root, entry, and match block (parity with `SpecParser`)
- [x] Reject unknown keys at all 3 levels: root, entry, match block
- [x] 5 unit tests in `ProfileParserTest.UnknownKeyDetection` — all 529 tests pass

#### Phase 2: `match.when` — Body-Predicate Matching

- [x] Add `EngineRegistry` field to `TransformEngine` (constructor change)
- [x] Update `ProfileParser` constructor to accept `EngineRegistry`
- [x] Update `ProfileParser.parseEntry()` — parse `when` block, compile expression at load time
- [x] Update `ProfileEntry` — add `whenPredicate` field
- [x] Add `hasWhenPredicates()` computed method to `TransformProfile` (NOT record component)
- [x] Update `ProfileMatcher` — two-phase matching (envelope, then body predicate)
- [x] Update `TransformEngine.transformInternal()` — conditional body pre-parse
- [x] Pass `preParsedBody` to `transformWithSpec` (reuse across all 4 `bodyToJson` sites)
- [x] Extract `isTruthy()` to `JsonNodeUtils`
- [x] Unit tests: `ProfileMatcherTest` (when predicate), `ProfileParserTest` (when parsing)
- [ ] E2E tests: body predicate routing, status+when combined, non-JSON body graceful skip

#### SDD Documentation (cross-cutting)

- [x] Add FR-001-15 / FR-001-16 to `spec.md`
- [x] Add S-001-87 – S-001-100 to `scenarios.md`
- [x] Update coverage matrix
- [x] Add implementation tasks to `tasks.md` (T-001-69–72)

### Phase Summary

| Phase | Scope | Affected Code | Backward Compatible? |
|-------|-------|---------------|----------------------|
| **Phase 1** | `match.status` | `ProfileEntry`, `StatusPattern`, `StatusPatternParser`, `ProfileMatcher`, `ProfileParser`, `TransformEngine` | ✅ Yes — new optional field, defaults to match-all |
| **Phase 1b** | Unknown-key detection in `ProfileParser` | `ProfileParser` | ✅ Yes — stricter validation, existing valid profiles unaffected |
| **Phase 2** | `match.when` | `ProfileEntry`, `TransformProfile`, `ProfileMatcher`, `ProfileParser` (+`EngineRegistry` dep), `TransformEngine` (+`EngineRegistry` field), `JsonNodeUtils` (isTruthy extraction) | ✅ Yes — new optional field, defaults to always-match |

Both phases are **fully backward-compatible** — existing profiles with no `status`
or `when` fields behave identically to today.

## ADR Impact

### New ADR Required: ADR-0036 — Conditional Response Routing

Decision: Extend profile `match` with `status` (envelope predicate) and `when` (body
predicate) for response-direction entries.

Key points for the ADR:
- **Status matching is an envelope predicate** — cheap, evaluated before body parsing.
- **Body-predicate matching is lazy** — only evaluated when envelope predicates pass.
- **Spec remains routing-agnostic** — the profile decides when to apply it (extends
  ADR-0015, ADR-0016).
- **Evaluation order guarantee** — direction → path → method → content-type → status →
  when. Documented and enforced.
- **`when` predicates are always evaluated against the original body** — not
  intermediate pipeline output. This is the "route the input" convention (ADR-0027).
- **Ambiguity with `when`** — entries with `when` predicates are exempt from
  load-time ambiguity rejection. Runtime pipelining applies if both match.

### Existing ADR Updates

- **ADR-0006** (Profile Match Resolution) — update to include `status` (weighted)
  and `when` in constraint count for tie-breaking. Add exemption for `when`-bearing
  entries from load-time ambiguity rejection.
- **ADR-0003** (Status Code Transforms) — add a note distinguishing `status.when`
  (spec-level conditional override, evaluated against *transformed* body) from
  `match.status` (profile-level routing, evaluated against *incoming status code*).
- **ADR-0012** (Pipeline Chaining) — add section clarifying that `when` predicates
  are evaluated against the original body, not pipeline intermediates.

## Feature Impact

### Feature 001 (Core Engine)

New FR required:

| ID | Requirement |
|----|-------------|
| FR-001-15 | Profile entries MAY include `match.status` for response-direction entries, supporting exact code, class pattern (Nxx), negation (!), range, and list matching. Weighted specificity scoring per §Specificity Implications. |
| FR-001-16 | Profile entries MAY include `match.when` containing a compiled expression evaluated against the original body, enabling body-shape-aware routing. Allowed on both request and response directions. |

> **v3 note:** FR-001-13 (Session Context Binding) and FR-001-14 (Port Value Objects)
> are already allocated. The v2 proposal incorrectly reused these IDs.

New scenarios required (starting from S-001-87, the next available ID):

| ID | Description | Tags |
|----|-------------|------|
| S-001-87 | Status class routing: 2xx matches, 4xx doesn't | status, routing, profile |
| S-001-88 | Status exact match: 404 → spec fires, 200 → no match | status, routing, exact |
| S-001-89 | Status range: 400-499 → spec fires | status, routing, range |
| S-001-90 | Status on request direction → load-time error | status, validation |
| S-001-100 | Status unquoted integer in YAML: `status: 404` (IntNode) behaves same as `status: "404"` | status, parser, yaml |
| S-001-91 | Status negation: !5xx matches 200, doesn't match 502 | status, routing, negation |
| S-001-92 | Status specificity: exact beats class for same code | status, specificity, adr-0006 |
| S-001-93 | Body when predicate matches → spec fires | when, predicate, routing |
| S-001-94 | Body when predicate fails → no match (fail-safe) | when, error, fail-safe |
| S-001-95 | Combined status + when: 200 + admin → admin spec | status, when, combined |
| S-001-96 | No match on status + no match on when → passthrough | status, when, passthrough |
| S-001-97 | Non-JSON body with when predicate → graceful skip | when, non-json, fail-safe |
| S-001-98 | When predicate on request direction → works | when, request, both-directions |
| S-001-99 | When + pipeline: two non-exclusive when predicates → ADR-0012 chain | when, pipeline, chaining |

### Feature 002 (PingAccess Adapter)

- **No adapter code changes needed** — the PingAccess adapter already populates
  `statusCode` from `response.getStatusCode()` (verified in `PingAccessAdapter.java`
  line 114) and passes it to `TransformEngine.transform()`.
- E2E test additions: 5–8 new scenarios validating the feature through PingAccess.

### Feature 004 (Standalone Proxy)

- **No adapter code changes needed** — `ProxyHandler` already wraps the upstream
  response status into the `Message` (step 7) and passes it to
  `TransformEngine.transform()`.
- Integration test additions: status-based routing and body-predicate routing tests.

## E2E Test Plan (PingAccess)

### New Specs

```yaml
# e2e-status-route-success.yaml
id: e2e-status-route-success
version: "1.0.0"
description: "Success response rewrite for status routing tests"
transform:
  lang: jslt
  expr: |
    {
      "result": "success",
      "original_status": $status,
      "data": .data
    }

# e2e-status-route-error.yaml
id: e2e-status-route-error
version: "1.0.0"
description: "Error response rewrite for status routing tests"
transform:
  lang: jslt
  expr: |
    {
      "result": "error",
      "original_status": $status,
      "error_code": .errorCode,
      "error_message": .message
    }
status:
  set: 502

# e2e-polymorphic-admin.yaml
id: e2e-polymorphic-admin
version: "1.0.0"
description: "Admin user response transform for polymorphic routing"
transform:
  lang: jslt
  expr: |
    {
      "role": "admin",
      "display_name": .name,
      "permissions": .permissions
    }

# e2e-polymorphic-user.yaml
id: e2e-polymorphic-user
version: "1.0.0"
description: "Regular user response transform for polymorphic routing"
transform:
  lang: jslt
  expr: |
    {
      "role": "standard",
      "display_name": .name
    }
```

### New Profile Entries

```yaml
  # ── Status routing (Tests 33–35) ──
  - spec: e2e-status-route-success@1.0.0
    direction: response
    match:
      path: "/api/status-route/**"
      status: "2xx"

  - spec: e2e-status-route-error@1.0.0
    direction: response
    match:
      path: "/api/status-route/**"
      status: "4xx"

  # ── Polymorphic body routing (Tests 36–38) ──
  - spec: e2e-polymorphic-admin@1.0.0
    direction: response
    match:
      path: "/api/polymorphic/**"
      status: "2xx"
      when:
        lang: jslt
        expr: '.role == "admin"'

  - spec: e2e-polymorphic-user@1.0.0
    direction: response
    match:
      path: "/api/polymorphic/**"
      status: "2xx"
      when:
        lang: jslt
        expr: '.role != "admin"'

  - spec: e2e-status-route-error@1.0.0
    direction: response
    match:
      path: "/api/polymorphic/**"
      status: "4xx"
```

### New Karate Features

```
e2e/status-routing.feature
├── Test 33 — Status routing: 200 → success spec (body reshaped + status preserved)
├── Test 34 — Status routing: 404 → error spec (body reshaped + status overridden to 502)
├── Test 35 — Status routing: unmatched status (301) → passthrough
└── Test 36 — Status routing: exact beats class (404 specificity)

e2e/polymorphic-routing.feature
├── Test 37 — Polymorphic: 200 + role=admin → admin transform
├── Test 38 — Polymorphic: 200 + role=user → user transform
├── Test 39 — Polymorphic: 400 error → error transform (no body predicate needed)
└── Test 40 — Polymorphic: 200 + unknown role → passthrough (no when match)
```

### Echo Backend Considerations

The echo backend must be able to return configurable status codes for the routing tests.
Current echo backend returns 200 by default. Options:

1. **Path-based status:** `/api/status-route/404` → echo returns 404. The echo backend
   already supports custom status via path segments if configured — verify and extend.
2. **Header-based status:** Request header `X-Echo-Status: 404` → echo returns 404.
   This is more flexible and doesn't conflict with profile path matching.
3. **Separate error endpoint:** A static endpoint that always returns 4xx with a
   specific body shape.

The **header-based approach** (`X-Echo-Status`) is recommended — it's the most flexible
and cleanly separates the test control mechanism from the profile routing path.

**Implementation note:** The `X-Echo-Status` header would be consumed by the echo
backend *before* the request-side transform runs. Since request transforms can add
headers (via `headerSpec`), we need to ensure the echo status header is set by the
*test* (Karate), not by a spec. This is correct in practice — Karate sets the header,
PA passes it through to the backend, the backend returns the configured status.

**IMPORTANT:** Request-side transforms that add/remove headers will affect what the
echo backend sees. The `X-Echo-Status` header should be in the original Karate request,
and the spec should NOT remove or rename it (or should explicitly preserve it). The
E2E specs for status routing (`e2e-status-route-*`) are response-only, so there's no
request-side spec to interfere. However, if a request-side entry also matches the same
path, it could modify headers. The test profile should NOT have a request-direction
entry for `/api/status-route/**` to avoid this interaction.

## Rejected Alternatives

### A. Spec-level `when` guard (instead of profile-level)

```yaml
# REJECTED — coupling routing to spec
id: error-normalizer
version: "1.0.0"
when:                              # ← rejected
  lang: jslt
  expr: '$status >= 400'
transform: ...
```

**Why rejected:**
- Violates ADR-0015/ADR-0016 — spec is a capability declaration, profile is routing.
- Reduces spec reusability — the same transform can't be used with different routing
  conditions in different profiles.
- The `when` on the spec is a prerequisite, not a routing decision.

### B. Direction-specific expressions (instead of profile routing)

```yaml
# REJECTED — overloading bidirectional with conditional logic
forward:
  lang: jslt
  expr: |
    if ($status >= 400)
      { ... error handling ... }
    else
      { ... success handling ... }
```

**Why rejected:**
- This already works today with JSLT `if`/`else`, but:
  - Forces all routing logic into a single expression.
  - Cannot have different header/status/URL rules per branch.
  - Doesn't scale to 3+ response shapes.
- Not rejected as a technique — it's valid for simple cases. But it shouldn't be the
  *only* option.

### C. `match.status` as a list-only syntax

```yaml
match:
  status: [200, 201, 204]         # only list syntax
```

**Why rejected:**
- Forces verbose syntax for common cases (`2xx` is more readable than `[200, 201, ...]`).
- Class patterns (`4xx`) are the most common use case and would require enumerating
  100 codes.
- The proposed design supports both — list for specificity, class for convenience.

### D. `when` evaluated against intermediate pipeline body

```yaml
# REJECTED — re-evaluating when against pipeline intermediates
# Would mean Step 2's when sees Step 1's output, not the original body
```

**Why rejected:**
- Breaks the "route the input" principle (ADR-0027).
- Makes pipeline behaviour order-dependent in surprising ways — Step 1 could alter the
  body such that Step 2's `when` predicate now fails (or passes unexpectedly).
- `when` is a routing decision, not a transform-time computation. It should be evaluated
  once, against a stable input.

## Resolved Questions (from v1)

1. **Should `match.when` be allowed on request-direction entries?**
   **Answer:** Yes. Allowed on both directions. No restriction. See §Request-Direction
   `when`.

2. **Should pipeline chaining work with status-matched entries?**
   **Answer:** Yes. ADR-0012 pipeline semantics apply unchanged. Two entries with the
   same path + same status pipeline in declaration order. See §Pipeline Chaining.

3. **Should `match.when` predicates compose with pipeline chaining?**
   **Answer:** `when` predicates are evaluated against the original body, at match time,
   before any pipeline execution. If multiple entries (with `when`) match the original
   body, they pipeline per ADR-0012. If predicates are mutually exclusive, only one
   entry matches — no pipeline. This follows naturally from the existing semantics.

## Remaining Open Questions

1. **`match.when` performance for high-traffic profiles.**
   A profile with 10 entries all having `when` predicates would evaluate up to 10
   predicates per request. For profiles bound to high-traffic routes, this could be
   expensive.
   **Mitigation:** Short-circuit on envelope predicates (most entries won't even reach
   the `when` check). Document that `when` predicates should be simple comparisons,
   not complex JSLT expressions. Future optimization: compile `when` into a decision
   tree if benchmarks show a bottleneck. See also §Fast-Path: Single When Match.

2. **Should `match.when` support multi-expression syntax?**
   E.g., `when: [expr1, expr2]` meaning "all must be true" (AND semantics). This would
   allow composable predicates. **Provisional answer:** Not in the initial release.
   A single expression with JSLT `and` operators covers this case. Revisit if user
   feedback shows demand.

3. **Should `match.status` support mixed list with patterns?**
   E.g., `status: ["2xx", 404]` meaning "any 2xx OR exactly 404". This is technically
   supported by `AnyOf` containing both `Class` and `Exact` patterns. The parser needs
   to handle mixed types in the YAML list. **Provisional answer:** Support it — the
   implementation is trivial given the `AnyOf` wrapper, and it's a natural extension.

4. **Should `status.when` (spec-level) be retrofitted to pass `TransformContext`?**
   (v3) Currently `StatusTransformer.apply()` passes `TransformContext.empty()`, which
   means `$headers`, `$status`, etc. are unavailable in spec-level `when` predicates.
   This is arguably a bug in the existing implementation. **Provisional answer:** Fix
   as a separate task (T-001-XX) before or alongside Phase 2, since the inconsistency
   between profile-level `when` (full context) and spec-level `when` (empty context)
   would confuse operators.

5. **Should `match.when` accept a plain string shorthand?**
   (v3) For consistency with `status.when`, should `when: '.role == "admin"'` (without
   `lang`/`expr` block) be accepted as a shorthand implying JSLT? **Provisional answer:**
   Not in the initial release. The explicit `{lang, expr}` block is mandatory for
   profile-level `when` because profiles don't have a default engine. If JSLT shorthand
   is added later, it should be added to both profile and spec levels simultaneously.

## Revision History

| Rev | Date | Key Changes |
|-----|------|-------------|
| 1 | 2026-02-15 | Initial proposal. |
| 2 | 2026-02-15 | 13 gaps addressed: ambiguity exemption for `when`, pipeline+when timing, context availability, non-JSON handling, status specificity hierarchy, negation patterns, `hasWhenPredicates` caching, pre-parsed body reuse, `status.when`/`match.status` disambiguation, structured logging, hot-reload, echo backend, parser detail. |
| 3 | 2026-02-15 | 7 additional gaps: (1) FR-001-13/14 ID collision → renumbered to FR-001-15/16, (2) scenario IDs S-001-XX → S-001-87–99, (3) `match.when` block format vs `status.when` string format inconsistency analyzed, (4) `bodyToJson()` throws not returns null — fixed catch clause, (5) `isTruthy()` triple-duplication → extraction recommended, (6) `status.when` passes empty context — noted as pre-existing limitation divergent from `match.when`, (7) `ProfileParser` lacks unknown-key detection — parity gap with `SpecParser`, (8) `when` is a reused keyword not new — updated Design Principles, (9) fast-path single-when optimization — deferred by design. |
| 4 | 2026-02-15 | 6 additional gaps from type-system/parser-correctness/API-contract angle: (1) YAML integer-vs-string silent failure — `status: 404` (unquoted) silently ignored by `optionalString()` → replaced with type-aware `parseStatusField()`, (2) `ProfileParser` has no `EngineRegistry` dependency → can't compile `when` expressions at load time, (3) `TransformProfile.hasWhenPredicates` can't be a record component → changed to computed method, (4) pre-parsed body reuse covers only 1 of 4 `bodyToJson()` calls in `transformWithSpec` → expanded to all 4, (5) `StatusPattern.matches(int)` primitive vs `Message.statusCode()` `Integer` nullability mismatch → documented contract, (6) added S-001-100 for unquoted-integer YAML parsing scenario. |
