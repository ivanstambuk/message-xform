# Pending Task

**Focus**: Platform E2E tests — passkey flows (Phase 8.10/8.11)
**Status**: 4/6 Phase 8 scenarios complete; passkey tests not started
**Next Step**: Set up `openauth-sim` FIDO2 authenticator simulator, then implement passkey E2E tests

## Context Notes
- Platform stack is running (PA, AM, PD all healthy as of 2026-02-17 08:00)
- PD cert must be re-imported into AM's JVM truststore if AM container was recreated
- WebAuthn journey (`WebAuthnJourney`) already imported into AM; `UsernamelessJourney` config exists but import not verified
- Transform specs v2 (`am-auth-response-v2` + `am-strip-internal`) are deployed and chained in PA
- Karate standalone JAR (1.4.1) is downloaded in `deployments/platform/e2e/`

## Important Gotchas for Next Session
- Clear Karate cookie jar before cross-domain calls (`* configure cookies = null`)
- Custom headers from message-xform are lowercase (`x-auth-session`)
- Standard HTTP headers keep original casing (`Set-Cookie`)
- `authId` JWT must be echoed back verbatim in request body (D9: response-only transforms)

## Implementation Reference
- PLAN.md has full FIDO2 ceremony pseudocode in "Implementation Notes for Steps 8.10/8.11"
- Decision D11 defines `openauth-sim` as the authenticator emulator
- WebAuthn callbacks use `HiddenValueCallback` with JSON-encoded challenge data

## SDD Gaps (if any)
- None — all checks passed (terminology, ADRs, open questions, spec consistency)
