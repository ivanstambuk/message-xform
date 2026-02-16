# Pending Task

**Focus**: Platform deployment — Phase 8 (E2E smoke tests)
**Status**: Phase 7 complete, Phase 8 not started
**Next Step**: Design and implement Karate E2E smoke tests for the platform

## Context Notes
- The full 3-container platform (PD + AM + PA) is running with the message-xform
  plugin active and verified
- Docker Compose v2 is required (`docker compose`, not `docker-compose`)
- Transform specs, profile, and configure-pa-plugin.sh script all tested and working
- Current containers: platform-pingdirectory, platform-pingam, platform-pingaccess
  (all on `platform_platform` network)

## Files Created/Modified This Session
- `deployments/platform/specs/am-auth-response.yaml` — body transform spec
- `deployments/platform/specs/am-header-inject.yaml` — header injection spec
- `deployments/platform/profiles/platform-am.yaml` — profile routing
- `deployments/platform/scripts/configure-pa-plugin.sh` — plugin provisioning
- `deployments/platform/docker-compose.yml` — volume mounts + v2 syntax
- `docs/operations/platform-deployment-guide.md` — §9c plugin wiring docs
- `docs/operations/pingam-operations-guide.md` — transformed response surface
- `docs/operations/pingaccess-operations-guide.md` — cross-references

## SDD Gaps
- None identified. This session was deployment/infrastructure work, not feature code.
