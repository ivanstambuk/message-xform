# Pending Task

**Focus**: Feature 004, Phase 8 — I12: Full integration test sweep + scenario coverage matrix
**Status**: I11 (Docker packaging) complete. I12 ready to start.
**Next Step**: T-004-55 — Create integration test fixtures (FX-004-04/05)

## Context Notes
- All 54 tasks through I11 are complete and committed
- Docker image (`message-xform-proxy`) builds at 80.8 MB via 3-stage jlink Dockerfile
- The existing test suite covers unit + integration tests per module
- I12 needs a full scenario sweep and a `scenarios.md` coverage matrix (like Feature 001)
- ConfigLoader YAML paths: `engine.specs-dir`, `engine.profiles-dir` (NOT `specs.dir`)
- Env var names: `BACKEND_HOST`, `SPECS_DIR`, `PROFILES_DIR` etc. (no prefix)

## Remaining Tasks (I12)

1. T-004-55 — Test fixtures for integration tests
2. T-004-56 — Full integration test sweep (77 scenarios)
3. T-004-57 — `scenarios.md` with coverage matrix
4. T-004-58 — NFR verification benchmarks
5. T-004-59 — Drift gate report
6. T-004-60 — Update knowledge-map.md

## SDD Gaps
- None — all checks passed in retro audit
