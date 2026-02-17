# Pending Task

**Focus**: Platform K8s Deployment — Phase 4 (Networking & Ingress)
**Status**: Phase 3 fully complete. All services running and verified on k3s.
**Next Step**: Configure k3s Traefik IngressRoute for external access to PA engine.

## Context Notes
- PA engine serves on port 3000 (HTTPS) inside the cluster
- Two PA applications: `/am` (proxy) and `/api` (clean URLs)
- MessageTransform rule is active on both applications
- PA admin password is `2FederateM0re` (NOT `2Access`)
- Plugin JAR must mount at `/opt/out/instance/deploy` (NOT staging)
- K8s namespace: `message-xform`
- Helm chart: `pingidentity/ping-devops` v0.11.17 (revision 4)

## SDD Gaps
- None — this is infrastructure/operations work, not feature development
