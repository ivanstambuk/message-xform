# Pending Task

**Focus**: Feature 004 — Standalone HTTP Proxy Mode (Implementation Planning)
**Status**: Specification complete and reviewed. No open questions. Ready for plan.md + tasks.md.
**Next Step**: Create `docs/architecture/features/004/plan.md` and `docs/architecture/features/004/tasks.md`

## Context Notes
- Feature 004 spec has 36 FRs (FR-004-01 through FR-004-35, plus FR-004-06a), 7 NFRs, 55 scenarios
- All 8 open questions (Q-029 through Q-036) resolved and encoded in normative docs
- Key architectural decisions this session:
  - FR-004-06a: ProxyHandler populates Javalin Context with upstream response before wrapResponse
  - FR-004-35: TransformResult dispatch table (SUCCESS/ERROR/PASSTHROUGH × REQUEST/RESPONSE)
  - FR-004-26: Non-JSON body on profile-matched route → 400 Bad Request
  - Admin endpoint security: non-goal for v1 (reload-only, no payload)
- The env var table now has complete coverage (all config keys mappable)
- Content-Length recalculation covers both directions (request + response)
- Chunked transfer encoding is handled by Javalin/Jetty (no proxy-level logic needed)

## Implementation Planning Guidance
- Break into phases: core adapter → proxy handler → upstream client → config → hot reload → health → TLS → Docker
- The adapter (`StandaloneAdapter`) is the reference implementation — keep it simple
- Consider starting with a walking skeleton (FR-004-01 passthrough) then layering transforms

## SDD Gaps
- None — all checks passed in retro audit
