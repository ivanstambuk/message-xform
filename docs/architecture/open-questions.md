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
| Q-040 | Ivan | How should `max-body-bytes` be enforced for chunked-encoded requests? Javalin/Jetty assembles the full body before the proxy can inspect it. A 50 MB chunked request would be fully buffered in memory before rejection. | A: Configure Jetty's `maxRequestSize` / `maxFormContentSize` at server init to reject oversized bodies during upload (memory-safe but Jetty-specific). B: Check body size after Javalin assembles the body (`ctx.body().length()`) — simpler but permits transient memory spikes. C: Document as known limitation in v1 — Jetty buffers first, proxy checks after. | Open | 2026-02-08 | Jetty 12 API surface for streaming size limits needs investigation. |
| Q-041 | Ivan | Should `wrapRequest` parse cookies from the `Cookie` header and bind `$cookies` context variable (Feature 001 DO-001-07)? | A: Defer to v2 — `$cookies` is `null`. Document in spec that cookie binding is not supported in v1. B: Implement in v1 — Javalin has `ctx.cookie(name)` API. Populate `TransformContext.cookies` from parsed `Cookie` header. | Open | 2026-02-08 | Spec currently defers this (note added in FR-004-35 section). |



