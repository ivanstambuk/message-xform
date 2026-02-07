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

### 2. Create Pending Task File
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
```

**Constraint**: Keep this file under 100 lines. It's a breadcrumb, not a novel.

### 3. Verify Handover
// turbo
- Confirm `pending-task.md` exists and is committed.
- Confirm no uncommitted code changes remain.

### 4. Report
Tell the user:
- What was saved in the handover file.
- That the next session will pick it up via `/init`.
