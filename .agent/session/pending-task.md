# Pending Task

**Focus**: T-001-53 — TransformEngineBenchmark (NFR-001-03 verification)
**Status**: Task defined in tasks.md (Phase 9). ADR-0028 accepted. Ready to implement.
**Next Step**: Implement TransformEngineBenchmark.java following the openauth-sim pattern.

## Context Notes
- ADR-0028 decided: hybrid approach — feature-scoped lightweight benchmarks + Feature 009 for heavy infra
- T-001-53 added to Feature 001 tasks.md as Phase 9
- plan.md exit criteria updated to include Phase 9
- docs/operations/performance-testing.md superseded, retained as Feature 009 seed material
- Knowledge map + llms.txt updated with ADR-0028

## Implementation Reference
- Pattern: `openauth-sim/core/src/test/.../MapDbCredentialStoreBaselineBenchmark.java`
- Pattern: `openauth-sim/core-ocra/src/test/.../OcraReplayVerifierBenchmark.java`
- Opt-in flag: `-Dio.messagexform.benchmark=true` or `IO_MESSAGEXFORM_BENCHMARK=true`
- Target: p95 < 5ms for < 50KB payloads (NFR-001-03)
- Scenarios: S-001-53a (identity 1KB), S-001-53b (5-field 10KB), S-001-53c (skip when disabled)

## SDD Gaps
- Scenarios S-001-53a/b/c not yet added to scenarios.md (add when implementing T-001-53)
