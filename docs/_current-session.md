# Current Session — Feature 004 Implementation (Phase 3)

**Date:** 2026-02-08T22:53+01:00
**Feature:** 004 — Standalone HTTP Proxy Mode
**Phase:** Phase 3 — Core Proxy: Handler + Upstream Client (I4 starting)

## Progress

### Phase 1 ✅ DONE
- T-004-01: 3-arg `transform()` overload in core engine (6 tests)
- T-004-02: Scaffold `adapter-standalone` Gradle submodule
- T-004-03: Zero gateway-dependency verification (2 tests)

### Phase 2 ✅ DONE
- T-004-04: ProxyConfig record hierarchy (8 tests)
- T-004-05: Config test fixtures (3 files)
- T-004-06: YAML config loader (8 tests)
- T-004-07: Environment variable overlay (47 tests)
- T-004-08: Config validation (19 tests)

### Phase 3 🔧 IN PROGRESS (I4)
- T-004-09: UpstreamClient basic forwarding — NOT STARTED
- T-004-10: HTTP/1.1 enforcement — NOT STARTED
- T-004-11: Content-Length recalculation — NOT STARTED
- T-004-12: Hop-by-hop header stripping — NOT STARTED
- T-004-13: Backend error handling — NOT STARTED
- T-004-14: Connection pool configuration — NOT STARTED

## Key Decisions
- Javalin 6.7.0 ships Jetty 11, not Jetty 12. All docs corrected.
- ProxyConfig uses flat backend fields + Builder (no separate BackendConfig record).
  Nested records: TlsConfig (inbound), BackendTlsConfig (outbound), PoolConfig.

## Test Count
- 90 tests across Phase 1+2 (all GREEN)
- Full `spotlessApply check` GREEN
