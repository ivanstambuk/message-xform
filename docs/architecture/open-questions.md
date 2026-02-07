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
| Q-018 | Ivan | Large/streaming body handling: should the spec mandate full-body buffering by adapters? | A) Yes — spec mandates adapters MUST buffer full body before passing to engine; add max-input-bytes config B) No explicit mandate — leave to adapter implementation C) Support streaming via chunked JsonNode processing (future) | Open | 2026-02-07 | severity: medium — FR-001-04 |
| Q-019 | Ivan | `sensitive` field marking (NFR-001-06): what is the YAML syntax for declaring fields as sensitive? | A) Add `sensitive: [fieldPath1, fieldPath2]` block to spec YAML B) Use JSON Schema `x-sensitive: true` annotation in field definition C) Mark at profile level, not spec level | Open | 2026-02-07 | severity: medium — NFR-001-06, FR-001-01 |
