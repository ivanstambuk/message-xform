# Open Questions

Use this file **only** to capture currently open medium- or high-impact questions.
It is a **temporary scratchpad for open questions**, not a permanent record of decisions.

Hard rules:

- This table may only contain rows whose status is `Open`.
- When a question is resolved, its outcome **must be captured** in the relevant
  spec's normative artefacts (`spec.md`, `plan.md`) or in an ADR under `docs/decisions/`,
  and the corresponding row **must be deleted** from this file.
- Question IDs (e.g., `Q-001`) are local to this project and chat transcripts;
  they must never be referenced from external docs.
- This file is **never** a source of truth; once resolved, no record remains here.

<!-- Add new rows below with Status set to Open only. Remove the row once resolved and documented elsewhere. -->

| ID | Feature | Question | Options | Status | Asked |
|----|---------|----------|---------|--------|-------|
| Q-001 | 001 | Should transform specs support optional input/output JSON Schema for compile-time validation? | (A) Yes, optional — validate at load time if present. (B) No, defer to v2. (C) Yes, mandatory. | Open | 2026-02-07 |
| Q-002 | 001 | How should header transforms be expressed? | (A) Separate `headers` block with add/remove/rename. (B) Headers injected into JSLT as `_headers`. (C) Headers are adapter-level only. | Open | 2026-02-07 |
| Q-003 | 001 | How should status code transforms be defined? | (A) Separate `status` block. (B) JSLT sets `_status` magic field. (C) Adapter-level only. | Open | 2026-02-07 |
| Q-004 | 001 | Should we define a formal engine support matrix (what each engine can/cannot do)? | (A) Yes, in spec. (B) No, document per-engine in adapter specs. | Open | 2026-02-07 |
| Q-005 | 001 | What are the spec version compatibility rules? | (A) Informational only. (B) Profiles pin to spec versions. (C) Semver with breaking change policy. | Open | 2026-02-07 |
| Q-006 | 001 | What happens when multiple profiles match the same request? | (A) First-match-wins (profile load order). (B) Most-specific-wins (longest path match). (C) Error — ambiguous match rejected at load time. | Open | 2026-02-07 |
| Q-007 | 001 | Should we define structured observability (metrics, log format, trace correlation)? | (A) Yes, in spec. (B) Minimal — just structured error logging. (C) Defer to implementation. | Open | 2026-02-07 |
| Q-008 | 001 | Should a single spec support chained/pipeline transforms (multiple expressions)? | (A) Yes, pipeline of expressions. (B) No, one expression per direction — JSLT is expressive enough. | Open | 2026-02-07 |
| Q-009 | 001 | Should we create ADRs for key decisions already made (JSLT, pluggable SPI, JsonNode body type)? | (A) Yes, create ADR-001/002/003 now. (B) No, spec appendix is sufficient. | Open | 2026-02-07 |
