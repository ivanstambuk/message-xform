# ADR-0018 – Body Buffering Is an Adapter/Gateway Concern

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-04 defines the `Message` interface with `getBodyAsJson()` returning a `JsonNode`.
The question arose whether the core engine spec should mandate full-body buffering by
adapters and impose a configurable `max-input-bytes` limit to prevent OOM.

### Options Considered

- **Option A – Mandate buffering with `max-input-bytes`** (rejected)

  The core spec mandates adapters buffer the full body and adds a size limit config.

  Rejected because: body-size limits, OOM protection, and buffering strategy are
  **non-functional gateway concerns** — not the transformation engine's responsibility.
  Adding such parameters to the core spec crosses the boundary between the engine
  (which transforms JSON) and the infrastructure (which manages resources).

- **Option B – No mandate; leave to adapter/gateway** (chosen)

  The core engine spec does NOT prescribe buffering, size limits, or memory management.
  The `Message` interface contract is simple: `getBodyAsJson()` returns a complete
  `JsonNode`. How the adapter obtains that `JsonNode` (buffering strategy, size limits,
  streaming cutoff) is entirely the adapter's and gateway's responsibility.

  Pros:
  - ✅ Clean separation: engine = transform JSON, gateway = manage resources
  - ✅ No core config parameters for infrastructure concerns
  - ✅ Each adapter can leverage its gateway's native body-size controls

  Cons:
  - ❌ No portable size contract across adapters (acceptable — gateways already have this)

- **Option C – Streaming via chunked JsonNode** (rejected)

  Over-engineered, incompatible with JSLT and ADR-0011 (JsonNode model).

Related ADRs:
- ADR-0011 – Jackson JsonNode Body Representation
- ADR-0013 – Copy-on-Wrap Message Adapter

## Decision

We adopt **Option B – body buffering and size limits are NOT a core engine concern**.

The core engine's contract is:
- **Input:** a complete `JsonNode` (provided by the adapter via `Message.getBodyAsJson()`).
- **Output:** a transformed `JsonNode`.

The engine does NOT specify, mandate, or configure:
- How the adapter buffers the body.
- Maximum body size.
- OOM protection strategies.

These are gateway-level NFRs handled by the adapter's gateway configuration (e.g.,
PingAccess request body limits, Kong `client_max_body_size`, NGINX `client_max_body_size`).

## Consequences

Positive:
- Core engine spec stays focused on transformation — no infrastructure leakage.
- Adapters leverage their gateway's native body-size controls.

Negative / trade-offs:
- If an adapter implementer forgets to configure gateway-level limits, the engine
  could receive very large JsonNodes. This is the adapter implementer's responsibility.

Follow-ups:
- Add a note to FR-001-04 clarifying that body buffering is an adapter concern.
- Adapter feature specs (Feature 002+) SHOULD document their gateway's body-size
  configuration.
