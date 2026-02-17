# Current Session — K8s Platform Deployment (Phase 4)

## Focus
Kubernetes deployment of PingAM + PingDirectory + PingAccess on local k3s cluster.
Completing Phase 4 (Networking & Ingress) from `deployments/platform/PLAN.md`.

## Status
✅ **PHASE 4 COMPLETE** — All 5 steps done.

## Completed This Session

### Housekeeping — Vendor Doc Symlinks
- Restored 6 symlinks in `binaries/*/docs/*.txt` into shared artifact store
- Root cause: bind mount overlay hid git-tracked symlinks
- Actual vendor doc text files (gitignored) are missing from shared store — NOT blocking

### Phase 4 — Networking & Ingress

#### Step 4.1 — IngressRoute
- Created `k8s/ingress.yaml` with 4 resources:
  - `ServersTransport/pa-transport` — `insecureSkipVerify: true` for PA's self-signed cert
  - `IngressRoute/platform-ingress` — HTTPS entrypoint, both `/am` and `/api` paths
  - `IngressRoute/platform-ingress-redirect` — HTTP → HTTPS 301 redirect
  - `Middleware/redirect-to-https` — redirect scheme middleware

#### Step 4.2 — TLS Termination
- Traefik 3.6.7 terminates TLS using its default self-signed cert
- Re-encrypts to PA engine on port 3000 (HTTPS backend)
- `ServersTransport` skips backend TLS verification

#### Step 4.3–4.4 — Verification
- `/api/v1/auth/login` → clean `fields[]` JSON ✅
- `/am/json/authenticate` → transformed callbacks ✅
- Custom headers injected (`x-auth-provider`, `x-auth-session`) ✅
- HTTP → HTTPS redirect (301) ✅

#### Step 4.5 — Host Header Handling
- PA virtual host `*:443` created for Ingress port matching
- Both apps (`/am`, `/api`) bound to VH 2 (`*:3000`) and VH 3 (`*:443`)
- PA `host: *` accepts any hostname — works with `localhost`, IP, or DNS name

## Key Learnings

### PA Virtual Host Port Matching
PA does virtual host matching on both `host` AND `port`. When Traefik terminates
TLS and forwards to PA, the `Host` header retains the original port (443 via
Traefik), not PA's internal port (3000). Solution: create a `*:443` virtual host
in PA and bind all applications to it alongside the `*:3000` VH.

### Traefik ServersTransport for HTTPS Backends
When the backend service speaks HTTPS (as PA does), Traefik needs a
`ServersTransport` with `insecureSkipVerify: true` and `scheme: https` in the
IngressRoute service definition. Without `scheme: https`, Traefik sends HTTP
to the backend and PA rejects it.

## Documentation Updated
- `deployments/platform/PLAN.md` — Phase 4 steps marked complete, K3 deploy path fixed
- `deployments/platform/k8s/ingress.yaml` — new file

## Next Steps
1. **Phase 5** — E2E testing on K8s (14-scenario Karate suite)
2. **Phase 6** — Cloud deployment guides (AKS, GKE, EKS)
