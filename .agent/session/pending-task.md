# Pending Task

**Focus**: Feature 004 — Standalone HTTP Proxy Mode, Phase 3 I4 (UpstreamClient)
**Status**: I3 complete (all 5 tasks done), I4 starting
**Next Step**: Implement T-004-09 — UpstreamClient basic forwarding (FR-004-01)

## Context Notes
- ProxyConfig fully implemented with builder, env var overlay, and validation
- ConfigLoader maps YAML → ProxyConfig via Jackson YAML (manual JsonNode traversal)
- Javalin 6.7.0 confirmed = Jetty 11
- Need mock HTTP backend for UpstreamClient tests (WireMock or similar)
- JDK HttpClient for upstream forwarding (zero extra deps)

## Tasks in I4
1. T-004-09: UpstreamClient basic forwarding (GET/POST, response intact)
2. T-004-10: HTTP/1.1 enforcement (FR-004-33)
3. T-004-11: Content-Length recalculation (FR-004-34)
4. T-004-12: Hop-by-hop header stripping (FR-004-04)
5. T-004-13: Backend error handling (FR-004-24/25)
6. T-004-14: Connection pool configuration (FR-004-18)

## After I4: I5 — StandaloneAdapter + ProxyHandler
- T-004-15 through T-004-25
