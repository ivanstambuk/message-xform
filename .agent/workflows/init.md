---
description: Session initialization — load project context, assess state, and propose next steps
---

# /init — Session Initialization

Perform this at the start of every AI session.

## Steps

### 1. Load Project Context
// turbo
Read the core governance files to prime context:
- `AGENTS.md` — Project rules, conventions, and key references.
- `PLAN.md` — Architecture, features, and open questions.
- `docs/decisions/project-constitution.md` — Non-negotiable SDD principles.
- `docs/architecture/terminology.md` — Canonical term definitions.
- `llms.txt` — Manifest of high-signal specs (know what's available).

### 2. Load Session State
// turbo
Check for previous session state (read in this order):
- `docs/_current-session.md` — Primary session state. Read this first for the
  big picture: active work, key decisions, remaining questions, blocking issues.
- `.agent/session/pending-task.md` — Granular next-step breadcrumb from last session.
  If it exists, treat it as the immediate action to resume.
- If neither exists: no carry-over — proceed to state analysis.

### 3. Assess Project State
// turbo
- Run `git status` and `git log -5 --oneline` to understand recent activity.
- Scan the repository structure for any new or changed files.

### 3.5. Codebase Size Report
// turbo
Run the CLOC report script to show lines-of-code statistics:
```bash
scripts/cloc-report.sh .
```
Include the output table in the summary. This shows:
- **Production code** (src/main/java) vs **Test code** (src/test/java)
- Files, lines, comments, blanks breakdown
- Test-to-total ratio

If `cloc` is not installed, note it as a gap and continue.

### 4. Present Summary
Report to the user:
- **Project context**: Brief recap of what message-xform is and its current state.
- **Pending work**: Any carry-over from a previous session (from `_current-session.md`
  and/or `pending-task.md`).
- **Recent changes**: What happened in the last few commits.

### 5. Check Open Questions
// turbo
Read `docs/architecture/open-questions.md`. If any rows with status `Open` exist:
- Count them and note which features they belong to.
- These take priority in the next step.

### 6. Quick SDD State Check
// turbo
Do a fast health check of the SDD artifacts:
- Are there any open questions that appear resolved but weren't removed?
  (Search for questions referenced in ADRs that still sit in `open-questions.md`.)
- Check `docs/architecture/knowledge-map.md` — is it up to date with the latest
  ADRs and features?
- Note any SDD hygiene issues for the user.

### 7. Propose Next Steps
Offer 2–4 concrete options for the session based on:
- **Open questions first**: If open questions exist, the default first option MUST
  be to resolve them (one by one, per Rule 8 in AGENTS.md). Open questions block
  spec finalization.
- **SDD gaps**: If the `/retro` flagged missing scenarios, terminology, or
  ADRs, propose fixing those.
- Pending tasks from the previous session.
- Open items in `PLAN.md` (research, features, decisions).
- Any issues discovered during state assessment.

Ask the user which direction to take.
