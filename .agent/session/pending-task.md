# Pending Task

**Focus**: Feature 004 — Phase 7 (I10): Startup, Shutdown, Logging
**Status**: Phase 6 (TLS) complete — 45/60 tasks done (75%)
**Next Step**: T-004-46 — StandaloneMain startup sequence

## Context Notes
- Phase 6 (TLS) completed in full: T-004-40 through T-004-45 (6 tasks, 23 tests)
- New production classes: `TlsConfigurator`, `TlsConfigValidator`, modified `UpstreamClient`
- Test certificates in `adapter-standalone/src/test/resources/tls/` (regenerable via `generate-certs.sh`)
- TLS validation (`TlsConfigValidator`) is not yet wired into the startup sequence — T-004-46 should integrate it into `StandaloneMain`
- Hostname verification disable uses `jdk.internal.httpclient.disableHostnameVerification` system property (internal API)

## Phase 7 Tasks (I10)
- T-004-46: StandaloneMain startup sequence (FR-004-27, IMPL-004-05)
- T-004-47: Startup failure handling (S-004-45)
- T-004-48: Graceful shutdown (FR-004-28, S-004-46/47)
- T-004-49: Logback structured JSON logging (NFR-004-07)
- T-004-50: Request logging middleware (NFR-004-07)

## SDD Gaps
- None — all checks passed, findings ledger resolved
