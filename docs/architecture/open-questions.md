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
| Q-042 | Ivan | **Core API gap: how should adapters inject `$cookies` and `$queryParams` into `TransformContext`?** The current `TransformEngine.transform(Message, Direction)` builds `TransformContext` internally with `null` for both. The adapter has no mechanism to pass gateway-specific context (cookies, query params) to the engine. The spec says "no core changes" but this requires one. | **A.** Add `transform(Message, Direction, TransformContext)` overload — adapter builds the context, engine uses it. Clean separation, backward-compatible (old 2-arg method builds empty context internally). **B.** Add `cookies` and `queryParams` fields to the `Message` record — heavier change, couples transport concerns to the generic envelope. **C.** Add a `Map<String, Object> metadata` bag to `Message` — flexible but loosely typed. | Open | 2026-02-08 | Discovered during F004 spec review. FR-004-37 (cookies) and potential FR for queryParams both depend on this. Blocking for implementation. |
| Q-043 | Ivan | **Should `$queryParams` binding be in-scope for Feature 004 v1?** The proxy has direct access to query parameters via Javalin's `ctx.queryParamMap()`. Feature 001 `TransformContext` (DO-001-07) already defines a `queryParams` field, and `JsltExpressionEngine` binds it as `$queryParams`. Currently, the engine always passes `null`. The proxy is the natural place to populate this. | **A.** Yes — add FR-004-XX and scenarios for `$queryParams`. Trivial to implement alongside `$cookies` (both are `TransformContext` fields). **B.** No — defer to a future feature. `$queryParams` is less commonly needed for body transformations. Document in Non-Goals. | Open | 2026-02-08 | Related to Q-042 (same API change enables both). If Q-042 picks Option A, adding queryParams is one extra line in the adapter. |



