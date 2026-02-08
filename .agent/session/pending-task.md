# Pending Task

**Focus**: Feature 004 Phase 4 I6 — Error handling, body size, X-Forwarded-* headers
**Status**: 3/6 tasks complete (T-004-26, T-004-27, T-004-28)
**Next Step**: Implement T-004-29 — Request body size enforcement

## Context Notes
- `ProblemDetail.java` already has `bodyTooLarge()` factory method ready for T-004-29/30.
- Request body size enforcement (T-004-29) should use Jetty's
  `HttpConfiguration.setMaxRequestContentSize()` per spec.
- Response body size enforcement (T-004-30) should check in `UpstreamClient`
  after reading the response body.
- X-Forwarded-* headers (T-004-31) should be injected in `ProxyHandler`
  before forwarding to backend.

## Key Files Modified This Session
- `ProxyHandler.java` — try-catch for UpstreamException, non-JSON body handling
- `StandaloneAdapter.java` — added wrapRequestRaw(), wrapResponseRaw()
- `ProblemDetail.java` — NEW: proxy-level RFC 9457 error builder
- `Rfc9457ErrorTest.java` — NEW: 5 tests
- `BackendErrorTest.java` — NEW: 3 tests
- `NonJsonBodyTest.java` — NEW: 4 tests

## SDD Gaps
- Feature 004 missing standalone `scenarios.md` (pre-existing; scenarios in spec.md)
