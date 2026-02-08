# Pending Task

**Focus**: Feature 004 — Standalone HTTP Proxy Mode implementation planning
**Status**: Spec is Ready (all open questions resolved). No plan or tasks exist yet.
**Next Step**: Create `docs/architecture/features/004/plan.md` (phased implementation plan) and `docs/architecture/features/004/tasks.md` (granular task breakdown).

## Context Notes
- Spec has 37 FRs, 7 NFRs, 69 scenarios — comprehensive and reviewed against actual code
- Key design choice: `applyChanges` is response-only; request → UpstreamClient directly reads from Message
- Cookie binding ($cookies) is IN scope for v1 (Q-041 Option B)
- Body size enforcement uses Jetty `maxRequestContentSize` for chunked safety (Q-040)
- Config key is `proxy.max-body-bytes` not `backend.max-body-bytes` (Q-039)
- The core engine already passes null for cookies in TransformContext (line 407 of TransformEngine.java) — this needs to be updated when implementing the adapter
- No separate scenarios.md — all scenarios are inline in spec.md
- Knowledge map and roadmap both show Feature 004 as "Spec Ready"

## SDD Gaps
- None identified. All checks passed in retro audit.
