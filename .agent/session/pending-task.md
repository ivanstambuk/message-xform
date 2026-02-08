# Pending Task

**Focus**: Phase 7 I14 — Hot reload + atomic registry swap
**Status**: Not started — ready to begin
**Next Step**: Implement T-001-45 (TransformRegistry immutable snapshot)

## Context Notes
- Phase 7 I13 is complete (T-001-41 through T-001-44) — all 4 observability tasks done
- 289 tests passing, clean build
- Engine now has: structured logging, TelemetryListener SPI, sensitive path parsing, MDC trace propagation
- PERF-01..04 backlog lives in `docs/operations/performance-testing.md` (not in feature tasks)

## Next Steps (in order)
1. T-001-45 — Create `TransformRegistry` as immutable data class
2. T-001-46 — Implement `TransformEngine.reload()` with atomic registry swap
3. T-001-47 — Add TelemetryListener notifications for reload events
4. T-001-48 — Define `GatewayAdapter` SPI interface

## SDD Gaps
- None identified. All scenarios, terminology, ADRs, and specs are consistent.
