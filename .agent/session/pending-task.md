# Pending Task

**Focus**: Feature 002 — PingAccess Adapter implementation
**Status**: Build infrastructure complete; no adapter code yet
**Next Step**: Create `plan.md` and `tasks.md` for Feature 002, then begin
implementing FR-002-01 (GatewayAdapter) and FR-002-02 (AsyncRuleInterceptor).

## Context Notes
- `adapter-pingaccess/` module is scaffolded with compileOnly deps, shadow JAR
  excludes, and version guard — all verified (shadow JAR produces 0 PA classes).
- PA SDK JAR at `libs/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` (gitignored).
  Must be copied from `docs/reference/` or Docker image before building.
- PaVersionGuard.java is the only production class — called from configure().
- Core engine types use TypeScript notation in specs; F002 Java blocks retained
  per §6a exception (SDK-bound types).
- ADR-0035 establishes version parity: adapter version = PA version + patch.
  This means the adapter module version should be set to `9.0.1.0` (not `0.1.0-SNAPSHOT`)
  when actual implementation begins. Currently inherits root project version.

## SDD Gaps
- None — all gaps from retro were resolved inline.

## Remaining Work (Feature 002)
1. Create `plan.md` — break spec into implementation phases
2. Create `tasks.md` — granular task breakdown for Phase 1
3. Implement FR-002-01: PingAccessAdapter (wrapRequest, wrapResponse, applyChanges)
4. Implement FR-002-02: MessageTransformRule (handleRequest, handleResponse)
5. Implement FR-002-04: Plugin configuration with @UIElement
6. Implement FR-002-06: Session context binding from Identity
7. Implement FR-002-11: Error handling (PASS_THROUGH/DENY modes)
8. Implement FR-002-14: JMX observability (opt-in)
