---
description: Session initialization — load project context, assess state, and propose next steps
---

# /init — Session Initialization

Perform this at the start of every AI session.

## Steps

### 1. Load Project Context
// turbo
Read the core project files to prime context:
- `AGENTS.md` — Project rules and conventions.
- `PLAN.md` — Architecture, features, and open questions.

### 2. Check for Pending Work
// turbo
Check if `.agent/session/pending-task.md` exists.
- **If it exists**: Read it and treat it as the primary source of truth for the last session's unfinished work.
- **If not**: No carry-over — proceed to state analysis.

### 3. Assess Project State
// turbo
- Run `git status` and `git log -5 --oneline` to understand recent activity.
- Scan the repository structure for any new or changed files.

### 4. Present Summary
Report to the user:
- **Project context**: Brief recap of what message-xform is and its current state.
- **Pending work**: Any carry-over from a previous session.
- **Recent changes**: What happened in the last few commits.

### 5. Check Open Questions
// turbo
Read `docs/architecture/open-questions.md`. If any rows with status `Open` exist:
- Count them and note which features they belong to.
- These take priority in the next step.

### 6. Propose Next Steps
Offer 2–4 concrete options for the session based on:
- **Open questions first**: If open questions exist, the default first option MUST be to resolve them (one by one, per Rule 8). Open questions block spec finalization.
- Pending tasks from the previous session.
- Open items in `PLAN.md` (research, features, decisions).
- Any issues discovered during state assessment.

Ask the user which direction to take.
