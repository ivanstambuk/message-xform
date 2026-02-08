# Pending Task

**Focus**: Feature 004 — Standalone HTTP Proxy Mode, Phase 2 I3 (Configuration)
**Status**: T-004-04..06 done, T-004-07 and T-004-08 remain in I3
**Next Step**: Implement T-004-07 — Environment variable overlay (FR-004-11)

## Context Notes
- ProxyConfig uses a builder pattern with scheme-derived port resolution
- ConfigLoader maps YAML → ProxyConfig via Jackson YAML (manual JsonNode traversal)
- ConfigLoadException for structured startup errors
- Javalin 6.7.0 confirmed = Jetty 11 (all docs corrected this session)
- AGENTS.md pre-commit checklist now requires tasks.md + plan.md in every task commit

## Remaining in I3
1. T-004-07: EnvVarOverlayTest + env var overlay layer in ConfigLoader
   - BACKEND_HOST, PROXY_PORT, all 41 env var mappings
   - Empty/whitespace env vars treated as unset
2. T-004-08: ConfigValidationTest + validation logic
   - Missing backend.host → error
   - Invalid scheme/client-auth/logging-level → error
   - backend.port auto-derived from scheme

## After I3: Phase 3 (Core Proxy)
- T-004-09: UpstreamClient (JDK HttpClient)
- T-004-10: ProxyHandler (Javalin handler)
- T-004-11: GatewayAdapter bridge
