# Current Session

**Feature**: 004 — Standalone HTTP Proxy Mode
**Phase**: Phase 3 — Core Proxy: Handler + Upstream Client
**Increment**: I5 — StandaloneAdapter + ProxyHandler
**Status**: I4 complete, I5 not started

## Completed This Session

- T-004-09 — UpstreamClient: basic forwarding (6 tests)
- T-004-10 — UpstreamClient: HTTP/1.1 enforcement (1 test)
- T-004-11 — UpstreamClient: Content-Length recalculation (3 tests)
- T-004-12 — UpstreamClient: hop-by-hop header stripping (4 tests)
- T-004-13 — UpstreamClient: backend error handling (4 tests)
- T-004-14 — UpstreamClient: connection pool configuration (3 tests)

**Total**: 6 tasks, 21 tests, 6 commits (d54c624..ccdca69)

## Key Decisions

- Mock backend strategy: JDK `com.sun.net.httpserver.HttpServer` (zero deps)
- Domain exception hierarchy: UpstreamException → UpstreamConnectException, UpstreamTimeoutException
- Content-Length and Host are restricted headers (JDK-managed)
- All 8 RFC 7230 §6.1 hop-by-hop headers filtered in both directions

## Next

I5 — StandaloneAdapter + ProxyHandler (T-004-15..T-004-19):
- T-004-15: StandaloneAdapter (GatewayAdapter for Javalin Context)
- T-004-16: ProxyHandler: happy path
- T-004-17: ProxyHandler: error responses (RFC 9457)
- T-004-18: ProxyHandler: Javalin integration
- T-004-19: ProxyHandler: body size limit enforcement
