# Pending Task

**Focus**: Platform deployment — Step 8.6 complete
**Status**: All 9 phases done, 9/9 E2E scenarios passing, Step 8.6 (passkey JSLT transforms) complete
**Next Step**: Evaluate remaining deferred items or move to core engine work

## Context Notes
- Platform deployment PLAN.md is fully green (Phase 1–9 complete)
- Step 8.6 completed: `am-webauthn-response` spec using JSLT `capture()` regex
- Profile updated to v3.0.0 with ADR-0036 `match.when` body predicate routing
- Key insight: JSLT DOES have regex (`capture()`, `test()`, `replace()`) — Approach A worked
- Raw challenge bytes passed through as signed Int8Array string (no base64url encoding needed)

## Deferred Items (optional future work)
- **Step 8.8**: Clean URL routing — separate PA apps for `/api/v1/auth/passkey` and `/api/v1/auth/passkey/usernameless`
- **D14**: Full OIDC-based PA Web Sessions — requires AM OAuth2 provider + PA OIDC configuration
