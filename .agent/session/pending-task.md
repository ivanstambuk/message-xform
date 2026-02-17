# Pending Task

**Focus**: K8s E2E Test Validation — Phase 5 of platform PLAN
**Status**: 9/14 scenarios pass. Steps 5.1–5.5 complete. Steps 5.6–5.8 remain.
**Next Step**: Run passkey E2E tests on K8s (auth-passkey.feature, auth-passkey-usernameless.feature)

## Context Notes
- `run-e2e.sh --env k8s` works — starts port-forwards, runs Karate, cleans up
- `lowerCaseResponseHeaders = true` is enabled globally; all header assertions use lowercase keys
- Docker env (`./run-e2e.sh` without `--env k8s`) still works — verified
- PA Admin password on K8s is `2Access` (product-level override, not global)
- Passkey tests need WebAuthn device registration which hasn't been verified on K8s yet
- All documentation and KIs updated for the K8s E2E learnings

## SDD Gaps (if any)
- None — all checks passed (session was infrastructure/ops work, no features touched)
