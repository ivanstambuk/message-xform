# Pending Task

**Focus**: E2E Test Expansion — Phase 8b or Phase 9
**Status**: Phase 8a complete, choosing next phase
**Next Step**: Decide whether to implement Phase 8b (Web Session OIDC, L4) or
skip to Phase 9 (Hot-Reload). Phase 8b is higher complexity.

## Context Notes
- mock-oauth2-server is already in Docker setup (Phase 8a) — reused by Phase 8b
- PA Identity with Bearer token populates L1-L3 only; L4 needs Web Session cookie
- Phase 8b requires simulating OIDC auth code flow via curl redirect chain
- mock-oauth2-server has a debugger API that may simplify the login simulation
- All unit tests pass (258 tests), build is green
- E2E script syntax-checks clean (`bash -n`)

## SDD Gaps (if any)
- None — all checks passed in retro audit
