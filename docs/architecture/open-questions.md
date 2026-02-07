# Open Questions

Use this file **only** to capture currently open medium- or high-impact questions.
It is a **temporary scratchpad for open questions**, not a permanent record of decisions.

Hard rules:

- This table may only contain rows whose status is `Open`.
- When a question is resolved, its outcome **must be captured** in the relevant
  spec's normative artefacts (`spec.md`, `plan.md`) or in an ADR under `docs/decisions/`,
  and the corresponding row **must be deleted** from this file.
- Resolved questions **must not be archived** here; any details must be removed when
  the row is removed.
- Question IDs (e.g., `Q-001`) are local to this project and chat transcripts;
  they must never be referenced from external docs.
- This file is **never** a source of truth; once resolved, no record remains here.
- When presenting a question to a human, use the **Decision Card** format from
  `docs/architecture/spec-guidelines/open-questions-format.md`.

<!-- Add new rows below with Status set to Open only. Remove the row once resolved and documented elsewhere. -->

| ID | Owner | Question | Options (A preferred) | Status | Asked | Notes |
|----|-------|----------|------------------------|--------|-------|-------|
| Q-005 | Ivan | What are the spec version compatibility rules? | A) Informational only (recommended) B) Profiles pin to spec versions C) Semver with breaking change policy | Open | 2026-02-07 | |
| Q-006 | Ivan | What happens when multiple profiles match the same request? | A) First-match-wins by profile priority (recommended) B) Most-specific-wins (longest path) C) Error — ambiguous match rejected at load time | Open | 2026-02-07 | Critical for production. |
| Q-007 | Ivan | Should we define structured observability (metrics, log format, trace correlation)? | A) Minimal — structured error logging + request-id passthrough (recommended) B) Full — metrics, histograms, OpenTelemetry C) Defer to implementation | Open | 2026-02-07 | |
| Q-008 | Ivan | Should a single spec support chained/pipeline transforms? | A) No, one expression per direction — JSLT is expressive enough (recommended) B) Yes, pipeline of expressions | Open | 2026-02-07 | JourneyForge uses pipelines but their use case is different. |
| Q-009 | Ivan | Should we create ADRs for key decisions already made (JSLT, pluggable SPI, JsonNode)? | A) Yes, create ADR-001/002/003 now (recommended) B) No, spec appendix is sufficient | Open | 2026-02-07 | |
