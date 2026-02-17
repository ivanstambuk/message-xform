# Current Session — K8s Platform Deployment (Phase 3)

## Focus
Kubernetes deployment of PingAM + PingDirectory + PingAccess on local k3s cluster.
Completing Phase 3 steps from `deployments/platform/PLAN.md`.

## Status
✅ **PHASE 3 COMPLETE** — All 9 steps done.

## Completed This Session

### Step 3.2 — PD Schema Tweaks (`c4a1b60`)
- Applied `single-structural-objectclass-behavior: accept` via `dsconfig`
- Added `etag` attribute to PD schema via `ldapmodify` (changetype: modify format)
- Created `CTS ETag` mirror virtual attribute (`ds-entry-checksum → etag`)
- Created `pd-profile` ConfigMap for fresh deploys, mounted in `values-local.yaml`

### Step 3.4 — AM Configuration (`13332d5`)
- Ran configurator POST via `kubectl exec curl`
- **Critical gotcha: PD FQDN** — see Key Learnings below.
- 98 install.log steps, 0 errors, 16s completion

### Step 3.5 — Test Users (`d02fc68`)
- 10 test users (user.1–user.10) loaded via `kubectl cp` + `ldapmodify`
- `user.1` callback auth verified against AM

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

## Phase 3 Progress — ALL COMPLETE

| Step | Task | Done |
|------|------|------|
| 3.1 | Deploy PingDirectory | ✅ |
| 3.2 | PD schema tweaks | ✅ |
| 3.3 | Deploy PingAM standalone | ✅ |
| 3.4 | AM configuration | ✅ |
| 3.5 | Test users | ✅ |
| 3.6 | Deploy PingAccess | ✅ |
| 3.7 | PA configuration | ✅ |
| 3.8 | WebAuthn journey import | ✅ |
| 3.9 | Full verification | ✅ |

## Key Learnings

### PD FQDN Hostname Verification
PD's self-signed cert CN matches the K8s headless service FQDN, not the short
ClusterIP service name. AM's LDAP SDK does SSL hostname verification, causing
`Client-Side Timeout` when the hostname doesn't match the cert CN.

### PA Admin Password Override
`PA_ADMIN_PASSWORD_INITIAL=2Access` is only the seed password. After first
start, PingAccess uses `PING_IDENTITY_PASSWORD` (from global envs) as the
actual admin password. In our deployment: `2FederateM0re`.

### Plugin JAR Mount Path — deploy vs staging
The `ping-devops` container hooks do NOT copy `/opt/staging/deploy/` →
`/opt/out/instance/deploy/`. The init container writes to an emptyDir, which
must be mounted at `/opt/out/instance/deploy` directly (not `/opt/staging/deploy`).
Without this fix, the MessageTransform plugin JAR never reaches the classpath.

### K8s PD Schema — Dual Strategy
Apply live via `kubectl exec` (ldapmodify + dsconfig), AND mount a ConfigMap
for fresh deploys. ldapmodify must use `changetype: modify` format on K8s.

### Init Container Self-Healing for Certs
The init container pattern for PD cert trust re-retrieves and re-imports
the cert on every pod restart. Superior to Docker's manual re-import.

## Environment
- k3s on local Linux (bare metal)
- Namespace: `message-xform`
- KUBECONFIG: `/etc/rancher/k3s/k3s.yaml`
- Helm chart: `pingidentity/ping-devops` revision 4
- PingAM: standalone Deployment (not in chart)

## Next Steps
1. **Phase 4** — Networking & Ingress
2. **Phase 5** — E2E testing on K8s
