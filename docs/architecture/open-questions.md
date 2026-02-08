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
| Q-029 | Ivan | **HTTP server for standalone proxy:** Which embedded HTTP server should Feature 004 use? | **A) Javalin 6** (Jetty 12) — simplest API, virtual threads, proxy support. B) JDK HttpServer — zero deps, virtual threads, but manual routing. C) Undertow — very performant but lower-level API. | Open | 2026-02-08 | HIGH impact. Core dependency for Feature 004. Research: `docs/research/standalone-proxy-http-server.md` §2. |
| Q-030 | Ivan | **Upstream routing model:** Should the proxy support a single upstream URL per instance or per-profile upstream routing? | **A) Single upstream** per proxy instance (simplest, sidecar-friendly). B) Per-profile upstream (one proxy → multiple backends). C) Hybrid — single default + per-profile override. | Open | 2026-02-08 | MEDIUM impact. Affects config model and deployment patterns. Research: §7.1. |
| Q-031 | Ivan | **Proxy body size limit:** What default max body size should the proxy enforce before returning 413? | **A) 10 MB** (covers most JSON APIs, safe for JVM heap). B) 1 MB (conservative). C) Configurable only, no default. | Open | 2026-02-08 | MEDIUM impact. Per ADR-0018, body buffering is adapter-scoped. |
| Q-032 | Ivan | **TLS termination:** Should the standalone proxy support HTTPS natively, or operate as plaintext-only (behind LB/NGINX)? | **A) Plaintext only** for v1 (run behind LB). B) Optional TLS via YAML config. | Open | 2026-02-08 | LOW impact. Can be deferred; sidecar and dev use cases are plaintext. |

