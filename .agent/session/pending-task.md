# Pending Task

**Focus**: Execute E2E expansion plan (Phase 1–7)
**Status**: Plan created and reviewed — ready to execute
**Next Step**: Phase 1 — create spec files, profile, enhance echo backend

## Context Notes
- Plan is at `docs/architecture/features/002/e2e-expansion-plan.md`
- Phase 2 is the riskiest phase (profile routing activation + regression gate)
- `engine_request()` helper needs upgrade before Phase 3 (header capture, extra headers)
- DENY app setup (Phase 6) requires second PA application + rule
- Profile must include entries for BOTH `/api/error/**` and `/deny-api/error/**`
  (path mismatch bug found in adversarial review)

## Key Technical Notes
- Without profile, engine picks first spec arbitrarily (line 462, TransformEngine)
- PA's contextRoot is part of the request path seen by the rule plugin
- Echo backend needs Content-Type mirroring (currently hardcodes application/json)
- `bad-transform.yaml` pattern (JSLT `error()`) already proven in adapter-standalone tests

## SDD Gaps
- None — retro checks passed clean
