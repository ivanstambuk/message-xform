# Pending Task

**Focus**: Feature 004 — Phase 8: Docker Packaging (I11)
**Status**: Ready to start I11 — all Phase 7 work complete
**Next Step**: T-004-51 — Add Shadow plugin + configure fat JAR manifest

## Context Notes
- ProxyApp is the main entry point orchestrator; StandaloneMain.main() wraps it
- logback-classic is now `implementation` scope (not runtimeOnly) because
  LogbackConfigurator uses Logback's programmatic API directly
- Port 0 = ephemeral port (valid for testing and some deployments)
- All I1-I10 increments complete — 50 tasks done
- Full test suite green, spotless clean

## Remaining Phase 8 Tasks (I11 + I12)

### I11 — Dockerfile + Shadow JAR
- T-004-51: Shadow plugin + fat JAR (FR-004-30)
- T-004-52: Dockerfile multi-stage build (FR-004-29)
- T-004-53: Docker image size < 150 MB (NFR-004-04, S-004-48)
- T-004-54: Docker smoke test (S-004-49/50/51)

### I12 — Full integration test sweep
- T-004-55: Create test fixtures (FX-004-04/05)
- T-004-56: Full integration test sweep (all 77 scenarios)
- T-004-57: scenarios.md coverage matrix
- T-004-58: NFR verification

## SDD Gaps
- None — retro audit passed all checks
