# Feature <NNN> — <Title> — Tasks

Status: Draft | Last updated: YYYY-MM-DD

**Governing spec:** `docs/architecture/features/<NNN>/spec.md`
**Implementation plan:** `docs/architecture/features/<NNN>/plan.md`

## Task Checklist

Tasks are ordered by dependency. Each task references the spec requirement it
implements and sequences tests before code.

### Phase 1 — <Title>

- [ ] **T-001:** <Description>
  - Spec: FR-001-XX
  - Test first: Write failing test for <behaviour>
  - Implement: <what to build>
  - Verify: S-001-XX passes

- [ ] **T-002:** <Description>
  - Spec: FR-001-YY
  - Test first: ...
  - Implement: ...
  - Verify: ...

### Phase 2 — <Title>

- [ ] **T-003:** ...

## Completion Criteria

- [ ] All tasks checked off
- [ ] Quality gate passes
- [ ] Coverage matrix in `scenarios.md` has no uncovered FRs/NFRs
- [ ] Implementation Drift Gate report attached to plan
- [ ] Open questions resolved and removed from `open-questions.md`
