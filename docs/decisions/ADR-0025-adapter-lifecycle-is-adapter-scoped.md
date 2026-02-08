# ADR-0025 – Adapter Lifecycle is Adapter-Scoped

Date: 2026-02-08 | Status: Accepted

## Context

The `GatewayAdapter` SPI (SPI-001-04/05/06) defines three per-request methods:
`wrapRequest`, `wrapResponse`, and `applyChanges`. The question arose whether the
SPI should also define lifecycle methods — `initialize()`, `shutdown()` — to give
adapters a common structure for engine startup, teardown, and reload.

Each target gateway has a fundamentally different lifecycle model:
- **PingAccess**: `configure(PluginConfiguration)` + `@PostConstruct` + Spring DI
- **PingGateway**: filter `init(FilterConfig)` + servlet lifecycle
- **Kong**: per-worker Lua `init_worker()` + `access()` phases
- **Standalone**: `main(String[] args)` bootstrap

The `TransformEngine` API (API-001-01 through API-001-05) already exposes all the
operations an adapter needs for lifecycle management:
- `loadSpec(Path)` — load and compile a transform spec
- `loadProfile(Path)` — load a transform profile
- `reload()` — atomic hot-reload (NFR-001-05)
- `registerEngine(ExpressionEngine)` — register a pluggable expression engine

ADR-0024 defines the error contract for lifecycle operations:
`TransformLoadException` and its subtypes are thrown during load/reload and must
be surfaced by the adapter during startup.

### Options Considered

- **Option A – Add lifecycle methods to GatewayAdapter SPI** (rejected)
  - Add `GatewayAdapter.initialize(EngineConfig)` and `GatewayAdapter.shutdown()`.
  - Pros: common lifecycle structure.
  - Cons: forces a false abstraction over fundamentally different gateway frameworks.
    The generic `EngineConfig` would need to accommodate every gateway's config format.
    Contradicts NFR-001-05 ("reload triggers are adapter concerns, NOT core").

- **Option B – Separate AdapterLifecycle SPI** (rejected)
  - New optional `AdapterLifecycle` interface separate from `GatewayAdapter`.
  - Pros: opt-in.
  - Cons: same fundamental problem — lifecycle input differs per gateway. Adds SPI
    complexity for questionable benefit.

- **Option C – Lifecycle is adapter-scoped** (chosen)
  - Don't add lifecycle methods to `GatewayAdapter`. Each adapter calls the existing
    `TransformEngine` API from within its gateway-native lifecycle hooks.
  - Pros: no false abstraction, consistent with NFR-001-05 and ADR-0023, zero SPI
    surface increase. Error contract already covered by ADR-0024.
  - Cons: no enforced lifecycle structure across adapters.

Related ADRs:
- ADR-0023 – Cross-Profile Routing is Product-Defined (product-level orchestration)
- ADR-0024 – Error Type Catalogue (load-time error contract)

## Decision

We adopt **Option C – Lifecycle is adapter-scoped, not core SPI**.

The `GatewayAdapter` SPI remains focused on per-request operations only
(`wrapRequest`, `wrapResponse`, `applyChanges`). Lifecycle management —
engine initialization, shutdown, reload triggering — is the adapter's
responsibility using the existing `TransformEngine` public API.

Concretely:
1. **No new methods** are added to `GatewayAdapter`.
2. Each adapter calls `TransformEngine.loadSpec()`, `loadProfile()`,
   `registerEngine()` from within its gateway-native lifecycle hook
   (e.g., PingAccess `configure()`, PingGateway `init()`, standalone `main()`).
3. Adapters catch `TransformLoadException` (ADR-0024) during initialization
   and translate it to the gateway's native error/failure mechanism.
4. Reload triggers are adapter concerns (NFR-001-05) — adapters call
   `TransformEngine.reload()` when the gateway signals a configuration change.
5. The spec adds a non-normative **Adapter Lifecycle Guidance** note to
   the Gateway Adapter SPI section documenting this pattern.

## Consequences

Positive:
- SPI stays minimal — three per-request methods, no lifecycle coupling.
- Each adapter uses its gateway's natural lifecycle, avoiding abstraction mismatch.
- Fully consistent with NFR-001-05 and ADR-0023 (adapter/product-defined concerns).
- Error contract for lifecycle errors already exists (ADR-0024 `TransformLoadException`).

Negative / trade-offs:
- No enforced lifecycle structure — each adapter decides when and how to initialize.
  This is mitigated by per-gateway adapter feature specs (Features 002–008) which
  will document the specific lifecycle integration.
- Slightly more documentation burden per adapter feature spec.

Follow-ups:
- spec.md: add "Adapter Lifecycle Guidance" note to Gateway Adapter SPI section.
- open-questions.md: remove Q-026.
- knowledge-map.md: add ADR-0025 to traceability table.
- llms.txt: add ADR-0025.
- No new scenarios needed — this decision preserves existing behaviour and adds
  non-normative guidance only.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (SPI-001-04/05/06, API-001-01–05, NFR-001-05)
- Q-026: GatewayAdapter SPI lifecycle gaps (resolved by this ADR)
