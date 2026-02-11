# ADR-0030 – Session Context Binding (`$session`)

Date: 2026-02-09 | Status: Accepted

## Context

API gateways frequently maintain a **session context** — an arbitrary JSON structure
representing the state of a user or workload session between two entities. Examples
include PingAccess sessions containing validated JWT claims (`sub`, `roles`, `tenant`),
cookie-based session attributes, or gateway policy outputs. Transform expressions
currently cannot access this session data, limiting use cases like injecting a user
ID from the session into the request payload or branching transform logic based on
session attributes.

The core engine already provides several read-only context variables bound into JSLT
expressions:

| Variable | Source | Added by |
|----------|--------|----------|
| `$headers` | HTTP headers (first value) | Feature 001 (FR-001-10) |
| `$headers_all` | HTTP headers (all values) | ADR-0026 |
| `$status` | HTTP status code | Feature 001 (FR-001-11) |
| `$queryParams` | URL query parameters | ADR-0021 |
| `$cookies` | Request cookies | ADR-0021 |

Session context fits the same pattern — a read-only, gateway-provided data source
consumed by transform expressions — but it is fundamentally **not part of the HTTP
message**. It comes from the gateway's session management layer (SDK API, token
introspection, policy output, etc.).

The question (Q-044): how should gateway-provided session context be exposed to
transform expressions?

### Options Considered

- **Option A – `$session` on TransformContext + `sessionContext()` on Message** (chosen)
  - Add a nullable `JsonNode getSessionContext()` to the `Message` interface and a
    corresponding `$session` binding to `TransformContext`. The engine extracts session
    context from `Message` during context building, exactly like headers and status.
    Each gateway adapter populates `sessionContext()` from its native session API.
  - Pros: follows the exact pattern of `$headers`/`$cookies`/`$queryParams`; no API
    signature change on `TransformEngine.transform()`; clean, discoverable variable name.
  - Cons: stretches `Message` abstraction slightly (session ≠ HTTP message); single-purpose
    field — future gateway metadata types would each need their own field.

- **Option B – Generic `$extensions` map on TransformContext** (rejected)
  - Add `Map<String, JsonNode> getExtensions()` to `TransformContext`. Session context
    bound as `$extensions.session`.
  - Pros: future-proof — any new metadata type is just another key.
  - Rejected: less discoverable (`$extensions.session.sub` vs `$session.sub`); bikeshed
    risk on naming; harder to document; `Message` still needs a carrier mechanism.

- **Option C – Separate parameter on `TransformEngine.transform()`** (rejected)
  - Add a 4th argument `JsonNode sessionContext` to `transform()`.
  - Rejected: Q-042 already added a 3-arg overload; 4-arg is unwieldy; diverges from
    the pattern where all context data flows through `Message`.

Related ADRs:
- ADR-0021 – Query Params and Cookies in Context (closest precedent)
- ADR-0020 – Nullable Status Code (nullable context variable precedent)
- ADR-0017 – Status Null for Requests (nullable `$status` for request direction)
- ADR-0025 – Adapter Lifecycle (adapters own session extraction)

## Decision

We adopt **Option A – `$session` on TransformContext + `sessionContext()` on Message**.

### Concrete changes

1. **`Message` interface** (FR-001-04 / DO-001-01) gains:
   ```java
   /** Gateway session context as arbitrary JSON, or null if unavailable. */
   JsonNode getSessionContext();
   void setSessionContext(JsonNode session);
   ```

2. **`TransformContext` interface** (DO-001-07) gains:
   ```java
   /** Gateway session as JsonNode, or null if not provided. ADR-0030. */
   JsonNode getSessionContext();
   ```

3. **JSLT binding:** `$session` is bound as an external variable, following the same
   mechanism used for `$headers`, `$status`, `$queryParams`, and `$cookies`. When
   session context is `null`, `$session` evaluates to `null` in JSLT (JSLT handles
   null gracefully — `$session.sub` returns `null` when `$session` is null).

4. **Direction:** `$session` is available in **both** request and response transforms.
   The session context does not change between request/response processing in the
   same request lifecycle — it's the same session. A response transform may need
   `$session.tenantId` to restructure the response just as much as a request transform.

5. **Engine support matrix** (FR-001-02): `$session` column added. Engines that do
   not support context variables (JOLT) cannot access `$session`. Specs declaring
   `lang: jolt` with `$session` references MUST be rejected at load time.

6. **Mutability:** Read-only. Transforms consume session data but do NOT modify it.
   Writing to session state is a gateway-level concern, not a transform concern.

7. **Adapter responsibilities:**
   - PingAccess (Feature 002): populate via PingAccess SDK session API.
   - PingGateway (Feature 003): populate via PingGateway session/attribute API.
   - Standalone proxy (Feature 004): `null` by default; optionally extract from a
     designated header (e.g., `X-Session-Context: <base64-json>`) — deferred to a
     future Feature 004 extension.
   - Other adapters: gateway-specific.

### Processing order update

The context binding step (step 1 in the processing order) becomes:

```
1. Engine reads metadata → binds $headers, $headers_all, $status,
   $requestPath, $requestMethod, $queryParams, $cookies, $session
```

No other processing order changes — `$session` is bound at the same time as all
other context variables and is available throughout the pipeline.

## Consequences

Positive:
- Completes the context variable family: body, headers, status, URL, cookies,
  query params, and now session — all available in JSLT expressions.
- Enables high-value use cases: user identity injection, tenant-aware transforms,
  role-based response filtering.
- Zero disruption to existing adapters — `getSessionContext()` returns `null` by
  default (Java interface default method or base class implementation).
- Follows the proven pattern from ADR-0021 exactly — minimal new concept surface.

Negative / trade-offs:
- `Message` is conceptually an HTTP message, but now carries session context (which
  is extra-HTTP metadata). Trade-off accepted for pattern consistency.
- Each future gateway metadata type (e.g., `$policy`, `$token`) would need its own
  `Message` field. If this becomes a pattern, ADR-0030 can be revisited with a
  generic extension map.

Validating scenarios:
- S-001-82: `$session.sub` injected into request body (request direction).
- S-001-83: `$session.roles` used in conditional body transform (response direction).
- S-001-84: `$session` is null (no session provided) → null-safe access.
- S-001-85: JOLT engine + `$session` reference → rejected at load time.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-04, DO-001-07)
- ADR-0021: `docs/decisions/ADR-0021-query-params-and-cookies-in-context.md`
- Q-044: Session context binding (resolved by this ADR)
