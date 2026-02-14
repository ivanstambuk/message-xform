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

### 3.3. Antigravity Health Check
// turbo
Check for orphaned IDE processes:
```bash
scripts/cleanup-antigravity.sh
```
Report any orphans or ghost processes in the summary.

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

### 3.7. CI Health Check
// turbo
Check the latest GitHub Actions CI run status:
```bash
gh run list --limit 1 --json status,conclusion,name,headBranch,event,createdAt,url \
  | jq '.[0] + {ageMinutes: ((now - (.createdAt | fromdateiso8601)) / 60 | floor)}'
```
The `ageMinutes` field tells you how long ago the run started — use this instead
of comparing `createdAt` (UTC) against the user's local clock.
Interpret the result:
- **`conclusion: success`** → CI is green. Note it in the summary as ✅.
- **`conclusion: failure`** → CI is RED. Flag it prominently in the summary as
  🔴 **CI FAILING**. In step 7, this MUST be the **first suggested action**
  ("Fix CI before anything else"). Offer to pull the failed job logs with
  `gh run view <id> --log-failed`.
- **`status: in_progress`** → CI is still running. Note it as ⏳ in the summary.
- **No runs found / `gh` not authenticated** → Note as unknown, continue.

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
Offer 2–4 concrete options for the session, using this **priority order**:
1. **CI failure first**: If step 3.7 detected a red CI build, the **first option
   MUST be** to investigate and fix the CI failure. Broken CI blocks the team
   and should be addressed before any feature work.
2. **Open questions**: If open questions exist, the next option MUST be to
   resolve them (one by one, per Rule 8 in AGENTS.md). Open questions block
   spec finalization.
3. **SDD gaps**: If the `/retro` flagged missing scenarios, terminology, or
   ADRs, propose fixing those.
4. Pending tasks from the previous session.
5. Open items in `PLAN.md` (research, features, decisions).
6. Any issues discovered during state assessment.

Ask the user which direction to take.
