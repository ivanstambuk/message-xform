# Pending Task

**Focus**: E2E test expansion — Phase 9 (Hot-Reload) or Standalone Proxy E2E
**Status**: Karate migration complete. Ready for next feature work.
**Next Step**: Choose between Phase 9 hot-reload E2E, Phase 8b Web Session,
or creating `e2e-standalone/` for Feature 004.

## Context Notes
- Karate DSL is the standard E2E framework. Patterns documented in
  `docs/research/karate-e2e-patterns.md`.
- PingAccess strips custom backend response headers — always assert via
  body content or container log inspection (`karate.exec('docker exec ...')`).
- Tests 23-24 (Web Session/OIDC) skipped — requires PingFederate runtime.
- `callonce` caching is JVM-scoped — provisioning must be idempotent.
- The `e2e-<gateway>/` naming convention is established for future adapters.

## Open E2E Phases (from expansion plan)
- **Phase 8b**: Web Session OIDC (requires PingFederate or improved mock)
- **Phase 9**: Hot-Reload E2E (file-system mutation during live PA run)
- **Phase 10**: JMX Metrics E2E (JMX client to PA JVM)
- **Phase 11**: Multi-Rule Chain E2E (two PA rules in sequence)

## Standalone Proxy E2E (Feature 004)
- Create `e2e-standalone/` module following `e2e-pingaccess/` patterns.
- No Docker PA needed — standalone proxy runs as a JVM process.
- Bootstrap script starts the proxy, Karate tests hit endpoints.
- See `docs/research/karate-e2e-patterns.md` §5b for module naming.

## SDD Gaps
- None. All checks passed in retro.
