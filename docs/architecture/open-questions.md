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

| Q-023 | Ivan | **Multi-value header access**: FR-001-10 says `$headers."Set-Cookie"` returns the first value and "accessing all values requires a future extension." Is this documented well enough, or should we define the exact future API now? Real-world scenarios: `Set-Cookie` always has multiple values, `X-Forwarded-For` chains multiple IPs. | A) Define the multi-value API now as `$headers_all` (a JsonNode where values are arrays of strings) but mark it as **deferred implementation** — document the shape so adapters can prepare for it. B) Ship single-value only and defer entirely. The workaround is header-to-body injection via the adapter. C) Change `$headers` to always use arrays (breaking); this is the most honest representation. | Open | 2026-02-07 | Severity: LOW-MEDIUM — `Set-Cookie` and `X-Forwarded-For` are the main multi-value headers. Most transform scenarios only need request headers which are typically single-value. |




