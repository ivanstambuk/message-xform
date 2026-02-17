# Current Session — K8s E2E Test Validation (Phase 5)

## Focus
Running the platform E2E test suite against the Kubernetes deployment.
Phase 5 from `deployments/platform/PLAN.md`.

## Status
🟡 **PHASE 5 IN PROGRESS** — 9/14 scenarios pass (5/8 steps done).

## Completed This Session

### Phase 5 — E2E Test Validation

#### Step 5.1 — karate-config.js K8s Environment
- Added `k8s` environment block to `karate-config.js` with:
  - `paEngineUrl = https://localhost` (Traefik port 443)
  - `paEngineHost = localhost` (matches `*:443` VH, implied port for HTTPS)
  - `paAdminUrl = https://localhost:29000/...` (port-forwarded)
  - `amDirectUrl = http://127.0.0.1:28080/am` (port-forwarded)
  - `amHostHeader = pingam:8080` (matches AM boot.json FQDN including port)
  - `paPassword = 2Access` (from values-local.yaml product-level override)
- Enabled `karate.configure('lowerCaseResponseHeaders', true)` **globally** to
  normalize response header keys across Docker (lowercase) and K8s (Traefik title-cases)

#### Step 5.1b — run-e2e.sh K8s Support
- Added `--env k8s` flag to `run-e2e.sh`
- Script starts `kubectl port-forward` for AM (28080→8080) and PA Admin (29000→9000)
- Cleanup via `trap EXIT` ensures port-forwards terminate on script exit
- Passes `-Dkarate.env=k8s` to Karate JAR

#### Steps 5.2–5.5 — Test Execution
- `auth-login.feature` — 3/3 ✅
- `auth-logout.feature` — 1/1 ✅
- `clean-url-login.feature` — 3/3 ✅
- `clean-url-passkey.feature` — 2/2 ✅

### Documentation Capture
- PLAN.md: Phase 5 steps 5.1–5.5 marked complete
- E2E guide: 3 new pitfalls (P14 AM Host port, P15 Traefik casing, P11 fix expansion)
- K8s ops guide: PA password fix (2FederateM0re → 2Access), E2E section added
- Knowledge items updated (kubernetes_infrastructure, gotchas_and_debugging)
- Knowledge map entries updated for K8s, E2E guide, platform e2e directory

## Key Learnings

### Traefik Title-Cases HTTP/1.1 Response Headers
Traefik normalizes HTTP/1.1 response header names to title-case (`x-auth-session` →
`X-Auth-Session`). HTTP/2 mandates lowercase, so `curl` sees lowercase, but Karate's
Apache HttpClient uses HTTP/1.1 and sees title-cased headers. Fix: `lowerCaseResponseHeaders`.

### AM Host Header Requires Port
AM validates the Host header against its boot.json FQDN. When port-forwarding,
the Host must be `pingam:8080` (not just `pingam`) to match `http://pingam:8080/am`.

### PA Admin Password Is Product-Level
`values-local.yaml` sets `PING_IDENTITY_PASSWORD: "2Access"` under
`pingaccess-admin.container.envs`, not globally. Previous docs incorrectly said `2FederateM0re`.

## Next Steps
1. **Phase 5.6–5.7** — Passkey E2E tests on K8s (requires WebAuthn device registration)
2. **Phase 5.8** — Full suite pass → update e2e-results.md
3. **Phase 6** — Cloud deployment guides (AKS, GKE, EKS)
