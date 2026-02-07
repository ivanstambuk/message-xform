# Analysis Gate Checklist

Status: Draft | Last updated: 2026-02-07

Execute this checklist once spec, plan, and tasks exist for a feature — before
implementation begins. The gate ensures alignment between governance artifacts.

## Pre-Implementation Gate

### 1. Specification Readiness

- [ ] Feature spec exists at `docs/architecture/features/<NNN>/spec.md`
- [ ] Spec has `Status: Ready` (or has been reviewed by owner)
- [ ] All FRs have unique IDs (`FR-NNN-XX`)
- [ ] All NFRs have unique IDs (`NFR-NNN-XX`)
- [ ] Each FR/NFR has: requirement text, driver, measurement, dependencies, source
- [ ] Terminology used matches `docs/architecture/terminology.md`

### 2. Open Questions

- [ ] All high-impact open questions for this feature are resolved
- [ ] All medium-impact uncertainties are resolved or explicitly deferred
- [ ] Resolved questions have been removed from `docs/architecture/open-questions.md`
- [ ] Decisions are captured in spec normative sections + ADRs where applicable

### 3. ADR Alignment

- [ ] All ADRs referenced by the spec are `Status: Accepted`
- [ ] ADR consequences/follow-ups have been actioned or tracked
- [ ] No conflicting ADRs exist

### 4. Scenario Coverage

- [ ] Scenarios exist in `features/<NNN>/scenarios.md`
- [ ] Coverage matrix maps every FR and relevant NFR to at least one scenario
- [ ] Uncovered requirements are explicitly noted with rationale

### 5. Plan & Tasks

- [ ] Implementation plan exists at `features/<NNN>/plan.md`
- [ ] Tasks checklist exists at `features/<NNN>/tasks.md`
- [ ] Each task references the spec requirement it implements
- [ ] Tasks are ordered by dependency, tests sequenced before code
- [ ] Each phase is scoped to ≤90 minutes

## Implementation Drift Gate (post-implementation)

Execute this gate before marking a feature complete:

- [ ] Every FR has corresponding implementation and tests
- [ ] Every NFR has corresponding implementation and/or verification
- [ ] No implementation exists without documented intent (spec requirement)
- [ ] Coverage matrix has no uncovered FRs/NFRs (or explicit rationale)
- [ ] Quality gate passes
- [ ] Drift report attached to plan (brief: matches, gaps, speculative work)
- [ ] Any divergences logged as open questions or follow-up tasks
