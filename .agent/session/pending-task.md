# Pending Task

**Focus**: Platform deployment — all phases complete
**Status**: All 9 phases done, 9/9 E2E scenarios passing
**Next Step**: Evaluate deferred items or move to core engine work

## Context Notes
- Platform deployment PLAN.md is fully green (Phase 1–9 complete)
- D11 revised: embedded webauthn.js replaced the planned openauth-sim dependency
- UsernamelessJourney requires UsernameCollector in fallback path (error→username→password→DataStore)
- userHandle = base64(username) in discoverable credential assertions — identifier-first doesn't check this

## Deferred Items (optional future work)
- **Step 8.6**: Passkey JSLT transform specs — convert raw AM WebAuthn callbacks to clean JSON API
  - Challenge: JSLT lacks regex/base64; extracting challenge from embedded JS is fragile
  - Options: pure JSLT string ops (fragile), or add custom JSLT function (10-line Java, requires JAR rebuild)
  - Proposal was drafted in this session — user said "let's do /retro first"
- **Step 8.8**: Clean URL routing — separate PA apps for `/api/v1/auth/passkey` and `/api/v1/auth/passkey/usernameless`
- **D14**: Full OIDC-based PA Web Sessions — requires AM OAuth2 provider + PA OIDC configuration
