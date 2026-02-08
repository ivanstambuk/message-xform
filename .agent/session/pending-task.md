# Pending Task

**Focus**: Feature 004 — Standalone HTTP Proxy Mode implementation
**Status**: Planning complete. Ready to begin coding.
**Next Step**: Start Phase 1 — Implement I1 (T-004-01: add 3-arg `transform()`
overload to core engine) followed by I2 (T-004-02: Gradle module scaffold).

## Context Notes
- Spec is finalized (`docs/architecture/features/004/spec.md`) — 39 FRs, 7 NFRs.
- Plan created (`plan.md`) — 8 phases, 12 increments, 77 scenarios tracked.
- Tasks created (`tasks.md`) — 60 tasks with TDD cadence and verification commands.
- Q-042 resolved: 3-arg `transform(Message, Direction, TransformContext)` overload.
  The 2-arg method delegates to 3-arg with empty context. ~10 lines of core change.
- Q-043 resolved: `$queryParams` in-scope for v1 via `TransformContext`.
- No open questions remain.
- Phase 1 I1 is the entry point: modify `TransformEngine.java` in `core/` module,
  then run all Feature 001 tests to verify zero regressions.

## SDD Gaps (if any)
- None — all gaps from retro audit resolved and committed.
