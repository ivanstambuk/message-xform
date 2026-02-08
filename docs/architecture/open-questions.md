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
| Q-028 | Ivan | **`transform()` API profile matching gap**: `API-001-01 TransformEngine.transform(Message, Direction)` must perform profile matching (FR-001-05), but `Message` now includes `getRequestPath()` and `getRequestMethod()`. Should the engine extract match criteria from `Message`, or should the API signature change? | A) **Engine extracts from Message** — `transform()` reads `Message.getRequestPath()`, `.getRequestMethod()`, `.getContentType()` to resolve the matching profile. No API signature change. Simple, but couples profile matching to Message. B) **Add explicit match input** — `transform(Message, Direction, MatchCriteria)` where `MatchCriteria` bundles path/method/content-type. Decouples matching from Message, but adds a new type. C) **Two-phase API** — `matchProfile(path, method, contentType, direction) → TransformSpec[]`, then `transformWithSpec(Message, TransformSpec)`. Most flexible, but more complex adapter code. | Open | 2026-02-08 | Severity: MEDIUM — `Message` already has path/method after R-3 fix, so Option A may be the obvious choice. But the two-phase pattern (C) enables adapters to cache match results for repeated routes. |

