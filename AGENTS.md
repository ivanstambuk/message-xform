# AGENTS.md

> Project-level rules and conventions for AI agents working on message-xform.

## Project Overview

**message-xform** is a standalone payload & header transformation engine for API Gateways.
See `PLAN.md` for full architecture and feature breakdown.

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
  - `docs/decisions/` — Architecture Decision Records (ADRs).
  - `docs/research/` — Research notes (API analysis, gateway evaluations, etc.).
  - `docs/templates/` — Spec and document templates.
  - `.agent/workflows/` — Session lifecycle workflows.
  - `.agent/session/` — Ephemeral session state (pending tasks).

## Rules

1. **Read Before Acting**: At session start, run the `/init` workflow. Read `AGENTS.md` and `PLAN.md` before making changes.
2. **Persistence**: Update `AGENTS.md` immediately when conventions or preferences change.
3. **Implement Now, Don't Defer**: When asked for a feature, implement it. Don't suggest "add to backlog."
4. **Self-Correcting Protocol**: When making a preventable mistake, proactively ask the user to update `AGENTS.md` or a workflow to prevent recurrence.
5. **Commit Hygiene**: Bundle related changes in atomic commits with clear messages.
6. **Autonomous Operations**: If you can run a command to verify or fix something, do it — don't instruct the user to do it.
7. **Research Must Be Persisted**: When tasked with researching something — APIs, tools, design options, vendor comparisons — **always write findings to a file** in `docs/research/` (or `docs/decisions/` for ADRs). Never leave research as chat-only output. Research documents must be:
   - Self-contained and comprehensible without the chat context
   - Include source references (documentation URLs, file paths, line ranges)
   - Summarize implications for message-xform
   - Be committed to git so they survive across sessions
   - Feed into the SDD specification pipeline when development begins
8. **One Question at a Time**: When resolving open questions (spec refinement, design decisions, ADRs), present them **one by one**. Ask a single question, wait for the user's answer, record the decision, then move to the next question. Never dump multiple questions in bulk — each decision deserves focused attention.

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
