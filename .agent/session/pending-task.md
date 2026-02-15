# Pending Task

**Focus**: E2E Phase 11 — Multi-Rule Chain (Final Phase)
**Status**: Not started
**Next Step**: Design PA provisioning for a second rule instance with a
  dedicated spec dir, then implement Test 30 (S-002-27).

## Context Notes
- Phases 9 (hot-reload) and 10 (JMX) are fully committed and pushed.
- All 27 existing E2E scenarios pass (last verified in prior session).
- Phase 11 is the last remaining E2E phase before full coverage.
- Phase 8b (Web Session OIDC) is deferred — requires PingFederate.

## Phase 11 Details

S-002-27: Prior Rule URI Rewrite — validates that when a PA rule rewrites
the request URI before our adapter, the adapter sees the rewritten URI.

Tasks:
- P11-01: PA config — create a second rule instance + app/resource config
- P11-02: Create `e2e-chain-rewrite.yaml` spec for the rewritten path
- P11-03: Test 30 — POST to chain endpoint, verify rewritten URI in transform

Estimated: 1 scenario, 2 assertions

## E2E Assertion Totals
- Current: 73 assertions (Phases 1-7, 9, 10)
- After Phase 11: 75 assertions (projected)
- Phase 8 (OAuth): adds 12+ assertions if/when implemented

## Key Patterns Established
- `JVM_OPTS` for JMX in PA Docker (not `JAVA_OPTS`)
- Identity→marker overwrite for hot-reload testing
- Karate Java interop for JMX (no external tools)
- Wildcard ObjectName queries for portability

## SDD Gaps
- None — all checks passed in retro.
