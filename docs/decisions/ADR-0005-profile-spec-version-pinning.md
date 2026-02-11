# ADR-0005 – Profiles Pin to Spec Versions via id@version

Date: 2026-02-07 | Status: Accepted

## Context

Transform specs (`TransformSpec`) have an `id` and a `version` field. Transform
profiles (`FR-001-05`) reference specs to bind them to URL patterns. Without version
pinning, updating a spec affects all routes that reference it — making gradual
migration impossible.

Real-world scenario: `callback-prettify@1.0.0` is deployed on route A. A breaking
v2 change is needed for route B. Without version pinning, deploying v2 would break
route A. With pinning, both versions coexist and routes migrate independently.

### Options Considered

- **Option A – Informational only** (rejected)
  - The `version` field is purely informational metadata. No engine enforcement, no
    profile pinning.
  - Pros: simplest for v1, no engine complexity.
  - Cons: no automatic compatibility checks, cannot run two versions of the same spec
    concurrently, which blocks gradual migration.

- **Option B – Profiles pin to spec versions via id@version** (chosen)
  - Profiles reference specs by `id@version`. Engine validates version match at load
    time. Multiple versions of the same spec loaded concurrently.
  - Pros: gradual migration, explicit version control per route, self-documenting
    profiles, canary-style deployment.
  - Cons: profile updates required when changing spec versions (intentional friction).

- **Option C – Semver with breaking change policy** (rejected)
  - Version follows semver. Major version bump = breaking change. Engine warns on
    minor/patch changes.
  - Pros: clear upgrade semantics.
  - Cons: heavyweight for the problem — semver parsing adds complexity without the key
    benefit of concurrent loading. You still can't run two major versions side by side
    without explicit pinning.

## Decision

We adopt **Option B – Profiles pin to spec versions via id@version**.

Profiles reference specs using `id@version` syntax (e.g., `callback-prettify@2.0.0`).
Multiple versions of the same spec MAY be loaded concurrently as separate compiled
`TransformSpec` instances.

- **With version**: `spec: callback-prettify@1.0.0` → resolves to exact version.
  Engine fails fast if that version is not loaded.
- **Without version**: `spec: callback-prettify` → resolves to the **latest** loaded
  version (highest semver). This is a convenience for non-critical deployments.

The engine stores specs in a registry keyed by `id + version`, not just `id`.

## Consequences

Positive:
- Gradual migration: different routes pin to different spec versions. No big-bang
  upgrades required. Supports canary deployments.
- Explicit over implicit: profiles with `@version` are self-documenting.
- Concurrent versions: engine loads and holds multiple versions simultaneously.

Negative / trade-offs:
- Every spec version change requires updating the referencing profile(s). This is
  intentional friction — it prevents accidental upgrades.
- Semver parsing needed for "latest" resolution. Simple string comparison is
  insufficient (`1.10.0 > 1.9.0`).
- Bare `spec: id` (no version) is convenient but less predictable. Production profiles
  SHOULD always use explicit versions.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-05)
- Validating scenarios: S-001-41 (concurrent versions), S-001-42 (missing version rejected),
  S-001-43 (bare spec resolves to latest)
