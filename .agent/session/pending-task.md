# Pending Task

**Focus**: Feature 004 — Standalone HTTP Proxy Mode implementation planning
**Status**: Spec review complete, ready for implementation planning
**Next Step**: Create `docs/architecture/features/004/plan.md` (phased implementation plan)
  and `docs/architecture/features/004/tasks.md` (granular task breakdown)

## Context Notes
- Feature 004 spec is now comprehensive: 36 FRs, 8 NFRs, 59 scenarios, 40 config entries
- All open questions resolved (Q-029 through Q-038)
- Two decisions made this session:
  - Q-037: `max-body-bytes` applies to both request and response (Option A)
  - Q-038: Configurable `X-Forwarded-*` headers, default enabled (Option C)
- New FRs added: FR-004-06b (response metadata), FR-004-36 (forwarded headers)
- New config: CFG-004-39 (drain timeout), CFG-004-40 (forwarded headers enabled)
- Key gap fixed: response Message must carry request path/method for profile matching

## SDD Gaps
- None — retro audit passed, all findings fixed and committed
