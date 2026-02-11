# ADR-0016 – Unidirectional Spec Direction: Profile Determines

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-03 defines bidirectional specs (`forward`/`reverse` blocks) and unidirectional
specs (just `transform`). For bidirectional specs, the profile's `direction` field
selects which expression to run. For unidirectional specs, the spec says "treated as
unidirectional" but does NOT specify which direction that means.

The question: is the *profile* or the *spec* responsible for declaring which direction
a unidirectional transform applies to?

### Options Considered

- **Option A – Profile determines direction; spec is direction-agnostic** (chosen)

  A unidirectional spec with `transform` is a pure input→output function. The profile's
  `direction` field is always required and tells the engine when to apply it.

  Example:
  ```yaml
  # Spec: direction-agnostic
  id: strip-internal
  version: "1.0.0"
  transform:
    lang: jslt
    expr: |
      { * : . } - "_internal" - "_debug"

  # Profile decides direction:
  profile: pingam-cleanup
  transforms:
    - spec: strip-internal@1.0.0
      direction: response
      match:
        path: "/api/users"
    - spec: strip-internal@1.0.0
      direction: request               # same spec, both directions!
      match:
        path: "/api/admin"
  ```

  Pros:
  - ✅ Maximum reusability — same spec can clean request or response bodies
  - ✅ Clean separation: spec = what, profile = when/where
  - ✅ Consistent with ADR-0015 (spec = capability, profile = routing/usage)

  Cons:
  - ❌ Spec alone doesn't indicate intended direction
  - ❌ Author might accidentally bind a response-oriented spec to request phase

- **Option B – Defaults to `response`; profile can override** (rejected)

  Unidirectional specs default to `response` direction. Profile `direction` is optional.

  Pros:
  - ✅ Common case (response transforms) Just Works without boilerplate

  Cons:
  - ❌ Implicit default can mislead — transforms silently applied to wrong phase
  - ❌ Inconsistency: bidirectional is explicit, unidirectional has hidden default

- **Option C – Spec declares its direction explicitly** (rejected)

  Spec adds a `direction` field. Profile must agree or engine rejects at load time.

  Pros:
  - ✅ Most explicit — no ambiguity

  Cons:
  - ❌ Kills reusability — need two specs for same expression in both directions
  - ❌ Redundancy — direction declared in both spec and profile, violates DRY

### Comparison Matrix

| Aspect              | A: Profile decides     | B: Default response   | C: Spec declares    |
|---------------------|------------------------|-----------------------|---------------------|
| Spec reusability    | ✅ Both directions      | ✅ Both (with override)| ❌ One direction     |
| Explicit intent     | ⚠️ Profile needed      | ❌ Hidden default      | ✅ Fully explicit    |
| DRY principle       | ✅ Direction in one place| ✅ One place           | ❌ Duplicated        |
| ADR-0015 alignment  | ✅ Spec=capability      | ⚠️ Implicit routing   | ❌ Spec does routing |

Related ADRs:
- ADR-0015 – Spec-Level Match as Prerequisite Filter (spec = capability, profile = usage)

## Decision

We adopt **Option A – Profile determines direction; spec is direction-agnostic**.

A unidirectional spec (one that uses `transform` instead of `forward`/`reverse`) is
direction-agnostic — it is a pure input→output function. The profile's `direction`
field is **always required** for every transform entry and tells the engine which
HTTP message phase to apply the expression during.

Concrete rules:
1. A spec with only `transform` does NOT declare or imply a direction.
2. The profile's `direction` field is mandatory (no default). Omitting it is a
   load-time error.
3. For bidirectional specs (`forward`/`reverse`), the profile's `direction` selects
   which expression to use: `direction: response` → `forward.expr`,
   `direction: request` → `reverse.expr`.
4. The same unidirectional spec MAY be bound to both `request` and `response`
   directions in different profile entries — this is a feature, not a bug.

## Consequences

Positive:
- Maximum spec reusability across both directions.
- Clean separation of concerns: spec declares transformation logic, profile declares
  usage context (direction, route, content-type).
- Consistent with ADR-0015's prerequisite philosophy.

Negative / trade-offs:
- A spec alone doesn't indicate its intended direction — operators must read the
  profile to understand usage. Mitigated by structured logging (NFR-001-08).
- No auto-guard against binding a response-oriented spec to request phase.

