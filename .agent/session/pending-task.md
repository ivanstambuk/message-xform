# Pending Task

**Focus**: T-004-20 — ProxyHandler: passthrough cycle
**Status**: Not started — StandaloneAdapter is feature-complete, ProxyHandler is next
**Next Step**: Write `ProxyHandlerPassthroughTest` integration test with mock backend

## Context Notes
- StandaloneAdapter has all 3 GatewayAdapter methods + buildTransformContext()
- `buildTransformContext(ctx)` extracts cookies (cookieMap) and query params (queryParamMap)
- Response headers read from `ctx.res()` (servlet response), not `ctx.headerMap()`
- UpstreamClient is fully implemented (T-004-11..14) with hop-by-hop stripping,
  Content-Length recalc, backend error handling, connection pool config
- T-004-20 requires integration test: Javalin server + mock backend + HTTP client
- Spec requires: passthrough GET/POST, all 7 methods, headers, query string, full path
- Test architecture: Test ← HTTP → Proxy ← HTTP → Mock Upstream

## Dependencies for T-004-20
- ProxyHandler class (IMPL-004-02) — new class to create
- Wire: StandaloneAdapter + TransformEngine + UpstreamClient
- For passthrough: no profile matches → forward unmodified → return unmodified
- FR-004-35 dispatch table: REQUEST PASSTHROUGH → raw bytes forwarded

## SDD Gaps
- None identified in retro audit
