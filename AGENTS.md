# AGENTS.md

> Project-level rules and conventions for AI agents working on message-xform.

## Project Overview

**message-xform** is a standalone payload & header transformation engine for API Gateways.
See `PLAN.md` for full architecture and feature breakdown. Read the project
constitution in `docs/decisions/project-constitution.md` before acting.

## Key References

| Document | Purpose |
|----------|---------|
| `PLAN.md` | Vision, architecture, features |
| `docs/decisions/project-constitution.md` | Non-negotiable SDD principles & governance |
| `docs/architecture/terminology.md` | Canonical term definitions (golden source) |
| `docs/architecture/knowledge-map.md` | Module structure, concept map, ADR dependencies |
| `docs/architecture/roadmap.md` | Feature roadmap (canonical source) |
| `docs/architecture/open-questions.md` | Live open questions (scratchpad, not archive) |
| `docs/architecture/spec-guidelines/docs-style.md` | Docs formatting & cross-reference conventions |
| `docs/architecture/spec-guidelines/open-questions-format.md` | Decision Card format for open questions |
| `docs/operations/analysis-gate-checklist.md` | Pre-implementation & drift gate verification |
| `llms.txt` | High-signal specs manifest for LLM context windows |
| `docs/_current-session.md` | Live session snapshot for agent handoffs |

## Roadmap

> Canonical source: `docs/architecture/roadmap.md`. Keep this summary in sync.

| # | Feature | Status |
|---|---------|--------|
| 001 | Message Transformation Engine (core) | 📝 Spec Draft |
| 002 | PingAccess Adapter | 🔲 Not Started |
| 003 | PingGateway Adapter | 🔲 Not Started |
| 004 | Standalone HTTP Proxy Mode | 🔲 Not Started |
| 005 | WSO2 API Manager Adapter | 🔲 Not Started |
| 006 | Apache APISIX Adapter | 🔲 Not Started |
| 007 | Kong Gateway Adapter | 🔲 Not Started |
| 008 | NGINX Adapter | 🔲 Not Started |

**Priority:** Tier 1 (001+002) → Tier 2 (003+004) → Tier 3 (005+006) → Tier 4 (007+008)

## Feature Lifecycle

Every feature progresses through these steps. Do not skip steps.

1. **Research** — Analyze the gateway's extension model, API, SDK. Write findings to `docs/research/<name>.md`.
2. **Scenarios** — Define concrete test scenarios with input/output JSON pairs in `features/<NNN>/scenarios.md`.
3. **Spec** — Write the feature specification (SDD) in `features/<NNN>/spec.md`.
4. **Plan** — Break the spec into implementation phases in `features/<NNN>/plan.md`.
5. **Tasks** — Granular task breakdown in `features/<NNN>/tasks.md`.
6. **Implement** — Write code, tests, and documentation.
7. **Verify** — Run integration tests against scenarios. All scenarios must pass.

Update both `docs/architecture/roadmap.md` and the Roadmap table above when status changes.

## Conventions

- **Config format**: YAML — human-readable, Git-diffable.
- **Module structure**: Multi-module (core + adapter per gateway).
- **Documentation hierarchy**:
  - `PLAN.md` — Vision, architecture, features, and open questions.
  - `AGENTS.md` — Agent rules and project conventions (this file).
  - `docs/architecture/features/<NNN>/spec.md` — Feature specifications (SDD).
  - `docs/architecture/features/<NNN>/scenarios.md` — Test scenarios (input/output contracts).
  - `docs/architecture/features/<NNN>/plan.md` — Implementation plans.
  - `docs/architecture/features/<NNN>/tasks.md` — Task breakdowns.
  - `docs/architecture/open-questions.md` — Live open questions (scratchpad, not archive).
  - `docs/architecture/roadmap.md` — Feature roadmap (canonical source).
  - `docs/architecture/terminology.md` — Project terminology (golden source).
  - `docs/architecture/knowledge-map.md` — Module/concept dependency graph.
  - `docs/architecture/spec-guidelines/` — Docs style, open questions format.
  - `docs/decisions/` — Architecture Decision Records (ADRs).
  - `docs/decisions/project-constitution.md` — Project constitution.
  - `docs/operations/` — Gate checklists and operational runbooks.
  - `docs/research/` — Research notes (API analysis, gateway evaluations, etc.).
  - `docs/templates/` — Spec, plan, tasks, and ADR templates.
  - `docs/_current-session.md` — Session state for agent handoffs.
  - `.agent/workflows/` — Session lifecycle workflows.
  - `.agent/session/` — Ephemeral session state (pending tasks).
  - `llms.txt` — High-signal specs manifest for LLM context.

## Rules

1. **Read Before Acting**: At session start, run the `/init` workflow. Read `AGENTS.md` and `PLAN.md` before making changes.
2. **Persistence**: Update `AGENTS.md` immediately when conventions or preferences change.
3. **Implement Now, Don't Defer**: When asked for a feature, implement it. Don't suggest "add to backlog."
4. **Self-Correcting Protocol**: When making a preventable mistake, proactively ask the user to update `AGENTS.md` or a workflow to prevent recurrence.
5. **Incremental Auto-Commit Protocol**: Commit after **every logical increment** — do not accumulate a mega-commit.
   - A "logical increment" is a self-contained unit: one ADR + its spec/scenario updates, one open question resolution, one terminology fix, one backlog item.
   - Commit immediately after each increment, before starting the next.
   - Use descriptive commit messages that explain *what* and *why* (not just *what*).
   - **Do NOT batch unrelated changes** in one commit.
   - **User override**: If the user requests "no commits" / "commit in bulk later", follow their instruction.
   - **Format**: `<type>: <short description>` — types: `fix`, `feat`, `docs`, `refactor`, `retro`, `session`, `backlog`, `terminology`.
6. **Autonomous Operations**: If you can run a command to verify or fix something, do it — don't instruct the user to do it.
7. **Research Must Be Persisted**: When tasked with researching something — APIs, tools, design options, vendor comparisons — **always write findings to a file** in `docs/research/` (or `docs/decisions/` for ADRs). Never leave research as chat-only output. Research documents must be:
   - Self-contained and comprehensible without the chat context
   - Include source references (documentation URLs, file paths, line ranges)
   - Summarize implications for message-xform
   - Be committed to git so they survive across sessions
   - Feed into the SDD specification pipeline when development begins
8. **Open Question Resolution Protocol**: When resolving open questions (spec refinement, design decisions), follow this protocol:
   - Present questions **one by one** using the **Decision Card** format from `docs/architecture/spec-guidelines/open-questions-format.md`.
   - **Decision Card isolation**: Send *only* the Decision Card in the message — no extra background, explanations, or additional questions.
   - **Workspace-first grounding**: The Decision Card's "Current behaviour" and "Key references" MUST cite concrete file paths/sections from the repo, not chat memory.
   - **Always recommend**: Option A MUST be the recommended option. Include clear pros/cons for every option.
   - **Wait for answer**: Do not proceed until the user chooses an option.
   - **Resolution Checklist** — after the user decides, complete ALL steps before moving to the next question:
     1. ☐ Update `spec.md` with the decision (normative text, interfaces, examples).
     2. ☐ Create or update ADR under `docs/decisions/` (mandatory for high-severity; use judgement for medium).
     3. ☐ **Add or update scenario(s)** in `scenarios.md` — MANDATORY if the decision introduces new behaviour, a new edge case, or changes an existing contract. Update the coverage matrix and scenario index. If unsure, add a scenario — false positives are cheap, missing coverage is expensive. (Rule 9 applies.)
     4. ☐ Update `docs/architecture/knowledge-map.md` (dependency graph + traceability table) if new ADR created.
     5. ☐ Update `llms.txt` if new ADR created.
     6. ☐ Remove the resolved row from `docs/architecture/open-questions.md`.
     7. ☐ Commit all changes in a single atomic commit.
   - Then move to the next question.
   - **Follow-up analysis in ADRs**: When a Decision Card triggers follow-up discussion
     (e.g., capability comparisons, use-case explorations, trade-off deep dives), capture
     that analysis as dedicated sections in the ADR — not only in chat. ADRs are the
     permanent record; chat is ephemeral.
9. **Decisions Must Be Testable**: Every ADR or resolved open question that changes the spec MUST produce at least one validating scenario in `scenarios.md`. Scenarios reference ADRs (via tags like `adr-0002`) and spec requirements (via `requires: [FR-001-10]`). The coverage matrix at the bottom of `scenarios.md` MUST be updated. When a discussion reveals a new edge case, add a scenario immediately — don't defer.
10. **Spec Review Protocol**: When the agent reviews or critiques a spec, findings MUST be triaged and persisted — never left as chat-only output.
    - **🔴🟡 Decisions needed** (high/medium severity): Register as formal open questions in `docs/architecture/open-questions.md`. These require human input via Decision Cards before the spec is updated. Each question gets a severity tag in the Notes column (`severity: high` or `severity: medium`).
    - **🔵 Obvious fixes** (low/polish): Apply directly to the spec in a commit. These are corrections or consistency issues, not architectural decisions — they don't need Decision Cards.
    - **Commit**: Always commit the new open questions in a single atomic commit so they are tracked in git history.
    - **Workflow**: After creating the questions, present the first 🔴 (high severity) question as a Decision Card and work through them in priority order.

## Pre-Implementation Checklist (Mandatory)

> Before writing ANY production code, verify:
> - [ ] Feature spec exists at `docs/architecture/features/<NNN>/spec.md`
> - [ ] Spec has been reviewed/acknowledged by owner
> - [ ] Feature plan exists at `docs/architecture/features/<NNN>/plan.md`
> - [ ] Feature tasks exist at `docs/architecture/features/<NNN>/tasks.md`
> - [ ] Scenarios with input/output contracts exist
> - [ ] Analysis gate checklist (`docs/operations/analysis-gate-checklist.md`) passed
> - [ ] Current task is marked "in-progress" in tasks.md
>
> If ANY box is unchecked, STOP and complete that step first.

## Agent Persistence & Token Budget

- Assume a large token and context budget. Do not prematurely truncate work,
  over-summarise, or stop early due to token concerns. Prefer complete, fully
  verified solutions over minimal answers.
- Aggressively leverage existing specs, docs, and code instead of asking the user
  to restate information recoverable from the repository.
- Be **brave and persistent**: for any scoped task, keep going through exploration →
  design → implementation → verification until the task is clearly done.
- The owner explicitly encourages long-running, self-directed work to minimise
  babysitting. Run multiple commands, apply patches, and iterate deeply.
- Balance persistence with the SDD workflow and project guardrails. When you must
  stop early, state clearly what blocked you and what you would do next.

## Exhaustive Execution for Scoped Tasks

- When the owner describes a task with an explicit scope (e.g., "all scenarios",
  "every ADR", "execute entire Phase X"), treat that scope as **exhaustive by
  default**, not "best-effort".
- Do **not** silently narrow scope unless the owner explicitly approves.
- Before declaring a task "complete", you MUST:
  - Define concrete acceptance conditions (e.g., "no uncovered FRs in coverage matrix").
  - Use repo-wide search commands (`rg`, `grep`) to confirm zero remaining
    occurrences of the old pattern in the declared scope.
- If you cannot finish the full scope, you MUST say explicitly that the task is
  **partial**, list what remains, and stop without marking it complete.

## No Silent Scope Narrowing

- When the owner asks to "do X" or "update all Y", assume they mean **all
  artifacts covered by that scope** unless they explicitly say otherwise.
- If an exhaustive pass would be unusually large, pause and ask whether to:
  A) still do the exhaustive pass; or
  B) limit to a subset, and record the agreed scope.

## Browser Agent (browser_subagent) — MANDATORY Setup

The browser_subagent requires a Chromium instance with Chrome DevTools Protocol (CDP) on port **9222**.

**Critical:** On the Alfred server (SSH remote), the Antigravity server process does NOT inherit `DISPLAY` from `.bashrc`. Chrome must be **pre-launched manually** via `start-chrome.sh` before browser_subagent will work.

### Before Calling browser_subagent

**Step 1: Ensure Chromium is running with CDP (MANDATORY)**
```bash
curl -s --max-time 2 http://localhost:9222/json/version | head -1
```
If not running (or after server reboot):
```bash
~/dev/message-xform/scripts/start-chrome.sh
```

**Step 2: Clean up stale tabs (REQUIRED before each call)**
```bash
~/dev/message-xform/scripts/cleanup-chrome-tabs.sh
```

**Why:** Each `browser_subagent` call creates a new tab. After 6+ tabs with SSE connections, the browser's per-origin connection limit is exhausted, causing "connection reset" / "timed out" failures.

**Anti-pattern:** Call browser_subagent 5 times → 5 tabs accumulate → SSE exhaustion  
**Correct pattern:** Clean tabs → call browser_subagent → clean tabs → call again

### Antigravity Settings (Alfred Server)

| Setting | Value |
|---------|-------|
| Chrome Binary Path | `/home/ivan/dev/message-xform/scripts/chrome-wrapper.sh` |
| Browser User Profile Path | `/tmp/ag-cdp` |
| Browser CDP Port | `9222` |

### Scripts Reference

| Script | Purpose |
|--------|---------|
| `scripts/start-chrome.sh` | Launch Chromium with CDP on port 9222 via Xvfb (run this once per session) |
| `scripts/chrome-wrapper.sh` | Wrapper for Antigravity settings — injects `--no-sandbox`, `DISPLAY=:99` |
| `scripts/cleanup-chrome-tabs.sh` | Close accumulated tabs, keep one blank tab |

---

*Created: 2026-02-07*
*Status: INITIAL — Expand as project conventions emerge.*
