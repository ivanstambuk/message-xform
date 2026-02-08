---
description: Session retrospective — review work, verify SDD completeness, and prepare handover for next session
---

# /retro — Session Retrospective & Handover

Perform this at the end of a session or after completing a significant piece of work.
This workflow combines the retrospective audit with session handover — run it once
to close out a session cleanly.

## Steps

### 1. Assess Uncommitted State
// turbo
Run `git status` to check for uncommitted work.
- If there are meaningful changes, commit them (WIP commit is acceptable).
- Ensure nothing is lost.

### 2. Summarize Work Done
Review the session's activity:
// turbo
- Run `git log --oneline` (limit to commits from this session) to list all changes.
- Summarize the key changes, decisions made, and features/specs modified.
- Note any deviations from the original plan or approach.

### 3. SDD Completeness Audit

Verify that all work from the session is fully captured per the SDD methodology.
Run through each check and report findings. This is the core of the retrospective.

#### 3a. Scenario Coverage Check
// turbo
- Open `docs/architecture/features/*/scenarios.md` for any features touched.
- Review the **coverage matrix** at the bottom.
- Check: does every FR and NFR added/modified this session have at least one
  validating scenario? If not, flag it.
- Check: did any discussion in this session reveal an edge case that should be a
  scenario but isn't? Flag it.

#### 3b. Terminology Check
// turbo
- Open `docs/architecture/terminology.md`.
- Review the session's commits and chat for any new concepts or terms introduced.
- Check: are all new terms defined in the terminology doc? If not, flag them.
- Check: were any "avoid" terms from the canonical term map used in new docs?
  Search with `rg` if needed.

#### 3c. ADR Completeness Check
// turbo
- Review the session for any medium- or high-impact design decisions that were made.
- Check: does every such decision have a corresponding ADR in `docs/decisions/`?
  If not, flag it.
- Check: do all ADRs reference their validating scenarios? If not, flag it.
- Check: are all ADR statuses correct (Proposed/Accepted/Deprecated/Superseded)?

#### 3d. Open Questions Hygiene
// turbo
- Open `docs/architecture/open-questions.md`.
- Check: are there any rows for questions that were actually resolved this session
  but not yet removed? If yes, flag it — resolved questions must not remain.
- Check: were any new questions discussed but not logged? If yes, flag it.

#### 3e. Spec Consistency Check
// turbo
- For any specs modified this session, verify:
  - FR/NFR IDs are sequential and unique.
  - All requirements have: ID, requirement text, driver, measurement, dependencies, source.
  - Cross-references to ADRs are correct.

### 4. Knowledge Map & References Sync
// turbo
- Open `docs/architecture/knowledge-map.md`.
- Check: if new modules, ADRs, or concepts were introduced, are they reflected?
- Open `llms.txt` and verify any new high-signal docs are listed.

### 5. Capture Learnings
Identify anything that should be persisted. **Every learning listed here MUST be
written to a file before the retro is complete** — chat-only bullets are failures.
Per AGENTS.md rule 17, target locations are:
- **Library/API quirks** (unexpected syntax, error behaviors, threading gotchas) →
  the relevant `docs/research/` document. Create one if none exists.
- **Tooling/process pitfalls** (build, formatting, CI, editor interactions) →
  `AGENTS.md` "Known Pitfalls" section.
- **New conventions** (patterns or rules that emerged) → `AGENTS.md` operational rules.
- **Plan updates** (vision, features, or priorities shifted) → update `PLAN.md`.
- **Constitution changes** (new principle or governance rule) →
  propose an update to `docs/decisions/project-constitution.md`.

### 6. Apply Fixes
// turbo
Apply any fixes identified in the SDD audit:
- Add missing scenarios.
- Add missing terminology entries.
- Create missing ADRs.
- Remove resolved open questions.
- Update knowledge map and llms.txt.
- Update `AGENTS.md` with any new rules or conventions.
- Update `PLAN.md` if scope, priorities, or technical decisions changed.
- Commit all fixes.

### 7. Update Session State & Handover
// turbo
- Update `docs/_current-session.md` with session progress, key decisions,
  and remaining work.
- Write `.agent/session/pending-task.md` with the following structure:

```markdown
# Pending Task

**Focus**: [What was being worked on]
**Status**: [Where it stands — e.g., "halfway through implementing X"]
**Next Step**: [The immediate next action to take]

## Context Notes
- [Key decisions made but not yet documented]
- [Gotchas encountered]
- [Any temporary state or workarounds in place]

## SDD Gaps (if any)
- [Missing scenarios]
- [Missing terminology entries]
- [Missing ADRs]
- [Unresolved open questions that were discussed]
```

**Constraint**: Keep `pending-task.md` under 100 lines. It's a breadcrumb, not a novel.

### 8. Verify & Commit
// turbo
- Confirm `pending-task.md` exists and is committed.
- Confirm `docs/_current-session.md` is updated and committed.
- Confirm no uncommitted code changes remain (`git status` is clean).

### 9. Report
Present a concise retrospective to the user:
- **Done**: What was accomplished.
- **Learned**: Key takeaways or decisions.
- **SDD Audit**: Summary of completeness checks and any fixes applied.
  - Scenarios: ✅/⚠️ (count of gaps found and fixed)
  - Terminology: ✅/⚠️
  - ADRs: ✅/⚠️
  - Open Questions: ✅/⚠️
  - Spec Consistency: ✅/⚠️
- **Changed**: What docs were updated and why.
- **Open**: Anything left unresolved or worth revisiting.
- **Handover**: What's saved for the next session (pending-task summary).
  Note that the next session will pick it up via `/init`.
