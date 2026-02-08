# Current Session — 2026-02-08

## Active Work

**Feature 004 — Standalone HTTP Proxy Mode** (Research Phase)
Status: 🔬 Research in progress — HTTP server selection, architecture design.

## Session Progress

1. ✅ `/init` — loaded project context, assessed state, proposed next steps
2. ✅ Feature 004 research started — HTTP server evaluation
3. ✅ Research document written: `docs/research/standalone-proxy-http-server.md`
   - Evaluated 5 candidates: Javalin, Undertow, Vert.x, Netty, JDK HttpServer
   - Recommendation: Javalin 6 (highest API/effort ratio) or JDK HttpServer (zero deps)
   - Architecture sketch, proxy config model, request flow designed
   - HTTP client decision: JDK `HttpClient` (zero deps)
   - Hot reload: WatchService + admin endpoint
4. ✅ Open questions logged: Q-029 through Q-032
   - Q-029: HTTP server choice (HIGH impact)
   - Q-030: Upstream routing model (MEDIUM)
   - Q-031: Body size limits (MEDIUM)
   - Q-032: TLS termination (LOW)
5. 🔲 Resolve open questions with Ivan
6. 🔲 Write Feature 004 spec

## Key Decisions (Pending)

- Q-029: HTTP server — Javalin 6 recommended, awaiting owner decision
- Q-030: Upstream routing — single upstream per instance recommended
- Q-031: Body limit — 10 MB default recommended
- Q-032: TLS — plaintext only for v1 recommended

## Carry-Forward Context

- Feature 001 is ✅ Complete (53 tasks, 87 scenarios, all exit criteria met)
- Feature 004 depends on Feature 001 (core engine)
- ADR-0025 defines adapter lifecycle as adapter-scoped (no lifecycle methods in SPI)
- GatewayAdapter SPI has 3 methods: wrapRequest, wrapResponse, applyChanges
