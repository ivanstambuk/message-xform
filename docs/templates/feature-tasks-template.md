# Feature <NNN> — <Title> — Tasks

_Status:_ Draft
_Last updated:_ YYYY-MM-DD

**Governing spec:** `docs/architecture/features/<NNN>/spec.md`
**Implementation plan:** `docs/architecture/features/<NNN>/plan.md`

> Keep this checklist aligned with the feature plan increments. Stage tests
> before implementation, record verification commands beside each task, and
> prefer bite-sized entries (≤90 minutes).
>
> When referencing requirements, keep feature IDs (`FR-`), non-functional IDs
> (`NFR-`), and scenario IDs (`S-<NNN>-`) in parentheses after the task title.
>
> When new high- or medium-impact questions arise during execution, add them to
> `docs/architecture/open-questions.md` instead of informal notes, and treat a
> task as fully resolved only once the governing spec sections and, when
> required, ADRs under `docs/decisions/` reflect the clarified behaviour.

## Task Checklist

Tasks are ordered by dependency. Each task references the spec requirement it
implements and **sequences tests before code** (Rule 12 — TDD cadence).

### Phase 1 — <Title>

- [ ] **T-<NNN>-01** — <Task title> (FR-<NNN>-XX, S-<NNN>-XX)
  _Intent:_ What this task delivers and why it matters.
  _Test first:_ Write failing test for <behaviour>.
  _Implement:_ <what to build to make the test green>.
  _Verify:_ S-<NNN>-XX passes.
  _Verification commands:_
  - `./gradlew --no-daemon :<module>:test --tests "…"`
  - `./gradlew spotlessApply check`
  _Notes:_ Link to related spec sections or follow-ups.

- [ ] **T-<NNN>-02** — <Task title> (FR-<NNN>-YY)
  _Intent:_ …
  _Test first:_ …
  _Implement:_ …
  _Verify:_ …
  _Verification commands:_ …

### Phase 2 — <Title>

- [ ] **T-<NNN>-03** — …

## Verification Log

Track long-running or shared commands (full quality gate, integration suites,
etc.) with timestamps to avoid duplicate work.

- YYYY-MM-DD — `./gradlew --no-daemon spotlessApply check` (result, duration)

## Completion Criteria

- [ ] All tasks checked off
- [ ] Quality gate passes
- [ ] Coverage matrix in `scenarios.md` has no uncovered FRs/NFRs
- [ ] Implementation Drift Gate report attached to plan
- [ ] Open questions resolved and removed from `open-questions.md`

## Notes / TODOs

Document temporary skips, deferred tests, or environment quirks so the next
agent or session can follow up.
