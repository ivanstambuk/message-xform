# AGENTS.md

> Project-level rules and conventions for AI agents working on message-xform.

> **Speech-to-text**: The user communicates via speech-to-text software. Expect
> occasional transcription errors (e.g., "eight" instead of "A", "take a leak of
> face" instead of "take a leap of faith"). When the intended meaning is obvious
> from context, silently correct and proceed — do not ask for clarification.
> Only ask when the meaning is genuinely ambiguous.

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
| 001 | Message Transformation Engine (core) | ✅ Complete |
| 002 | PingAccess Adapter | 🔲 Not Started |
| 003 | PingGateway Adapter | 🔲 Not Started |
| 004 | Standalone HTTP Proxy Mode | ✅ Complete |
| 005 | WSO2 API Manager Adapter | 🔲 Not Started |
| 006 | Apache APISIX Adapter | 🔲 Not Started |
| 007 | Kong Gateway Adapter | 🔲 Not Started |
| 008 | NGINX Adapter | 🔲 Not Started |
| 009 | Toolchain & Quality Platform | 📋 Spec Ready |

**Priority:** Tier 1 (001+004+009) → Tier 2 (002+003) → Tier 3 (005+006) → Tier 4 (007+008)

## Feature Lifecycle

Every feature progresses through these steps. Do not skip steps.

1. **Research** — Analyze the gateway's extension model, API, SDK. Write findings to `docs/research/<name>.md`.
2. **Scenarios** — Define concrete test scenarios with input/output JSON pairs in `features/<NNN>/scenarios.md`.
3. **Spec** — Write the feature specification (SDD) in `features/<NNN>/spec.md`.
4. **Plan** — Break the spec into implementation phases in `features/<NNN>/plan.md`.
5. **Tasks** — Granular task breakdown in `features/<NNN>/tasks.md`. Order tests before code in every task.
6. **Test** — Write failing tests for the first task increment. Confirm they fail. This validates that the test infrastructure is wired and the assertions are meaningful.
7. **Implement** — Drive failing tests to green. Write production code, then refactor. Repeat per task increment.
8. **Verify** — Run the full quality gate and integration tests against scenarios. All scenarios must pass.

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
  - `docs/operations/` — Gate checklists, operational runbooks, and cross-cutting
    NFR tasks (performance benchmarks, CI gates). **Rule**: tasks that are not
    specific to a single feature belong here, not in feature task lists.
  - `docs/research/` — Research notes (API analysis, gateway evaluations, etc.).
  - `docs/templates/` — Spec, plan, tasks, and ADR templates.
  - `docs/_current-session.md` — Session state for agent handoffs.
  - `.agent/workflows/` — Session lifecycle workflows.
  - `.agent/session/` — Ephemeral session state (pending tasks).
  - `llms.txt` — High-signal specs manifest for LLM context.

## Build & Test Commands

```bash
# Full formatting + verify (canonical quality gate):
./gradlew --no-daemon spotlessApply check

# Dry-run format check (CI-safe, no modifications):
./gradlew --no-daemon spotlessCheck check

# Focused core-only tests:
./gradlew --no-daemon :core:test

# Standalone proxy tests:
./gradlew --no-daemon :adapter-standalone:test

# Single test class:
./gradlew --no-daemon :core:test --tests "io.messagexform.core.engine.TransformEngineTest"

# Shadow JAR (fat JAR for standalone proxy):
./gradlew --no-daemon :adapter-standalone:shadowJar

# Docker image build (from adapter-standalone/):
docker build -t message-xform-proxy adapter-standalone/
```

See `docs/operations/quality-gate.md` for full pipeline documentation.

**Formatter policy:**
- **Palantir Java Format** 2.78.0 via Spotless Gradle plugin 8.1.0.
- Scope: `src/**/*.java` in every subproject.
- Remove unused imports, trim trailing whitespace, end with newline.
- Run `./gradlew spotlessApply` to auto-format before committing.

**Prerequisites:**
- Ensure `JAVA_HOME` points to a Java 21 JDK before invoking Gradle or Git hooks.
  Verify: `java -version` should show `21.x`. SDKMAN: `sdk use java 21.0.4-tem`.
- **Git hooks** ⚡ **(MANDATORY)**: Run `git config core.hooksPath githooks` once
  per clone. The `pre-commit` hook runs the quality gate; the `commit-msg` hook
  enforces conventional commit format. Verify: `git config core.hooksPath` should
  return `githooks`.

## Rules

1. **Read Before Acting**: At session start, run the `/init` workflow. Read `AGENTS.md` and `PLAN.md` before making changes.
2. **Persistence**: Update `AGENTS.md` immediately when conventions or preferences change.
3. **Implement Now, Don't Defer**: When asked for a feature, implement it. Don't suggest "add to backlog."
4. **Self-Correcting Protocol**: When making a preventable mistake, proactively ask the user to update `AGENTS.md` or a workflow to prevent recurrence.
5. **Agent Autonomy & Escalation Threshold** ⚡ **(NON-NEGOTIABLE)**: Act autonomously on anything that is **obvious, low-impact, and non-controversial**. Only escalate to the user when a decision is **medium/high-impact AND has multiple viable options**.
   - **Just do it (no user input needed):** committing completed work, updating task trackers, fixing lint/formatting, running tests, updating doc status, creating obvious follow-up files, cleaning up temp files. These are mechanical consequences of the work — not decisions.
   - **Escalate to the user:** architectural choices with trade-offs, ambiguous requirements, breaking API changes, dependency additions, scope changes, anything where reasonable people could disagree.
   - **The test:** "Would a competent teammate do this without asking?" If yes → do it. If no → ask.
   - **Anti-patterns to eliminate:** "Should I commit?", "Would you like me to update tasks.md?", "Shall I proceed to the next task?", "Should I run tests?" — **NEVER ask these**.
6. **Incremental Auto-Commit Protocol** ⚡ **(NON-NEGOTIABLE)**: Commit after **every atomic unit of work** — automatically, silently, without asking. **NEVER ask "should I commit?" or "would you like me to commit?"** — just do it. Committing is like breathing; it happens on its own.
   - **Implementation work**: One task (`T-xxx-yy`) = one commit. Tests pass → run the **Pre-Commit Checklist** below → `git add -A && git commit` → move on. No announcement, no permission request, no summary-then-wait.
   - **Non-task work**: One self-contained change = one commit. Examples: one ADR + its spec/scenario updates, one open question resolution, one terminology fix, one `AGENTS.md` update, one backlog item.
   - Use descriptive commit messages that explain *what* and *why* (not just *what*).
   - **Do NOT batch unrelated changes** in one commit.
   - **Narrow exception**: Multiple tasks may share a single commit **only** when they are trivially coupled — i.e., they modify the same method/class and none can compile or pass tests independently. Document all task IDs in the commit message.
   - **Pre-Commit Checklist for Task Commits** ⚡ **(MANDATORY — every task commit)**:
     Before running `git add -A && git commit` for any task (`T-xxx-yy`):
     1. ☐ `tasks.md`: Mark the task `[x]`, add `_Verification log:_` with test count, result, and commit hash.
     2. ☐ `tasks.md`: Update `_Status:_` and `_Last updated:_` header if not already current.
     3. ☐ `plan.md`: Update the parent increment status (e.g., ✅ DONE or 🔧 IN PROGRESS) and add a `_Result:_` line if the increment is newly completed.
     4. ☐ `plan.md`: Update `_Status:_` and `_Last updated:_` header if not already current.
     All four items go into the **same** commit as the implementation code.
     **Anti-pattern (FORBIDDEN)**: committing Java code/tests first, then `tasks.md`
     as a separate `docs:` commit. This pollutes git history with 2N commits instead
     of N. Complete ALL checklist items, THEN commit once.
   - **User override**: If the user requests "no commits" / "commit in bulk later", follow their instruction.
   - **Format**: `<type>(<scope>): T-xxx-yy — <short description>` — types: `fix`, `feat`, `docs`, `refactor`, `retro`, `session`, `backlog`, `terminology`.
7. **Autonomous Operations**: If you can run a command to verify or fix something, do it — don't instruct the user to do it.
8. **Research Must Be Persisted**: When tasked with researching something — APIs, tools, design options, vendor comparisons — **always write findings to a file** in `docs/research/` (or `docs/decisions/` for ADRs). Never leave research as chat-only output. Research documents must be:
   - Self-contained and comprehensible without the chat context
   - Include source references (documentation URLs, file paths, line ranges)
   - Summarize implications for message-xform
   - Be committed to git so they survive across sessions
   - Feed into the SDD specification pipeline when development begins
9. **Open Question Resolution Protocol**: When resolving open questions (spec refinement, design decisions), follow this protocol:
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
11. **No Implicit Decisions**: Every decision discussed with the user MUST be explicitly captured in a permanent artefact — never left as an implicit understanding from chat. Chat is ephemeral; decisions must survive across sessions.
    - If a topic was discussed and a conclusion was reached, it MUST be recorded in at least one of:
      - An **ADR** under `docs/decisions/` (for architectural/design decisions).
      - The **spec** (`spec.md`) as normative text (for requirements-level decisions).
      - **`terminology.md`** (for vocabulary agreements).
      - **`scenarios.md`** (for behavioural contracts).
    - **Test:** If a future agent or the user could reasonably re-raise the same question, the current capture is insufficient. The artefact must contain enough context that a reader can find it, understand the decision, and know it's settled.
    - **When in doubt, create an ADR.** ADRs are cheap; rediscussion is expensive. Even a short ADR that says "we considered X and decided not to do it" prevents future rework.
    - This rule applies even when the decision is "do nothing" or "this is out of scope" — the reasoning must still be recorded.
12. **Test-First Cadence (TDD)**: Write tests before production code. This is non-negotiable.
    - **Red → Green → Refactor**: For every task increment, write or extend failing tests first, confirm they fail (red), write the minimum production code to make them pass (green), then refactor.
    - **Branch coverage upfront**: When outlining a task, list the expected success, validation, and failure branches. Add thin failing tests for each branch **before** writing implementation code so coverage grows organically.
    - **Quality gate after every increment**: Run the build quality gate (e.g., `./gradlew spotlessApply check`) after every self-contained increment. A red build must be fixed or the failing test explicitly quarantined with a TODO, a documented reason, and a follow-up captured in the plan.
    - **Tests validate scenarios**: Executable tests MUST map back to the scenarios defined in `scenarios.md`. When a scenario has no corresponding test, that's a gap — fill it before moving on.
    - **No "implement then test" sequences**: Task breakdowns in `tasks.md` MUST order test creation before production code for each increment. A task that reads "implement X, then add tests" is incorrectly structured — restructure it as "write failing tests for X, then implement X."
13. **No Unapproved Deletions**: Never delete files or directories — especially via recursive commands or when cleaning untracked items — unless the user has explicitly approved the exact paths in the current session. Features may be developed in parallel across sessions, so untracked files can appear without warning; surface them for review instead of removing them.
14. **No Destructive Commands**: Avoid destructive commands (e.g., `rm -rf`, `git reset --hard`, force-pushes) unless the user explicitly requests them. Stay within the repository sandbox. Prefer reversible operations.
15. **Dependency Approval Required**: Never add or upgrade libraries, Gradle plugins, BOMs, or other build dependencies without explicit user approval. When approved, document the rationale in the relevant feature plan or ADR. Automated dependency PRs (e.g., Dependabot) still require owner approval before merging.
16. **No Reflection**: Do not introduce Java reflection in production or test sources. When existing code requires access to collaborators or internals, prefer explicit seams (constructor parameters, package-private collaborators, or dedicated test fixtures) instead of reflection.
17. **Learnings Must Be Persisted**: Session learnings (pitfalls, syntax gotchas, tooling workarounds, API surprises) MUST be written to a file — never left as chat-only retro bullets. Target locations:
    - **Library/API quirks** → the relevant `docs/research/` document.
    - **Tooling/process pitfalls** → `AGENTS.md` (this file), under "Known Pitfalls" below.
    - **Conventions** → `AGENTS.md` operational rules.
18. **No Cascading Renumber**: When inserting new increments, tasks, scenarios, or requirements between existing ones, use **letter suffixes** (e.g., I11a, T-001-36a, S-001-74a, FR-001-12a) instead of renumbering all downstream IDs. This avoids churn across cross-referencing documents (spec, plan, tasks, scenarios, knowledge-map). A dedicated cleanup pass may renumber IDs at the end of a feature when the structure is stable — never during active refinement.
19. **Spec Amendment Cascade** ⚡ **(NON-NEGOTIABLE)**: When a feature spec is modified — new FRs added, interfaces changed, scenarios extended, or contracts altered — the agent MUST immediately cascade status and implementation changes through all dependent documents. Do **not** leave a spec amended while surrounding documents still claim "Complete".
    - **Cascade checklist** (complete ALL steps in the same commit or increment):
      1. ☐ **`spec.md`**: Set Status to `Amended — <reason>, implementation pending`. It is NOT `Ready` when new requirements are pending implementation.
      2. ☐ **`plan.md`**: Add a new Phase/Increment for the new work. Update Status to reflect pending work. Update Exit Criteria (add unchecked items for new work).
      3. ☐ **`tasks.md`**: Add **new tasks** (never modify completed tasks). New tasks implement the new behaviour. Update Status and Completion Criteria.
      4. ☐ **`scenarios.md`**: Add scenarios for new behaviour (mandatory per Rule 9). Update coverage matrix.
      5. ☐ **`roadmap.md`**: If feature was `✅ Complete`, change to `🔨 In Progress`. Feature is not complete until all new tasks are done.
      6. ☐ **`AGENTS.md`**: Sync the roadmap mirror table to match `roadmap.md`.
      7. ☐ **`knowledge-map.md`** / **`llms.txt`**: Update if new ADRs or cross-cutting references were added.
    - **Key principle**: New tasks are always **additive**. Never modify completed tasks to absorb new work — add new task IDs that implement the delta. This preserves audit trail and keeps verification logs intact.
    - **Immediate actionability**: After the cascade, the feature MUST be in a state where the next agent session can pick up the new tasks and start implementing immediately — no ambiguity about what "In Progress" means.

### Known Pitfalls

- **File edit tool + Spotless concurrency** (2026-02-08): When `./gradlew spotlessApply` runs concurrently with `replace_file_content` or `multi_replace_file_content`, Spotless may overwrite edits silently — the tool reports success but the file on disk is unchanged. **Workaround**: run `spotlessApply` first, *then* edit, *then* `check`. Or use `sed` / Python for edits that must survive.
- **Antigravity IDE — Java 21 red squiggles** (2026-02-08): The Antigravity IDE (VS Code over SSH) stores remote extensions in `~/.antigravity-server/extensions/`, *not* `~/.vscode-server/extensions/`. The default-bundled `redhat.java` v1.12.0 does **not** support `JavaSE-21` — upgrade to v1.53+ (download VSIX from Open VSX, install via `Extensions: Install from VSIX...`). After upgrading, **delete the old version** (`rm -rf ~/.antigravity-server/extensions/redhat.java-1.12.0*`). Also requires `.vscode/settings.json` with `java.configuration.runtimes` pointing at the SDKMAN JDK path. **If adding new Gradle submodules:** The JDT LS caches its Gradle import and won't auto-detect new `include()` entries in `settings.gradle.kts`. Fix: delete `~/.antigravity-server/data/User/workspaceStorage/*/redhat.java/jdt_ws` and reload the window (`Ctrl+Shift+P` → "Developer: Reload Window").
- **networknt json-schema-validator** (2026-02-08): `JsonSchemaFactory.getSchema(jsonNode)` validates structural issues (malformed schema syntax) at creation time, but does **not** catch semantic issues like `type: not-a-type`. Our `SpecParser` explicitly checks the `type` keyword value against the JSON Schema 2020-12 type allowlist. Future: if deeper meta-schema validation is needed, validate against the meta-schema itself (`https://json-schema.org/draft/2020-12/schema`).
- **`HttpServer.stop(0)` port release is unreliable** (2026-02-09): When allocating an ephemeral port for "backend unreachable" tests, using `HttpServer.create(...).stop(0)` may not release the port instantly — the OS can hold it in TIME_WAIT, causing a subsequent TCP connect to succeed when it shouldn't. **Fix**: use `java.net.ServerSocket(0)` + `.close()` instead. See `ReadinessEndpointTest` for the pattern.
- **Javalin 6 TLS — `addConnector`, not SSL plugin** (2026-02-09): Javalin 6 bundles an SSL plugin, but for full Jetty control (e.g., mTLS `needClientAuth`, custom truststores), use `config.jetty.addConnector((server, httpConfig) -> ...)` directly with `SslContextFactory.Server`, `SslConnectionFactory`, and a `ServerConnector`. The callback must return the connector. See `TlsConfigurator` for the pattern.
- **JDK `HttpClient` hostname verification** (2026-02-09): Disabling hostname verification on the JDK 21 `HttpClient` requires the system property `jdk.internal.httpclient.disableHostnameVerification=true` (not the older `javax.net.ssl` properties). This is an internal API and must be set *before* the `HttpClient` is built.
- **Jetty 11 uses `jakarta.servlet`** (2026-02-09): Jetty 11 (bundled with Javalin 6) uses `jakarta.servlet.http.HttpServletRequest/Response`, not `javax.servlet`. Test mock backends using `AbstractHandler` must import from `jakarta.servlet`.

## Pre-Implementation Checklist (Mandatory)

> Before writing ANY production code, verify:
> - [ ] Feature spec exists at `docs/architecture/features/<NNN>/spec.md`
> - [ ] Spec has been reviewed/acknowledged by owner
> - [ ] Feature plan exists at `docs/architecture/features/<NNN>/plan.md`
> - [ ] Feature tasks exist at `docs/architecture/features/<NNN>/tasks.md`
> - [ ] Tasks order tests before code for every increment
> - [ ] Scenarios with input/output contracts exist
> - [ ] Failing tests exist for the first task increment (red phase)
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

## Session Boundary Convention

The agent SHOULD proactively suggest a `/retro` (starting a
fresh session) when any of the following **hard signals** are observed:

| Signal | Why it matters |
|--------|---------------|
| **Context truncation** ("CHECKPOINT N" in the conversation) | Earlier decisions, file contents, and rationale are lost. The agent is operating on reconstructed context, increasing error rates. |
| **3+ avoidable errors in a single task** (wrong method names, re-reading already-viewed files, incorrect fixture IDs, import management issues) | Symptoms of context pressure — the agent's effective memory is degraded. |
| **Phase boundary reached** (all tasks in a phase completed) | Phases represent architectural shifts (e.g., "parsing specs" → "executing transforms"). A fresh session brings a clean mental model for the new domain. |

The agent SHOULD also **consider** suggesting a session boundary (soft signals):

- **4–6 tasks completed** in the current session — productive chunk, capture learnings.
- **Significant cross-cutting refactoring** completed (e.g., touching 8+ files for a rename/cleanup).
- **2+ hours of active work** — retro captures learnings while they're fresh.

**When NOT to switch:**

- Never mid-task — always finish the current task and commit.
- When the next task directly depends on context just established in this session.
- Small mechanical tasks that chain tightly (e.g., two sub-tasks that are naturally one unit).

**Format:** When suggesting a boundary, the agent says:
> 🔄 **Session boundary suggested** — [reason]. Shall I run `/retro`?

## Autonomous Task Sequencing

When a task plan exists (e.g., `tasks.md` with ordered tasks), the agent MUST
execute tasks **continuously** without stopping to ask "should I continue with
the next task?" between each one.

**The rule:** Commit each task → immediately start the next. Do **not** pause for
user input unless a **session boundary signal** (see above) is triggered.

**Rationale:** The task plan already encodes the sequencing and dependencies.
Stopping after each task to ask permission is waste — it breaks flow, adds
latency, and provides no value when the plan is clear.

**Specifically, do NOT:**
- Ask "shall I proceed to T-001-XX?" after completing T-001-(XX-1).
- Summarise what you just did and wait for acknowledgement before starting the
  next task.
- Present a menu of "options" when the task plan already defines the next step.

**DO:**
- Commit each completed task atomically (per Auto-Commit rule).
- Immediately begin the next task in sequence.
- Continue until a session boundary signal fires (phase boundary, context
  truncation, 3+ errors) — then suggest `/retro`.
- If a task fails or produces unexpected results, fix it and continue. Only
  stop if you're genuinely blocked and need human input.

**Exception:** If the next task involves a **design decision not yet captured in
an ADR** or a **new architectural direction**, pause and ask. The distinction
is: mechanical execution continues autonomously; novel design decisions require
human input.

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


## Known Pitfalls

### JSLT 0.1.14 Function Availability
The project uses JSLT `0.1.14` (see `gradle/libs.versions.toml`). Not all JSLT
built-in functions listed in the latest documentation are available in this version.
Notably:
- `uppercase()` is NOT available — calling it silently returns `null` (no compile
  error, no runtime error). This is a JSLT quirk: unknown function names at
  evaluation time produce `null` rather than throwing.
- `upper-case()` (hyphenated) throws `JsltException: No such function` at compile
  time, which is the correct behavior for a missing function.
- **Workaround:** Use string concatenation (`+`) or structural transforms in tests
  rather than relying on version-specific JSLT built-in string functions.

### JDK HttpClient Restricted Headers
The JDK `HttpClient` (`java.net.http`) blocks setting the `Host` header via
`HttpRequest.Builder.header("Host", ...)` — it throws `IllegalArgumentException:
restricted header name: "Host"`. In integration tests, rely on the auto-generated
`Host` header (typically `127.0.0.1:<port>`) instead of setting a custom one.

### Javalin 6 — Unknown Methods Return 404, Not 405
Javalin does not return `405 Method Not Allowed` for HTTP methods without a
registered handler (e.g. `PROPFIND`). Instead, it returns `404 Not Found`
because no route matches. To produce a proper `405` with an RFC 9457 body,
add a `before("/<path>", ...)` filter with a method whitelist check that
calls `ctx.skipRemainingHandlers()` after writing the 405 response.

### TransformRegistry.specCount() Returns 2× Unique Specs
`TransformRegistry.specCount()` returns the internal map size, which stores each
spec under **both** its `id` key and its `id@version` key. This means 1 unique
spec file = 2 map entries = `specCount() == 2`. For user-facing reporting (e.g.,
admin reload JSON response), use the number of spec files scanned
(`specPaths.size()`) instead of `engine.specCount()`.

---

*Created: 2026-02-07*
*Status: INITIAL — Expand as project conventions emerge.*
