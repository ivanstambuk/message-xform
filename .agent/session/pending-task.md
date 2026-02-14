# Pending Task

**Focus**: E2E Test Verification
**Status**: Tests rewritten with stronger assertions but not yet run against live PA
**Next Step**: Run `scripts/pa-e2e-test.sh` to verify all tests pass with the new
bidirectional spec and raw-body echo backend

## Context Notes
- `e2e-rename.yaml` changed from unidirectional to bidirectional (forward/reverse)
- Echo backend now returns raw request body (no `{"echo":{...}}` envelope)
- Test 1 now asserts actual field values (`user_id`, `first_name`, etc.) instead
  of just "is JSON"
- Direct echo probe added (bypasses PA) to verify request-side transform
- Rule 17 in AGENTS.md now has three tiers — future features should create
  SDK + operations guides following the same pattern as Feature 002

## SDD Gaps (if any)
- None identified — all retro checks passed clean
