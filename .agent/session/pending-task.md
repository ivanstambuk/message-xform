# Pending Task

**Focus**: Feature 009 — Toolchain & Quality Platform
**Status**: Spec complete (`docs/architecture/features/009/spec.md`). Plan and tasks not yet created.
**Next Step**: Create `plan.md` and `tasks.md` for Feature 009, then begin implementation.

## Context Notes
- Spec captures 15 FRs: 10 already satisfied (baseline), 4 new (EditorConfig, ArchUnit, SpotBugs, Gradle runbook), 1 passive (version pinning).
- ArchUnit (FR-009-12) is the most impactful new item — 4 rules: module boundaries, core isolation, no-reflection, SPI layering.
- SpotBugs (FR-009-13) is a SHOULD, not MUST — can be deferred.
- EditorConfig (FR-009-11) is the quickest win.
- Modeled on openauth-sim Feature 013 and journeyforge Feature 009.

## SDD Gaps
- None. Retro completed: terminology updated, knowledge map synced, llms.txt updated.

## Alternative Next Steps
- Tier 2 features (002 PingAccess / 003 PingGateway adapters)
- Cross-language portability audit (PLAN.md backlog)
