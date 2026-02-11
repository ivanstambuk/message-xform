# ADR-0023 – Cross-Profile Routing Is Product-Defined

Date: 2026-02-08 | Status: Accepted

## Context

ADR-0006 defines most-specific-wins match resolution for entries **within a single
profile**. The question arose (Q-027): what happens when two **different** profile
files both match the same incoming request?

For example, `pingam-auth.yaml` and `pingam-legacy.yaml` both contain entries
matching `POST /json/*/authenticate`. Should the engine detect this conflict, resolve
it via specificity, or treat it as an error?

### Key insight

The engine is a plugin/rule/filter embedded inside a **gateway product** (PingAccess,
Kong, PingGateway, standalone proxy). The gateway product already owns request routing
through its own **deployment model**:

- In PingAccess, an adapter rule is bound per API operation, context root, or globally.
  Multiple adapter instances may coexist, each with its own profile configuration.
  The gateway product determines which rule fires for which request.
- In Kong, a plugin is attached to a specific route or service.
- In a standalone proxy, the deployment configuration maps routes to profiles.

Whether multiple profiles can apply to the same request — and in what order — is
determined by the gateway product's routing semantics, not by the engine. Attempting
to detect or resolve cross-profile conflicts in the engine would:

1. Duplicate routing logic the gateway product already owns.
2. Require the engine to understand the gateway product's deployment model (which
   varies per product).
3. Impose constraints that may conflict with the gateway product's intended behaviour.

### Options Considered

- **Option A – Flat pool with cross-profile load-time validation** (rejected)
  - Merge all entries from all loaded profiles into a single pool. Apply ADR-0006
    specificity scoring across profiles. Ties → load-time error.
  - Pros: consistent algorithm; conflicts always caught at load time.
  - Cons: teams can't author profiles independently; adding a new profile can break
    an existing one; the engine assumes it knows the full routing picture (it doesn't —
    the gateway product controls invocation).

- **Option B – Profile-level priority field** (rejected)
  - Add a `priority: <integer>` field to profiles for cross-profile disambiguation.
  - Pros: teams can work independently if priorities are coordinated.
  - Cons: re-introduces the explicit priority pattern ADR-0006 already rejected;
    arbitrary numbers are hard to manage at scale.

- **Option C – Profile isolation (implicit cross-profile chaining)** (rejected)
  - All matching profiles execute independently in file-load order.
  - Pros: zero coordination needed.
  - Cons: silent double-application; execution order depends on file naming; very
    hard to debug.

- **Option D – Cross-profile routing is product-defined** (chosen)
  - The engine does not detect, resolve, or define behaviour for cross-profile
    conflicts. Each engine invocation processes one profile. Whether multiple profiles
    can apply to the same request is a deployment model concern owned by the gateway
    product.
  - Pros: clean separation of concerns; consistent with the engine's role as a
    stateless transform evaluator; no coupling to gateway-specific routing semantics.
  - Cons: the engine cannot warn about cross-profile overlaps. This is acceptable —
    the gateway product's admin tooling (PingAccess admin console, Kong Manager, etc.)
    is the right place for such validations.

## Decision

We adopt **Option D – Cross-profile routing is product-defined**.

1. **ADR-0006 scope is intra-profile only.** Most-specific-wins match resolution
   applies to entries within a single profile. The engine does not apply this
   algorithm across profiles.

2. **Each engine invocation processes one profile.** The gateway adapter passes a
   single profile (or a single set of matched entries) to the engine per request.
   The engine does not need to resolve which profile to use.

3. **Cross-profile routing is product-defined.** Whether multiple profiles can apply
   to the same request, and in what order, is determined by the gateway product's
   deployment model. This varies per product:
   - PingAccess: rule binding at API operation / context root / global level.
   - Kong: plugin attachment to route or service.
   - Standalone: deployment configuration.

4. **The engine does not detect cross-profile conflicts.** If a gateway product's
   deployment model allows two adapter instances to process the same request with
   different profiles, the resulting behaviour is product-defined. The engine has
   no visibility into this and does not attempt to prevent it.

5. **Operational responsibility.** Preventing cross-profile overlap is the gateway
   administrator's responsibility, enforced through the gateway product's own
   configuration validation and admin tooling — not through the engine.

## Consequences

Positive:
- Clean separation of concerns: the engine is a stateless transform evaluator;
  the gateway product owns routing.
- No coupling to gateway-specific deployment models in the core engine.
- Simpler engine: no cross-profile conflict detection, no cross-profile scoring.
- Teams authoring profiles for different gateway products don't need to worry about
  engine-level conflicts.

Negative / trade-offs:
- The engine cannot warn about cross-profile overlaps. Operators must rely on the
  gateway product's admin tooling for conflict detection.
- If a gateway product allows double-invocation and the administrator doesn't
  prevent it, transforms may apply twice silently. This is the administrator's
  responsibility to prevent.

References:
- ADR-0006 – Most-Specific-Wins Profile Match Resolution (intra-profile scope)
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-05)
- `docs/architecture/terminology.md` – gateway product, deployment model, product-defined
