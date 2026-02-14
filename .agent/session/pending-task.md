# Pending Task

**Focus**: E2E Test Expansion — Phases 8–11
**Status**: Phases 1–7 complete (50/50 assertions). Phases 8–11 planned, not started.
**Next Step**: Begin Phase 8 (OAuth/Identity E2E) — set up mock-oauth2-server in Docker Compose.

## Context Notes
- `e2e-expansion-plan.md` has full task-level detail for Phases 8–11.
- Phase 8 (OAuth) is the highest-value remaining work — validates `$session.*` in live PA.
- Phase 9 (Hot-Reload) is self-contained — file mutation via `docker cp` + sleep.
- Phase 10 (JMX) requires exposing JMX port — check `PA_JVM_ARGS` env var.
- Phase 11 (Multi-Rule) requires two PA rules in chain — most complex PA config.
- 5 scenarios are unit-only by design (documented in `e2e-results.md` gap analysis).
- Strict unknown-key detection was added to SpecParser (FR-001-01) this session.
- S-001-86 was added for the strict key validation (scenarios.md, coverage matrix).

## SDD Gaps
- None remaining — all retro findings resolved.

## Commits This Session
```
987c3d2 docs(002): add Phases 8-11 to E2E expansion plan
7c5cb4d fix(002): revert E2E plan status, remove review log
bd6b6d8 docs(002): E2E expansion Phase 7 — documentation and coverage matrix
2e36065 fix(001): strict unknown-key detection in SpecParser
```
