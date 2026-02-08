# Pending Task

**Focus**: Feature 004 — Phase 4: Error Handling + Body Size + Forwarded Headers (I6)
**Status**: I5 complete. Ready to begin I6.
**Next Step**: Start T-004-26 (error handling integration tests)

## Context Notes
- ProxyHandler fully implements passthrough, request/response/bidirectional
  transforms, dispatch table, and X-Request-ID generation/echo
- Content-length/transfer-encoding headers are now filtered from upstream
  responses to prevent body truncation when transforms change body size
- bad-transform.yaml uses JSLT `error()` for runtime errors — do NOT use
  compile-time-broken JSLT (fails at spec load time, not transform time)
- ProxyTestHarness provides shared infrastructure for all proxy integration tests
- All 56 proxy tests pass (passthrough + transform cycles)

## SDD Gaps (if any)
- Feature 004 scenarios.md with coverage matrix deferred until feature nears
  completion (scenarios are tracked in spec.md inline)
