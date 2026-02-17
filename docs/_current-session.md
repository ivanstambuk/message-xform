# Current Session — K8s Platform Deployment (Phase 3)

## Focus
Kubernetes deployment of PingAM + PingDirectory + PingAccess on local k3s cluster.
Completing Phase 3 steps from `deployments/platform/PLAN.md`.

## Status
✅ **PHASE 3 COMPLETE** — All 9 steps done.

## Completed This Session

### Step 3.7 — PA Configuration
- PA Admin API password: `2FederateM0re` (NOT `2Access` — see Key Learnings)
- Created: Site (pingam:8080), App /am, App /api, MessageTransform rule
- Attached rule to both applications' root resources
- **Mount path fix:** `/opt/staging/deploy` → `/opt/out/instance/deploy`
  (ping-devops hooks DON'T copy staging/deploy → out/instance/deploy)
- Transform pipeline verified: callbacks → clean `fields[]` + headers

### Step 3.8 — WebAuthn Journey Import
- Both WebAuthnJourney and UsernamelessJourney imported (14 nodes total)
- Both invocable via `authIndexType=service`

### Step 3.9 — Final Verification
- All 4 pods Running 1/1
- PD LDAPS ✅ AM health ✅ PA proxy 200 ✅ Transform active ✅ Journeys ✅

## Key Learnings

### PA Admin Password Override
`PA_ADMIN_PASSWORD_INITIAL=2Access` is only the seed password. After first
start, PingAccess uses `PING_IDENTITY_PASSWORD` (from global envs) as the
actual admin password. In our deployment: `2FederateM0re`.

### Plugin JAR Mount Path — deploy vs staging
The `ping-devops` container hooks do NOT copy `/opt/staging/deploy/` →
`/opt/out/instance/deploy/`. The init container writes to an emptyDir, which
must be mounted at `/opt/out/instance/deploy` directly (not `/opt/staging/deploy`).

### PD FQDN Hostname Verification
PD's self-signed cert CN matches the K8s headless service FQDN, not the short
ClusterIP service name. AM's LDAP SDK does SSL hostname verification, causing
`Client-Side Timeout` when the hostname doesn't match the cert CN.

## Documentation Updated
- `docs/operations/kubernetes-operations-guide.md` — PA config section 5b, deploy path fix, troubleshooting
- `docs/architecture/knowledge-map.md` — added K8s ops guide entry
- `deployments/platform/PLAN.md` — Steps 3.7, 3.8, 3.9 marked complete
- KI: `pingam_platform_deployment` — new `kubernetes_infrastructure.md` artifact, gotchas #10-12

## Next Steps
1. **Phase 4** — Networking & Ingress (k3s Traefik IngressRoute)
2. **Phase 5** — E2E testing on K8s
