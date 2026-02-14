# Pending Task

**Focus**: Feature 002 — PingAccess Adapter — Phase 4 (Session Context Binding)
**Status**: Phases 1–3 complete (18 tasks). Phase 4 is next.
**Next Step**: Implement T-002-18 (merge identity layers L1–L3 into SessionContext)

## Context Notes
- T-002-14 (cleanup hooks) is blocked on I8 (reload scheduler) — intentionally deferred.
- `buildTransformContext()` accepts a `SessionContext` parameter — Phase 4 will
  implement the identity → session merge that produces it.
- PingAccess `Exchange.getIdentity()` returns `IdentityMappingContext` which provides
  OAuth metadata, identity attributes, and session state. Need to decompile/javap
  this interface to understand the full API before implementing T-002-18.
- The coverage matrix in scenarios.md is complete for all implemented FRs.

## Gotchas
- PA SDK `HeaderField` uses `getHeaderName()` not `getName()` (see AGENTS.md pitfall)
- `ErrorHandlerConfiguration` must be inline-implemented (config doesn't implement it)
- Test specs need `input.schema` + `output.schema` blocks even for minimal tests
- `ConfigurationBuilder.from()` is the pattern for `getConfigurationFields()`

## SDD Gaps
- None — all checks passed. Findings ledger was empty.
