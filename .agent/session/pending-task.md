# Pending Task

**Focus**: Feature 004 — Phase 3, Increment I5: StandaloneAdapter + ProxyHandler
**Status**: I4 (UpstreamClient) complete with 21 tests. I5 not yet started.
**Next Step**: Begin T-004-15 — implement `StandaloneAdapter` (GatewayAdapter for Javalin Context)

## Context Notes
- UpstreamClient is fully implemented with HTTP/1.1, hop-by-hop stripping,
  Content-Length recalculation, domain exceptions, and connection pool config.
- `forward()` now throws `UpstreamException` (not IOException) — callers must
  catch `UpstreamConnectException` (→502) and `UpstreamTimeoutException` (→504).
- Mock backend strategy: JDK `com.sun.net.httpserver.HttpServer` — no WireMock.
- `plan.md` updated to "Phase 3 — I5", `tasks.md` has T-004-09..14 marked [x].

## Remaining I5 Tasks
- T-004-15: StandaloneAdapter (GatewayAdapter for Javalin Context)
- T-004-16: ProxyHandler: happy path (intercept → transform → forward → transform → return)
- T-004-17: ProxyHandler: error responses (RFC 9457 problem details)
- T-004-18: ProxyHandler: Javalin integration (register handler)
- T-004-19: ProxyHandler: body size limit enforcement

## SDD Gaps
None — all checks passed in retro audit.
