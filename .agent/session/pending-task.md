# Pending Task

**Focus**: Platform deployment — WebAuthn passkey testing + message-xform plugin wiring
**Status**: Phase 6 partially complete (journey imported, not yet tested with passkeys)
**Next Step**: Test passkey registration flow with a browser or openauth-sim (Phase 6.5)

## Context Notes
- WebAuthn journey is live and responding to `authIndexType=service&authIndexValue=WebAuthnJourney`
- Journey walks correctly through Username → WebAuthn Auth → Password → DataStore → WebAuthn Registration
- The registration step returns 3 callbacks (TextOutput JS + HiddenValueCallback)
- Completing the ceremony requires a WebAuthn client (browser or sim)
- Users `user.1` through `user.10` exist with password `Password1` — none have registered devices yet
- After Phase 6, next is Phase 7 (message-xform plugin into PingAccess)

## Key Patterns Established
- All AM API calls: `-H "Host: am.platform.local:18080"` + `http://127.0.0.1:18080`
- All curl: `-s` (never `-sf`)
- Admin auth: always explicit `authIndexType=service&authIndexValue=ldapService`
- Tree import: nodes first, then tree (via REST API, not frodo)

## SDD Gaps (if any)
- None — this was infrastructure/platform work, not feature development
