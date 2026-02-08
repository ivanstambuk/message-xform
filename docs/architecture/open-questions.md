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
| Q-035 | Ivan | **Admin endpoint security:** `POST /admin/reload` is on the same port as proxy traffic with no access control. In K8s sidecar deployments, it's network-accessible. | A: Document as non-goal v1, rely on K8s NetworkPolicy; B: Separate `admin.port` config (default disabled); C: Basic auth on admin endpoints | Open | 2026-02-08 | severity: high; Feature 004 |
| Q-036 | Ivan | **Non-JSON body handling:** When a profile matches a request but the body is not JSON-parseable (text/xml, binary, etc.), what should happen? | A: Return `400 Bad Request`; B: Passthrough body, still apply header/status/url transforms; C: Full passthrough (no transforms at all) | Open | 2026-02-08 | severity: medium; Feature 004 |


