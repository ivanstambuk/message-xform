# Pending Task

**Focus**: Feature 004 — Phase 5, Increment I8: FileWatcher + admin reload
**Status**: I7 complete (health/readiness endpoints). I8 not started.
**Next Step**: Begin T-004-36 (FileWatcher spec file change detection)

## Context Notes
- HealthHandler and ReadinessHandler are implemented and tested (12 tests)
- Endpoint priority proven via Javalin exact-match routing
- ReadinessHandler uses BooleanSupplier for engine state — this will connect
  to TransformEngine.isLoaded() (or equivalent) when ProxyApp bootstrap is
  implemented
- Test fixtures: identity-transform.yaml + wildcard-profile.yaml exist in
  test resources for priority tests

## I8 Tasks
- T-004-36: FileWatcher — WatchService-based hot reload with debounce
- T-004-37: Admin reload endpoint — POST /admin/reload
- T-004-38: Admin endpoint not subject to transforms

## SDD Gaps
- None — all checks passed in retro audit
