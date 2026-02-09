# Pending Task

**Focus**: Feature 004 — Phase 5 (Health, Readiness, Hot Reload, Admin)
**Status**: Phase 4 complete; Phase 5 not started
**Next Step**: Begin T-004-33 — Health endpoint test + implementation

## Context Notes
- Phase 4 (I5 + I6) is fully complete: error handling, body size limits,
  X-Forwarded-* headers, and method rejection are all implemented and tested.
- ProxyHandler constructor now takes 5 args: engine, adapter, upstreamClient,
  maxBodyBytes, forwardedHeadersEnabled. All test files have been updated.
- Method rejection uses a Javalin `before` filter (not ProxyHandler) because
  Javalin returns 404 for unregistered methods — see AGENTS.md Known Pitfalls.
- `UpstreamResponseTooLargeException` added to exception hierarchy.

## Upcoming Tasks (I7)
- T-004-33 — Health endpoint (`/healthz`)
- T-004-34 — Readiness endpoint (`/readyz`)
- T-004-35 — Health/readiness path exclusion from proxy routing

## SDD Gaps
- None identified. All scenarios, terminology, and ADRs are current.
