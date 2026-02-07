---
description: Session handover — preserve work-in-progress state for the next session
---

# /handover — Session Handover

Perform this when ending a session with incomplete work.

## Steps

### 1. Assess Uncommitted State
// turbo
Run `git status` to check for uncommitted work.
- If there are meaningful changes, commit them (WIP commit is acceptable).
- Ensure nothing is lost.

### 2. Quick SDD Hygiene Check
Before handing over, do a fast sanity check:
// turbo
- Run `rg "Open" docs/architecture/open-questions.md` to count remaining open questions.
- Check if any decisions were made in chat but not yet captured in ADRs or specs.
- If quick fixes (< 5 min) are possible, apply them now. Otherwise, note them
  in the handover.

### 3. Update Session State
// turbo
Update `docs/_current-session.md` with:
- **Active Work**: What feature/spec was being worked on.
- **Session Progress**: Key decisions made, ADRs created, specs modified.
- **Remaining Open Questions**: Which Q-IDs are still open.
- **Blocking Issues**: Anything preventing progress.
- **Key Decisions This Session**: Table of decisions, options chosen, and ADR IDs.

This is the **primary** handover artifact. It persists across sessions and is
read by `/init`.

### 4. Create Pending Task File
Write `.agent/session/pending-task.md` with the following structure:

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

**Constraint**: Keep this file under 100 lines. It's a breadcrumb, not a novel.

### 5. Verify Handover
// turbo
- Confirm `pending-task.md` exists and is committed.
- Confirm `docs/_current-session.md` is updated and committed.
- Confirm no uncommitted code changes remain.

### 6. Report
Tell the user:
- What was saved in the handover file and session state.
- Any SDD gaps noted for the next session.
- That the next session will pick it up via `/init`.
