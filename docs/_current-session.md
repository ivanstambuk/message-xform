# Current Session — 2026-02-09

**Feature**: 004 — Standalone HTTP Proxy Mode
**Phase**: 4 — Error Handling + Body Size + Forwarded Headers
**Increment**: I6 — Error handling, body size, X-Forwarded-* headers

## Session Status

### Completed Tasks (I6)
- [x] **T-004-26** — RFC 9457 error responses (5/5 tests)
- [x] **T-004-27** — Backend error responses (3/3 tests)
- [x] **T-004-28** — Non-JSON body rejection (4/4 tests)

### Remaining I6 Tasks
- [ ] T-004-29 — Request body size enforcement (FR-004-13, S-004-22)
- [ ] T-004-30 — Response body size enforcement (FR-004-13, S-004-56)
- [ ] T-004-31 — X-Forwarded-* headers (FR-004-36, S-004-40/41/42/43)

## Key Decisions
- `ProblemDetail` utility handles proxy-level RFC 9457 errors; core
  `ErrorResponseBuilder` handles transform errors. Clean separation.
- Non-JSON body handling: `wrapRequestRaw()`/`wrapResponseRaw()` build
  Messages with NullNode body for profile matching without JSON parse.
  Profile match → 400; no match → passthrough.
- Fixed PASSTHROUGH branch to reuse already-parsed `requestMessage.headers()`
  instead of re-calling `wrapRequest()`.

## Commit
- `33b76d6` — feat(adapter): error handling — RFC 9457, backend errors, non-JSON rejection (T-004-26/27/28)
