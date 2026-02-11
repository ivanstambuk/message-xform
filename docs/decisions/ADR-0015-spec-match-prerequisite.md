# ADR-0015 – Spec-Level Match as Prerequisite Filter

Date: 2026-02-07 | Status: Accepted

## Context

Transform specs include an optional `match` block (e.g., `content-type: "application/json"`)
and transform profiles also include `match` blocks (with `path`, `method`, `content-type`).
The field `content-type` appeared in both locations, and the interaction between the two
layers was undefined.

This matters because:
- Without clear semantics, authors don't know whether the spec's `match` is a capability
  declaration ("I can only handle JSON") or routing metadata.
- The engine needs to know whether to enforce spec-level match criteria and how they
  interact with profile-level matching (ADR-0006 specificity scoring).

### Options Considered

- **Option A – Spec match as prerequisite; profile further narrows** (chosen)

  The spec-level `match` declares media-type prerequisites — what the spec *can* handle.
  The profile-level `match` declares routing — *when* the spec is applied. The engine
  checks the spec prerequisite first; if satisfied, profile matching proceeds.

  Example:
  ```yaml
  # Spec: declares it can only handle JSON
  id: callback-prettify
  version: "1.0.0"
  match:
    content-type: "application/json"    # prerequisite — spec capability
  transform:
    lang: jslt
    expr: |
      { "type": if (.callbacks) "challenge" else "simple" }

  # Profile: binds the spec to a route
  profile: pingam-prettify
  transforms:
    - spec: callback-prettify@1.0.0
      direction: response
      match:
        path: "/json/alpha/authenticate"
        method: POST
        # content-type is optional here — spec already guarantees JSON-only
  ```

  Pros:
  - ✅ Clear separation: spec = "I can handle JSON", profile = "apply me to this route"
  - ✅ Enables future media-type extensibility (XML transforms, etc.)
  - ✅ Load-time cross-validation: engine can warn if a profile binds a JSON-only spec
    to a route with incompatible content-type

  Cons:
  - ❌ Two-phase matching adds implementation complexity
  - ❌ Authors might be confused when spec is skipped due to prerequisite failure

- **Option B – Remove spec-level match entirely** (rejected)

  Delete the `match` block from the spec format. All matching is a profile concern.

  Pros:
  - ✅ Simplest model — one matching layer, zero ambiguity
  - ✅ Consistent with "specs are portable pure functions" philosophy

  Cons:
  - ❌ No self-documenting capability in the spec
  - ❌ No load-time cross-validation between spec capability and profile binding
  - ❌ Easier to misconfigure

- **Option C – Keep both, profile overrides spec** (rejected)

  Both spec and profile can declare `content-type`. Profile values take precedence,
  spec values act as defaults.

  Pros:
  - ✅ Flexible — spec provides defaults, profiles override when needed
  - ✅ Backwards-compatible

  Cons:
  - ❌ Most complex — requires merge semantics
  - ❌ Violates single-source-of-truth
  - ❌ Unclear: absent profile `content-type` = inherit or match-all?

### Comparison Matrix

| Aspect                     | A: Prerequisite       | B: Remove spec match  | C: Profile overrides  |
|----------------------------|-----------------------|-----------------------|-----------------------|
| Matching layers            | Two (spec → profile)  | One (profile only)    | Two (merged)          |
| Self-documenting specs     | ✅ Declares capability | ❌ No capability info  | ⚠️ Default, overridable|
| Load-time cross-validation | ✅ Warn on mismatch   | ❌ No validation       | ⚠️ Complex merge      |
| Implementation complexity  | Medium                | Low                   | High                  |
| Spec portability           | ✅ Carries requirement | ✅ Pure                | ⚠️ Has defaults       |
| ADR-0006 alignment         | ✅ Profile untouched  | ✅ Simplest            | ❌ Merge complicates   |

Related ADRs:
- ADR-0006 – Profile Match Resolution (specificity scoring, unaffected by this decision)
- ADR-0004 – Engine Support Matrix (capability declarations)

## Decision

We adopt **Option A – Spec match as prerequisite filter**.

The spec-level `match` block declares **media-type prerequisites** — what the spec is
capable of processing. This is NOT routing; it is a capability declaration.

Concrete semantics:
1. **Spec `match.content-type`** is a prerequisite guard. The engine validates at
   profile load time that the profile's bound content-types are compatible with the
   spec's declared capability. Incompatible bindings produce a load-time warning.
2. **Profile `match`** handles all routing: path, method, content-type (narrowing).
   Profile `content-type` is optional — when absent, the spec's prerequisite is the
   effective content-type filter.
3. **Two-phase matching at request time:**
   a. Phase 1 (quick): Does the spec's prerequisite match the request content-type?
      If not → skip this profile entry (passthrough).
   b. Phase 2 (normal): Does the profile's route match? Apply ADR-0006 specificity.
4. The spec `match` block is **optional**. When absent, no prerequisite filtering
   applies — the spec accepts any content-type (profile decides).
5. Only `content-type` is supported in spec-level `match` initially. Path, method,
   and other routing criteria are exclusively profile concerns.

## Consequences

Positive:
- Specs are self-documenting: a spec declares what it can handle.
- Load-time cross-validation catches configuration mismatches early.
- Clean separation: spec = capability, profile = routing.
- Enables future media-type extensibility without breaking profiles.

Negative / trade-offs:
- Two-phase matching adds implementation complexity to the engine.
- Authors may need to check both spec and profile when debugging "why is my transform
  not applied?" — mitigated by structured logging (NFR-001-08).

