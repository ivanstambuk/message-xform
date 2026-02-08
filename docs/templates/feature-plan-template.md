# Feature <NNN> — <Title> — Implementation Plan

_Linked specification:_ `docs/architecture/features/<NNN>/spec.md`
_Linked tasks:_ `docs/architecture/features/<NNN>/tasks.md`
_Status:_ Draft
_Last updated:_ YYYY-MM-DD

> Guardrail: Keep this plan traceable back to the governing spec. Reference
> FR/NFR/Scenario IDs from `spec.md` where relevant, log any new high- or
> medium-impact questions in `docs/architecture/open-questions.md`, and assume
> clarifications are resolved only when the spec's normative sections and,
> where applicable, ADRs under `docs/decisions/` have been updated.

## Vision & Success Criteria

Reiterate the user value, measurable success signals, and quality bars.

## Scope Alignment

- **In scope:** Bullet the behaviours/increments covered by this plan.
- **Out of scope:** State exclusions so adjacent workstreams stay unaffected.

## Prerequisites

- [ ] Feature spec is `Status: Ready`
- [ ] All high-/medium-impact open questions resolved
- [ ] Related ADRs are `Status: Accepted`

## Dependencies & Interfaces

| Dependency | Notes |
|------------|-------|
| Module / file / spec | How this plan depends on it |

## Assumptions & Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| … | … | … |

## Implementation Drift Gate

Describe how the drift gate will be executed — what evidence to collect, where
to record results, and which commands to rerun. Include placeholders for
traceability matrices or lessons learned.

### Drift Gate Checklist (for agents)

- [ ] Spec/plan/tasks updated to the current date; all clarifications encoded
      in normative sections (no stale "Clarifications" blocks).
- [ ] `docs/architecture/open-questions.md` has no `Open` entries for this feature.
- [ ] Verification commands have been run and logged in `_current-session.md`.
- [ ] For each FR/NFR, confirm that the implementation matches the spec.
- [ ] Any drift (spec says X, code does Y) is logged as an open question or
      fixed immediately, depending on severity.

### Drift Report — YYYY-MM-DD

_Fill in after each drift gate run._

## Increment Map

Break the feature into ≤90-minute increments. Each increment should identify
prerequisites, deliverables, tests, and commands. **Order tests before code.**

1. **I1 — <Title>** (≤90 min)
   - _Goal:_ Brief description.
   - _Preconditions:_ Specs/tests that must already exist.
   - _Steps:_ Bullet the work items (tests first, then implementation).
   - _Requirements covered:_ FR-<NNN>-XX, NFR-<NNN>-XX
   - _Commands:_ `./gradlew …`
   - _Exit:_ Definition of done for this increment.
2. **I2 — <Title>** (≤90 min)
   - …

Add as many increments as required. For parallel work, split into sub-increments
(I3a/I3b) instead of bloating a single entry.

> **No cascading renumber rule:** When inserting a new increment between existing
> ones, use **letter suffixes** (e.g., I11a, I11b) instead of renumbering all
> downstream increments. This avoids churn in cross-references (scenario tracking,
> tasks, intent log). A dedicated cleanup pass may renumber IDs at the end of a
> feature when the structure is stable. The same rule applies to task IDs
> (T-NNN-36a) and scenario IDs (S-NNN-74a).

## Scenario Tracking

Map each Scenario ID to the increments/tasks that implement it so changes remain
traceable.

| Scenario ID | Increment / Task reference | Notes |
|-------------|---------------------------|-------|
| S-<NNN>-01  | I1 / T-<NNN>-01           | … |

## Analysis Gate

Record when the analysis gate (`docs/operations/analysis-gate-checklist.md`)
was completed, who reviewed it, and any findings that must be addressed before
implementation resumes.

- YYYY-MM-DD — _Gate result and notes._

## Exit Criteria

- [ ] All increments completed and checked off
- [ ] Quality gate passes (`./gradlew spotlessApply check` or equivalent)
- [ ] Scenario coverage matrix in `scenarios.md` has no uncovered FRs/NFRs
- [ ] Implementation Drift Gate report attached
- [ ] Open questions resolved and removed from `open-questions.md`
- [ ] Documentation synced (roadmap, knowledge-map, AGENTS.md if needed)

## Intent Log

Record key prompts, decisions, and validation commands per increment:

- **I1:** …
- **I2:** …

## Follow-ups / Backlog

Capture post-feature investigations, deferred optimisations, or monitoring tasks
so they can be prioritised later.
