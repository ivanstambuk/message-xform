# message-xform Project Constitution

## Metadata
- Constitution Version: 1.2.0
- Ratified On: 2026-02-07
- Last Amended On: 2026-02-15
- Maintainer: Ivan (project owner)

## Preamble

This constitution establishes the non-negotiable operating principles for the
message-xform project. Specifications lead every change, decisions are recorded as
ADRs, and the ecosystem remains reproducible across agent sessions.

All contributors (human and AI) must follow these rules before planning, coding,
or committing work. Read the terminology in `docs/architecture/terminology.md` and
the agent playbook in `AGENTS.md` before acting.

## Principles

### Principle 1 – Specifications Lead Execution

- Author or update a feature specification before producing plans, tasks, or code.
- The specification is the single source of truth; plans, tasks, and code must
  reference it explicitly.
- Store specifications under `docs/architecture/features/<NNN>/spec.md` with
  traceable identifiers (e.g., "Feature 001", "FR-001-05").
- Treat every repository change (code, documentation, schemas, configuration) as an
  outcome of the specification-driven pipeline; ad-hoc edits that bypass specs,
  plans, and tasks are not allowed.

### Principle 2 – Clarification Gate

- Resolve ambiguous scope before planning by capturing every high-impact question;
  escalate medium-impact uncertainties the same way, while tidying low-level
  ambiguities directly and noting fixes in the governing spec/plan.
- Record all high- and medium-impact open questions in
  `docs/architecture/open-questions.md`; do not plan or implement until the user
  answers them and the specification captures the resolution.
- When a question is resolved:
  1. Update the governing specification's normative sections (FRs, NFRs, behaviour)
     so the spec remains the single source of truth.
  2. For architecturally significant clarifications: create or update an ADR under
     `docs/decisions/` using `docs/templates/adr-template.md`.
  3. Remove the resolved row from `docs/architecture/open-questions.md` — the file
     must never contain resolved questions.
- When documenting options, always order by preference: Option A is the recommended
  path, Option B the next-best alternative, and so on.

### Principle 3 – Test-First Quality Discipline

- Write or update executable tests before implementing behaviour.
- During specification, enumerate success, validation, and failure branches and
  stage failing test cases for each path before implementation begins.
- Run the build quality gate after every self-contained increment; a red build
  must be fixed or the failing test explicitly quarantined with a documented follow-up.
- Maintain scenario coverage in `features/<NNN>/scenarios.md` with a coverage
  matrix mapping requirements to scenarios.

### Principle 4 – Documentation Sync & Traceability

- Mirror every approved change across roadmap, feature plans, tasks, terminology,
  and knowledge map as needed.
- Maintain per-feature `tasks.md` files that decompose work into logical increments
  planned to complete within ≤90 minutes, reference spec requirements, and sequence
  tests before code.
- Log decisions and rationale in ADRs; log working context in `_current-session.md`.

### Principle 5 – Controlled Dependencies & Security

- Add or upgrade dependencies only with explicit owner approval and record the
  rationale in the feature plan.
- Keep secrets synthetic and test-only; production data or sensitive keys must never
  enter the repository.
- Follow least-destructive command practices; seek approval for high-risk actions.

### Principle 6 – Implementation Drift Gate

- Before a feature can be marked complete, run an Implementation Drift Gate.
- Cross-check the approved specification, feature plan, tasks checklist, and
  code/tests to confirm every spec requirement has a corresponding implementation
  and no implementation ships without documented intent.
- Produce a brief drift report summarising matches, gaps, and speculative work.
- For every high- or medium-impact divergence, record an open question for user
  direction; remediate low-level drift (typos, formatting) directly.

### Principle 7 – No Research Spikes in Plans or Tasks

- Implementation plans and task lists must contain only **deterministic,
  executable** steps. Research questions ("figure out how X works", "determine
  the best approach") must be resolved _before_ they appear as assumptions in
  increments or tasks.
- If writing a plan increment requires unknown information, that signals a
  **spec gap** — resolve it via a time-boxed research spike, update the spec
  with the findings, then write the increment against the resolved spec.
- This rule prevents unbounded work hiding inside time-budgeted increments and
  ensures every increment's preconditions are fully known before execution.

### Principle 8 – Feature Ownership Boundaries

- Every domain type, module, and code artefact is **owned by exactly one
  feature**. Ownership is determined by the feature whose specification
  introduced the type (e.g., `TransformResult` is owned by Feature 001 because
  DO-001-05 defines it).
- When Feature B needs a change to a type or module owned by Feature A, it MUST
  NOT embed the change inside Feature B's plan, tasks, or code. Instead:
  1. **Update Feature A's specification** to add or extend the requirement.
  2. **Create a task in Feature A's task list** to implement the change.
  3. **Declare a prerequisite** in Feature B's plan: "requires T-001-XX
     (Feature 001) to be complete before I-B-YY can begin."
- This rule prevents cross-feature specification drift, ensures each feature's
  spec is the single source of truth for its domain, and guarantees that core
  API changes are reviewed and tested under the owning feature's quality gate.
- **Violation signal:** Any plan step that says "modify core type X" or
  "extend Feature A's class Y" inside a different feature's plan is a
  constitutional violation. Refactor the change into the owning feature.

### Principle 9 – No Backlogs in Feature Plans

- Feature specifications, plans, and task files MUST NOT contain "Follow-ups",
  "Backlog", or "Future Work" sections that propose new requirements, features,
  or enhancements outside the feature's approved scope.
- Speculative items disguised as follow-ups bypass the SDD pipeline
  (specification → plan → tasks) and the Clarification Gate (Principle 2).
  They accumulate unchecked, become stale, and create ambiguity about what is
  actually in scope.
- When implementation reveals a potential enhancement or new requirement:
  1. If it is a **new feature or cross-cutting concern**: record it in the
     master `PLAN.md` under the appropriate section (Next Up / Backlog).
  2. If it affects an **existing feature's scope**: update that feature's
     specification through the normal SDD pipeline (spec amendment → plan
     update → task creation).
  3. If it is a **design constraint** (e.g., "v1 does not support X"):
     document it as a Constraint in the feature's specification — not as a
     follow-up to "add later".
- **Violation signal:** Any section named "Follow-ups", "Backlog",
  "Future Work", "TODO", or similar in a feature plan or spec is a
  constitutional violation. Move the items to `PLAN.md` or delete them.

## ADR Lifecycle

| Status | Meaning |
|--------|---------|
| `Proposed` | Candidate design decision under review |
| `Accepted` | Binding decision for specs and implementation |
| `Deprecated` | No longer recommended; kept for history and migration guidance |
| `Superseded` | Replaced by a newer ADR (which must explain the change) |

Before implementation work begins for a feature:
- The governing feature spec SHOULD be `Status: Ready`.
- Any ADRs that the spec treats as normative prerequisites SHOULD be `Status: Accepted`.

## Governance

- **Amendments:** Propose constitution changes via commit referencing this document.
  Classify version bumps as MAJOR (principle removal or incompatible rewrite),
  MINOR (new principle or substantial expansion), or PATCH (clarification without
  semantic change).
- **Exception handling:** Temporary deviations require written approval in the
  relevant feature plan and must include a restoration plan.

## Enforcement

- The analysis gate checklist (`docs/operations/analysis-gate-checklist.md`) must be
  executed once spec, plan, and tasks exist to verify alignment before implementation.
- The Implementation Drift Gate report must demonstrate zero unresolved high- or
  medium-impact divergences before a feature is marked complete.
- Commits failing constitutional checks may not merge; re-run analysis and
  remediation before continuing.
