# Pending Task

**Focus**: Feature 002 — PingAccess Adapter Implementation
**Status**: Spec review complete (all 22 findings resolved). Ready to begin coding.
**Next Step**: Create implementation plan / tasks.md for Feature 002.

## Context Notes
- Spec (`docs/architecture/features/002/spec.md`) has been hardened through a 22-item review
- SDK guide (`docs/architecture/features/002/pingaccess-sdk-guide.md`) is comprehensive (2394 lines, 100% class coverage)
- Scenarios (`docs/architecture/features/002/scenarios.md`) cover 30 test cases (S-002-01 through S-002-30)
- Review tracker (`docs/architecture/features/002/spec-review-tracker.md`) documents all findings and fixes

## Key Spec Corrections (most impactful for implementation)
- `handleResponse()` MUST have a DENY guard (check `TRANSFORM_DENIED` ExchangeProperty)
- `TransformContext` is immutable — build two instances per exchange (request + response)
- Use `body.read()` before `getContent()` for streamed bodies
- Use `asMap()` (Java SDK), NOT `getAllHeaderFields()` (Groovy-only)
- Protected headers (`content-length`, `transfer-encoding`) excluded from header diff
- `applyChanges` uses two internal helpers, not the SPI method directly
- `ExchangeProperty` uses typed `TransformResultSummary` record, not `Map<String, Object>`

## SDD Gaps (if any)
- None — all gaps resolved during retro
