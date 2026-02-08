# Pending Task

**Focus**: Feature 004 — Implementation Planning
**Status**: Spec complete — ready for plan and task breakdown
**Next Step**: Create `docs/architecture/features/004/plan.md` (phased implementation plan)
  and `docs/architecture/features/004/tasks.md` (granular task list)

## Context Notes
- Feature 004 spec is fully written: 34 FRs, 7 NFRs, 53 scenarios, 38 config keys
- All research questions resolved (Q-029 through Q-032)
- ADR-0029 accepted (Javalin 6)
- Docker/K8s deployment is in scope
- No code written yet — this is still documentation phase
- Retro audit complete, all findings fixed

## Suggested Plan Phases
1. Gradle module setup + Javalin dependency
2. GatewayAdapter<Context> implementation (StandaloneAdapter)
3. ProxyHandler + UpstreamClient (core proxy loop)
4. YAML configuration loading + env var overrides
5. Health/readiness endpoints
6. Hot reload (FileWatcher + admin endpoint)
7. TLS (inbound + outbound)
8. Docker image (Dockerfile + shadow JAR)
9. Integration tests (WireMock backend)
10. K8s deployment examples

## SDD Gaps
- scenarios.md not yet created (deferred to plan phase — will be created alongside tasks)
