# ADR-0012 – Sequential Pipeline with Abort-on-Failure for Profile Chaining

Date: 2026-02-07 | Status: Accepted

## Context

ADR-0008 established that a transform spec defines exactly one expression per direction,
and that mixed-engine composition uses **profile-level chaining** — binding multiple
specs to the same route in a profile. However, the execution semantics of such chains
were unspecified:

- Does the output of spec N feed into spec N+1? (pipeline)
- Or does each spec receive the original message independently? (parallel)
- What happens when a step fails mid-chain?

This matters for correctness (especially mixed-engine chains like JOLT → JSLT) and
for error safety (partial pipeline results must never reach the client).

## Decision

When multiple transform entries within a single profile match the same request,
they form an **ordered pipeline** executed in declaration order:

1. Specs execute in the order they appear in the profile YAML.
2. The **output body** of spec N becomes the **input body** of spec N+1.
3. `TransformContext` (headers, status) is **re-read** from the `Message` envelope
   before each step — header changes from step N are visible to step N+1.
4. **Abort-on-failure**: if any step fails (evaluation error, budget exceeded), the
   **entire chain aborts**. The original, unmodified message passes through. No
   partial pipeline results reach the client.
5. Structured logging includes the chain step index (e.g., `chain_step: 2/3`).

### Rejected alternatives

- **Parallel execution**: each spec receives the original message independently.
  Rejected because body merge semantics are undefined — how do you combine two
  different JSON reshaping results? No real-world system works this way.

- **Sequential with skip-on-failure**: if a step fails, continue with the input
  the failed step received. Rejected because downstream specs may receive an
  unexpected shape, violating the "never return corrupted output" principle (FR-001-07).

## Consequences

- Profile authors must be aware that declaration order determines execution order.
- Debugging chains requires understanding intermediate shapes between steps.
- NFR-001-08 structured logging must include `chain_step` for traceability.
- The abort-on-failure model aligns with copy-on-wrap semantics (ADR-0013) —
  on failure, the copy is discarded and the native message is untouched.

## Related

- ADR-0008 — Single expression per direction (establishes profile-level chaining)
- FR-001-05 — Transform Profiles (normative pipeline semantics)
- S-001-49 — Mixed-engine chain scenario (JOLT → JSLT)
- S-001-56 — Pipeline chain abort-on-failure scenario
