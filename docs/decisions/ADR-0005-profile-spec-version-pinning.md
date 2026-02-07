# ADR-0005 – Profiles Pin to Spec Versions via id@version

Date: 2026-02-07 | Status: Accepted

## Context

Transform specs (`TransformSpec`) have an `id` and a `version` field. Transform
profiles (`FR-001-05`) reference specs to bind them to URL patterns. Without version
pinning, updating a spec affects all routes that reference it — making gradual
migration impossible.

Real-world scenario: `callback-prettify@1.0.0` is deployed on route A. A breaking
v2 change is needed for route B. Without version pinning, deploying v2 would break
route A. With pinning, both versions coexist.

## Decision

Profiles reference specs using **`id@version`** syntax (e.g., `callback-prettify@2.0.0`).
Multiple versions of the same spec MAY be loaded concurrently as separate compiled
`TransformSpec` instances.

- **With version**: `spec: callback-prettify@1.0.0` → resolves to exact version.
  Engine fails fast if that version is not loaded.
- **Without version**: `spec: callback-prettify` → resolves to the **latest** loaded
  version (highest semver). This is a convenience for non-critical deployments.

The engine stores specs in a registry keyed by `id + version`, not just `id`.

## Consequences

- **Gradual migration**: Different routes can pin to different spec versions. No
  big-bang upgrades required.
- **Concurrent versions**: The engine must support loading multiple versions of the
  same spec simultaneously. Memory cost is negligible (specs are small).
- **Explicit over implicit**: Profiles that use `@version` are self-documenting —
  you know exactly which spec version each route uses.
- **Latest-version fallback**: Bare `spec: id` references are convenient but less
  predictable. Production profiles SHOULD use explicit versions.
- **Semver parsing**: The engine must parse version strings for ordering (latest
  resolution). Simple string comparison is insufficient — `1.10.0 > 1.9.0`.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-05)
- Validating scenarios: S-001-41, S-001-42, S-001-43
