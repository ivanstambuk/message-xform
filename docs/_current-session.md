# Current Session — K8s Platform Deployment (Phase 3)

## Focus
Kubernetes deployment of PingAM + PingDirectory + PingAccess on local k3s cluster.
Completing Phase 3 steps from `deployments/platform/PLAN.md`.

## Status
🔄 **IN PROGRESS** — Steps 3.1–3.6 done, Steps 3.7–3.8 remaining.

## Completed This Session

### Step 3.2 — PD Schema Tweaks (`c4a1b60`)
- Applied `single-structural-objectclass-behavior: accept` via `dsconfig`
- Added `etag` attribute to PD schema via `ldapmodify` (changetype: modify format)
- Created `CTS ETag` mirror virtual attribute (`ds-entry-checksum → etag`)
- Created `pd-profile` ConfigMap for fresh deploys, mounted in `values-local.yaml`
- Helm upgraded to revision 3; PD restarted and confirmed tweaks persisted

### Step 3.4 — AM Configuration (`13332d5`)
- Ran configurator POST via `kubectl exec curl` (not a K8s Job)
- **Critical gotcha: PD FQDN** — PD's self-signed cert CN =
  `pingdirectory-0.pingdirectory-cluster.message-xform.svc.cluster.local`.
  AM's LDAP SDK performs hostname verification. Short name `pingdirectory` →
  `Client-Side Timeout`. Full FQDN → ✅ `Configuration complete!` in 16s.
- 98 install.log steps, 0 errors
- Admin callback auth produces valid `tokenId`

### Step 3.5 — Test Users (`d02fc68`)
- 10 test users (user.1–user.10) loaded via `kubectl cp` + `ldapmodify`
- `user.1` callback auth verified against AM

### Documentation (`9764592`)
- PingAM ops guide: new Part VI — Kubernetes Deployment (§11, §12, §13k)
- K8s ops guide: AM configurator section, PD FQDN troubleshooting row
- Platform deployment guide: K8s hostname callout, K8s dual strategy for PD tweaks
- All three guides cross-referenced

## Phase 3 Progress

| Step | Task | Done |
|------|------|------|
| 3.1 | Deploy PingDirectory | ✅ |
| 3.2 | PD schema tweaks | ✅ |
| 3.3 | Deploy PingAM standalone | ✅ |
| 3.4 | AM configuration | ✅ |
| 3.5 | Test users | ✅ |
| 3.6 | Deploy PingAccess | ✅ |
| 3.7 | PA configuration Job | ⬜ NEXT |
| 3.8 | WebAuthn journey import | ⬜ |
| 3.9 | Full verification | ✅ (PD/PA/AM all Running) |

## Key Learnings

### PD FQDN Hostname Verification
PD's self-signed cert CN matches the K8s headless service FQDN, not the short
ClusterIP service name. AM's LDAP SDK does SSL hostname verification, causing
`Client-Side Timeout` when the hostname doesn't match the cert CN. This is NOT
a network timeout — it's an SSL handshake failure masquerading as one.

### K8s PD Schema — Dual Strategy
On K8s, PD is already running when tweaks are needed. Apply live via `kubectl exec`
(ldapmodify for schema, dsconfig for VA + relaxation), AND mount a `pd-profile`
ConfigMap for fresh deployments. The `ldapmodify` must use `changetype: modify`
format (not the pre-setup file format used for profile mounting).

### Init Container Self-Healing
The K8s init container pattern for PD cert trust is superior to Docker Compose.
Every pod restart re-retrieves and re-imports the cert automatically. Docker
requires manual re-import after container recreation.

### ldapmodify --noPropertiesFile
PD containers have a tool.properties file that auto-injects `--bindDN` and
`--bindPasswordFile`. If you also specify `--bindPassword`, it conflicts and
fails. Use `--noPropertiesFile` when specifying credentials inline, or omit
credentials to use the file-based defaults.

## Environment
- k3s on local Linux (bare metal)
- Namespace: `message-xform`
- KUBECONFIG: `/etc/rancher/k3s/k3s.yaml`
- PD headless FQDN: `pingdirectory-0.pingdirectory-cluster.message-xform.svc.cluster.local`
- Helm chart: `pingidentity/ping-devops` revision 3
- PingAM: standalone Deployment (not in chart)

## Next Steps
1. **Step 3.7** — PA configuration: `configure-pa.sh` + plugin + API app
2. **Step 3.8** — WebAuthn journey import
3. **Phase 4** — Networking & Ingress
