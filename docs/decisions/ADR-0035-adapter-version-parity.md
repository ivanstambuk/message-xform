# ADR-0035 – Adapter Version Parity

Date: 2026-02-12 | Status: Accepted

## Context

Gateway adapter modules (currently Feature 002 / PingAccess, potentially Kong,
Envoy, or others in the future) depend on libraries and APIs provided by the
target gateway at runtime. ADR-0031 established that these dependencies are
declared `compileOnly` and version-locked to the gateway's shipped versions.

ADR-0031 §7 introduced a "version-locked release strategy" where the adapter
version `9.0.x` targets PA `9.0.*`. This works, but the version relationship
is only documented, not encoded in the version number itself. An operator
deploying adapter `9.0.3` must consult release notes to confirm which PA
version it targets — the answer isn't self-evident.

Several mature plugin ecosystems solve this by making the version number itself
carry the compatibility signal:

| Ecosystem | Pattern | Example |
|-----------|---------|---------|
| Elasticsearch plugins | Plugin version = ES version | `analysis-icu:8.12.0` for ES `8.12.0` |
| Kubernetes client-go | Minor version = K8s minor | `client-go@v0.29.0` for K8s `1.29` |
| IntelliJ plugins | `since-build` / `until-build` | Plugin build targets IDE build number |
| Terraform providers | Provider version tracks TF version range | `hashicorp/aws:5.x` for TF `1.x` |

### Options Considered

- **Option A – Version parity with adapter patch segment** (chosen)
  - Adapter version = `<GATEWAY_MAJOR>.<GATEWAY_MINOR>.<GATEWAY_PATCH>.<ADAPTER_PATCH>`
  - First 3 segments mirror the gateway version exactly.
  - 4th segment is the adapter's own iteration counter (bugfixes, features).
  - Example: PA `9.0.1` → adapter `9.0.1.0`, `9.0.1.1`, `9.0.1.2`.
  - Pros: zero-ambiguity compatibility signal. Operator sees adapter `9.2.0.0`,
    knows it's for PA `9.2.0`. Independent adapter patching is still possible.
  - Cons: adapter version resets on every gateway release (minor friction).

- **Option B – Documented version lock** (current ADR-0031 approach — superseded)
  - Adapter version `9.0.x` with a documented mapping to PA `9.0.*`.
  - Pros: simpler version scheme.
  - Rejected: the compatibility signal is in docs, not in the version. Easy to
    miss during upgrades. No automated enforcement.

- **Option C – Compatibility metadata in JAR manifest** (rejected)
  - Adapter uses its own semver. Compatibility is declared via
    `PA-Min-Version` / `PA-Max-Version` in `MANIFEST.MF`.
  - Pros: decoupled versioning, flexible range expression.
  - Rejected: requires tooling to read the manifest. Operators can't tell
    compatibility from the version number alone. Over-engineered for a
    single-gateway target.

Related ADRs:
- ADR-0031 – PA-Provided Dependencies (§7 version-locked strategy → superseded by this ADR)
- ADR-0032 – Core Anti-Corruption Layer (core engine is unaffected — own semver)

## Decision

We adopt **Option A – Version parity with adapter patch segment**.

### Version scheme

```
adapter version = <GATEWAY_MAJOR>.<GATEWAY_MINOR>.<GATEWAY_PATCH>.<ADAPTER_PATCH>
                   └──────────── mirrors gateway ────────────┘   └─ our patches ─┘
```

### Concrete rules

1. **First 3 segments mirror the target gateway version exactly.** When PA
   ships `9.2.0`, our adapter's first release is `9.2.0.0`. The adapter MUST
   be compiled against the exact dependency versions shipped in that gateway
   release (per ADR-0031 `compileOnly` pattern).

2. **4th segment (`ADAPTER_PATCH`) is the adapter's own iteration.** Starts
   at `0` for each new gateway version. Incremented for adapter bugfixes,
   feature additions, or documentation changes that don't require a gateway
   upgrade.

3. **Gateway upgrade = new adapter version line.** When PA goes from `9.0.1`
   to `9.2.0`, we start a new adapter version line (`9.2.0.0`). The previous
   line (`9.0.1.x`) may still receive critical patches if needed.

4. **Core engine version is independent.** The core engine (`core/`) follows
   its own semantic versioning. The adapter module depends on a specific core
   version via its Gradle dependency declaration. The gateway version parity
   applies only to the adapter module.

5. **Applies to all gateway adapters.** Future adapters (Kong, Envoy, etc.)
   follow the same pattern: adapter version mirrors the target gateway
   version, with a 4th segment for adapter patches.

### Gateway upgrade workflow

```
1. New PA version ships (e.g., 9.2.0)
2. Pull new Docker image
3. Run scripts/pa-extract-deps.sh → regenerates pa-provided.versions.toml
4. Review version diff (patch = compatible, minor = re-test, major = review)
5. Run full test suite (./gradlew clean check)
6. Bump adapter version to 9.2.0.0
7. Tag release: adapter-pingaccess-9.2.0.0
```

### Runtime misdeployment guard (simplified)

The runtime version guard from ADR-0031 §8 is **simplified** to a single
misdeployment check: if the adapter's compiled-against gateway version
(baked into `META-INF/message-xform/pa-compiled-versions.properties`)
doesn't match the detected runtime gateway version, log a WARN:

```
WARN: Adapter compiled for PA 9.0.1 but running in PA 9.2.0.
      Deploy adapter version 9.2.0.x for this PA instance.
```

No severity matrix, no patch/minor/major distinction — just a mismatch
warning with a clear remediation message. The version parity scheme makes
the fix obvious.

### Example version timeline

```
PA 9.0.1 ships         → adapter-pingaccess 9.0.1.0  (initial)
Adapter bugfix          → adapter-pingaccess 9.0.1.1  (our fix)
Another adapter fix     → adapter-pingaccess 9.0.1.2  (our fix)
PA 9.2.0 ships         → adapter-pingaccess 9.2.0.0  (new PA deps)
Adapter improvement     → adapter-pingaccess 9.2.0.1  (our improvement)
PA 9.2.1 ships (patch)  → adapter-pingaccess 9.2.1.0  (updated PA deps)
```

## Consequences

Positive:
- Zero-ambiguity compatibility signal — version number IS the compatibility
  contract. No documentation lookup needed.
- Automatic upgrade signal — PA 9.2 upgrade? Operator knows to deploy
  adapter 9.2.x. No guessing.
- Simplified runtime guard — misdeployment check instead of version drift
  severity matrix.
- Ecosystem-aligned — follows Elasticsearch, Kubernetes, and IntelliJ patterns.
- Future-proof — applies uniformly to all gateway adapters.

Negative / trade-offs:
- Adapter version resets on each gateway release (minor mental overhead).
- Cannot express "works with PA 9.0 through 9.2" in the version number — each
  gateway version gets its own adapter version line. This is intentional:
  untested compatibility ranges are worse than explicit version targeting.
- Parallel maintenance burden if supporting multiple PA versions simultaneously
  (e.g., 9.0.1.x and 9.2.0.x). Mitigated by only supporting the latest PA
  minor release unless contractually required.

## References

- `docs/architecture/features/002/spec.md` (FR-002-09)
- `docs/architecture/features/002/scenarios.md` (S-002-35a)
- `docs/decisions/ADR-0031-pa-provided-dependencies.md`
