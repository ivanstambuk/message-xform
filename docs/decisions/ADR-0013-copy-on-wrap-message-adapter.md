# ADR-0013 – Copy-on-Wrap Message Adapter Semantics

Date: 2026-02-07 | Status: Accepted

## Context

The `Message` interface (FR-001-04) has mutators (`setBodyFromJson`, `setHeader`,
`setStatusCode`). Gateway adapters must decide whether to **copy** the native
message or **wrap** it directly. This choice has critical implications for:

- **Pipeline chaining** (ADR-0012): intermediate mutations must be safe.
- **Abort-on-failure**: the engine must be able to cleanly discard all changes.
- **Thread safety**: some gateways (PingGateway) share response objects across filters.

## Decision

Adapters MUST create a **mutable copy** of the gateway-native message when wrapping:

- Deep-copy of `JsonNode` body (`JsonNode.deepCopy()`).
- Copy of headers map (`Map.copyOf()` or equivalent).
- Snapshot of status code.

The core engine mutates this copy freely during transformation. After successful
completion (including all chain steps), the adapter applies the final state back
to the native message via `GatewayAdapter.applyChanges(Message, native)`.

On abort-on-failure, the adapter **does not call `applyChanges()`** — the native
message remains completely untouched.

```
wrapResponse(native) → Message (copy)
  ↓
Engine mutates Message (body, headers, status)
  ↓
if success: applyChanges(Message, native) → writes back to native
if failure: discard Message copy → native untouched
```

### Performance impact

- Body deep-copy of a 50KB `JsonNode`: ~0.1ms (negligible vs 5ms latency budget).
- Header copy: sub-microsecond.
- Memory: briefly holds two copies during transformation. Acceptable for
  messages within the `max-output-bytes` limit (default: 1MB).

### Rejected alternatives

- **Live wrapper**: adapter wraps native message without copying. Reads/writes go
  directly to the native object. Rejected because undo-on-failure is extremely
  difficult, concurrent gateway filters may observe partial mutations, and the
  adapter must track and reverse every mutation — negating the "no copy" benefit.

- **Configurable per adapter**: each adapter chooses copy or wrapper. Rejected
  because the core engine cannot rely on either semantic, error handling becomes
  adapter-dependent, and the testing matrix explodes.

## Consequences

- Every adapter implementation must perform a deep copy in `wrapRequest`/`wrapResponse`.
- `GatewayAdapter.applyChanges()` becomes the single point where mutations reach
  the native message — making it easy to audit and test.
- Pipeline chaining (ADR-0012) works naturally: each step mutates the copy,
  final result applied once at the end.
- Memory usage is bounded by `max-output-bytes` × 2 per concurrent request.

## Related

- ADR-0011 — Jackson JsonNode as internal body representation
- ADR-0012 — Sequential pipeline with abort-on-failure
- FR-001-04 — Message envelope (normative copy-on-wrap semantics)
- NFR-001-01 — Stateless per-request
- S-001-58 — Copy-on-wrap abort rollback scenario
