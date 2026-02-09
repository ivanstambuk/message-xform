# Pending Task

**Focus**: Feature 004 — Standalone HTTP Proxy Mode, Phase 6: TLS
**Status**: I8 (FileWatcher + admin reload) complete. Moving to I9 (TLS).
**Next Step**: T-004-40 — Generate self-signed test certificates (server, client, truststore)

## Context Notes
- All hot reload infrastructure is implemented and tested (15 tests).
- `TransformRegistry.specCount()` returns 2× unique specs — use
  `specPaths.size()` for user-facing counts (documented in AGENTS.md pitfalls).
- `AdminReloadHandler` takes 3 args: `(engine, specsDir, profilePath)`. No
  `SpecParser` needed — engine creates its own internally.
- `FileWatcher` supports multi-directory watching (specs + profiles).
- `ProblemDetail.internalError()` was added for reload failures (500 + RFC 9457).

## Phase 6 Plan (I9 — TLS)
Tasks T-004-40 through T-004-45:
1. T-004-40: Generate self-signed certs (keytool → server.p12, client.p12, truststore.p12)
2. T-004-41: Inbound TLS (HTTPS server via Jetty SslContextFactory)
3. T-004-42: Inbound mTLS (client cert verification)
4. T-004-43: Outbound TLS (HTTPS to backend, truststore validation)
5. T-004-44: Outbound mTLS (client cert to backend)
6. T-004-45: TLS config validation at startup

## SDD Gaps
None — all gaps from retro were fixed and committed.
