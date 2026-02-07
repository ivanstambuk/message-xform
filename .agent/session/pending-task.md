# Pending Task

**Focus**: Feature 001 spec refinement — resolving medium-severity open questions
**Status**: All 4 high-severity questions resolved (Q-010–Q-013). Working through 6 medium-severity questions (Q-014–Q-019). Q-014 Decision Card was presented but not yet answered.
**Next Step**: Get user's answer on Q-014 (mapperRef invocation model), then continue through Q-015–Q-019.

## Context Notes
- Q-014 Decision Card was already shown to user — Option A (transform-level sequential directive only) recommended. Await answer.
- Resolution Checklist (AGENTS.md Rule 8) is now mandatory: spec → ADR → knowledge-map → llms.txt → scenarios → open-questions → commit.
- CFG-001-04 (reload-interval) was intentionally removed from core config — it's an adapter concern. ID gap is intentional and stable.
- NFR-001-05 was narrowed to core-only (atomic registry swap). Reload triggers are adapter concerns.

## SDD Gaps (if any)
- None identified — retro audit was clean after fixes applied.
- Note: medium-severity questions (Q-014–Q-019) may or may not need ADRs — use judgement per Resolution Checklist.

## Questions Ready to Present
- Q-014: mapperRef invocation model (Decision Card already shown)
- Q-015: match block spec-vs-profile overlap
- Q-016: Unidirectional direction semantics
- Q-017: $status availability for requests
- Q-018: Large/streaming body handling
- Q-019: sensitive field YAML syntax
