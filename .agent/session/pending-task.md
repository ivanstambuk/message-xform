# Pending Task

**Focus**: WebAuthn passkey E2E tests — completed
**Status**: All 3 passkey scenarios passing (7/7 total E2E scenarios)
**Next Step**: Push to origin, update PLAN.md passkey task status, Phase 9 documentation

## Context Notes
- `auth-passkey.feature` has 3 scenarios: full registration+auth, device-exists auth, unsupported fallback
- `webauthn.js` is a pure JDK-based FIDO2 helper (EC P-256, CBOR, no external deps)
- `delete-device.feature` is a reusable device cleanup helper via AM Admin API
- The Python script `/tmp/webauthn_test.py` was a throwaway debugging tool — safe to delete
- `karate-config.js` was updated with `passkeyTestUser` (user.4) and journey params

## Key Debugging Findings
1. **Root cause of auth loop**: ConfirmationCallback set to 0 = "Use Recovery Code" → wrong journey branch
2. **legacyData escaping**: ONE level of `"` → `\"` escaping. Double = HTTP 401
3. **allowCredentials regex**: Nested `Int8Array([...])` breaks naive bracket matching
4. **Device API version**: `resource=1.0, protocol=1.0` (different from auth API's 2.0)

## SDD Gaps (if any)
- None — this is platform infrastructure, not feature-level SDD work
- All learnings captured in PingAM operations guide §10 and KI webauthn_journey.md
