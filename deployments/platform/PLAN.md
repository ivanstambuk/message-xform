# Platform Deployment — Docker Compose → Kubernetes Migration Plan

## Goal

Replace the Docker Compose development stack with a production-grade Kubernetes
deployment using **Ping Identity official Helm charts** (`ping-devops` v0.11.17).
The result must:

1. Run locally on this machine (k3s — single node)
2. Deploy identically to AKS, GKE, and EKS with only values-file changes
3. Preserve all 14 E2E test scenarios (clean URLs, passkeys, login, logout)
4. Use ConfigMaps for transform specs/profiles (hot-reload compatible)
5. Use init containers for the message-xform shadow JAR
6. Remove Docker Compose entirely after K8s is validated

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  Kubernetes Cluster (k3s local / AKS / GKE / EKS)          │
│                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────┐ │
│  │  PingDirectory   │  │    PingAM        │  │ PingAccess  │ │
│  │  (Helm: ping-    │  │  (Helm: custom   │  │ (Helm: ping-│ │
│  │   devops)        │  │   values, custom │  │  devops +   │ │
│  │                  │  │   image)         │  │  init ctr)  │ │
│  │  PVC: pd-data    │  │  PVC: am-data    │  │             │ │
│  └────────┬─────────┘  └────────┬─────────┘  └──────┬──────┘ │
│           │ LDAPS :1636         │ HTTP :8080         │        │
│           └─────────────────────┘                    │        │
│                                                      │        │
│  ┌───────────────────────────────────────────────────┘        │
│  │                                                            │
│  │  ConfigMap: mxform-specs     (specs/*.yaml)                │
│  │  ConfigMap: mxform-profiles  (profiles/*.yaml)             │
│  │  Secret:    tls-certs        (self-signed or cert-manager) │
│  │  Secret:    pd-credentials                                 │
│  │  Secret:    am-credentials                                 │
│  │  Secret:    pa-credentials                                 │
│  │                                                            │
│  ├── Ingress (Traefik on k3s / cloud LB on AKS/GKE/EKS)     │
│  │     ├── /am/*   → PingAM Service                          │
│  │     └── /api/*  → PingAccess Service                      │
│  │                                                            │
│  └── Jobs:                                                    │
│        ├── configure-pd       (PD schema tweaks)              │
│        ├── configure-am       (AM bootstrapper)               │
│        ├── configure-pa       (PA Admin API wiring)           │
│        └── import-journeys    (WebAuthn tree import)          │
└─────────────────────────────────────────────────────────────┘
```

---

## Technology Decisions

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| K1 | Local K8s distribution | **k3s** | CNCF-certified, production-grade, <100 MB binary, includes Traefik ingress + local-path-provisioner. Same K8s API as AKS/GKE/EKS. 512 MB RAM minimum — we have 32 GB. |
| K2 | Helm chart | **`ping-devops` v0.11.17** | Official Ping Identity unified chart. Supports PingDirectory, PingAccess. PingAM will need a custom values entry (see K4). |
| K3 | PingAccess plugin injection | **Init container** | Init container copies `adapter-pingaccess-0.1.0-SNAPSHOT.jar` into a shared `emptyDir` volume mounted at `/opt/server/deploy/`. No custom PA image needed. |
| K4 | PingAM image | **Custom image (existing)** | We already build a custom AM WAR-on-Tomcat image. Push to a local registry (k3s) or cloud registry (ACR/GCR/ECR). The `ping-devops` chart supports `externalImage` for non-Ping images. |
| K5 | Transform specs/profiles | **ConfigMaps** | Hot-reload works because the engine polls the filesystem. ConfigMap volume mounts update in-place (kubelet sync period ~60s, engine polls every 30s). |
| K6 | Secrets | **K8s Secrets** | Credentials for PD, AM, PA stored as Secrets. Cloud deployments can use External Secrets Operator or cloud-native secret managers. |
| K7 | Persistence | **PVCs** | `local-path-provisioner` on k3s, cloud StorageClass on AKS/GKE/EKS. PD and AM both need persistent data across restarts. |
| K8 | TLS | **Self-signed** (local) / **cert-manager** (cloud) | k3s local: self-signed certs in K8s Secrets. Cloud: cert-manager with Let's Encrypt or cloud CA issuers. |
| K9 | Ingress | **Traefik** (k3s built-in) / **cloud-native** | k3s includes Traefik. AKS uses AGIC/nginx, GKE uses GCE ingress, EKS uses ALB. Values overlay handles the switch. |
| K10 | Configuration jobs | **Helm hooks (post-install Jobs)** | PA Admin API wiring, AM configuration, PD schema tweaks, WebAuthn journey import — all run as one-shot K8s Jobs triggered by `helm install`. |
| K11 | Namespace | **`message-xform`** | Single namespace for all platform components. Cloud deployments can use namespace-per-environment. |
| K12 | Container registry (local) | **k3s containerd import** | `k3s ctr images import` — no registry server needed locally. For cloud, push to ACR/GCR/ECR. |

---

## Open Questions (with Recommendations)

| # | Question | Recommendation | Status |
|---|----------|---------------|--------|
| Q1 | Should we use the `ping-devops` chart for PingAM or deploy AM separately? | **Use `ping-devops` with `externalImage`** — the chart supports arbitrary images via the `externalImage` block. This keeps all three products in one Helm release. If the chart's AM support is too limited, fall back to a standalone Deployment manifest. | **OPEN — needs validation** |
| Q2 | How to handle the AM ↔ PD cert trust (JVM truststore injection)? | **Init container on AM pod** that copies PD's CA cert and runs `keytool -importcert` into the JVM truststore before Tomcat starts. This replaces the manual `docker exec` step. | Recommended |
| Q3 | Should configuration scripts become Helm hooks or a separate `kubectl apply`? | **Helm post-install hooks** — they run once after all Pods are Ready, have retry semantics, and are part of the release lifecycle. The scripts need to be containerized (Alpine + curl + python3). | Recommended |
| Q4 | E2E tests: run inside the cluster or from outside? | **Outside the cluster** (same as now) — Karate runs on the host machine, pointing at the Ingress endpoint (or NodePort). This matches how a CI pipeline would test. | Recommended |
| Q5 | When to remove Docker Compose? | **After Phase 5** — only after all 14 E2E tests pass on k3s AND at least one cloud deployment guide is verified. | Recommended |

---

## Phases

### Phase 1 — Local K8s Bootstrap *(~1 session)*
Install k3s, Helm, kubectl. Verify cluster health.

| Step | Task | Done |
|------|------|------|
| 1.1 | Install k3s (`curl -sfL https://get.k3s.io \| sh -`) | |
| 1.2 | Configure kubectl (`export KUBECONFIG=/etc/rancher/k3s/k3s.yaml`) | |
| 1.3 | Install Helm (`curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 \| bash`) | |
| 1.4 | Add Ping Identity Helm repo (`helm repo add pingidentity https://helm.pingidentity.com/`) | |
| 1.5 | Create namespace `message-xform` | |
| 1.6 | Verify: `kubectl get nodes` shows Ready, `helm list` works | |
| 1.7 | Document k3s setup in deployment guide | |

### Phase 2 — Helm Values & Secrets *(~1 session)*
Create the values files and K8s Secrets for the three products.

| Step | Task | Done |
|------|------|------|
| 2.1 | Study `helm show values pingidentity/ping-devops` — map to our Docker Compose config | |
| 2.2 | Create `k8s/values-local.yaml` — base values for local k3s deployment | |
| 2.3 | Create K8s Secrets: `pd-credentials`, `am-credentials`, `pa-credentials`, `pa-license` | |
| 2.4 | Create ConfigMap `mxform-specs` from `specs/*.yaml` | |
| 2.5 | Create ConfigMap `mxform-profiles` from `profiles/*.yaml` | |
| 2.6 | Create TLS Secret from existing `secrets/tlskey.p12` and `secrets/pubCert.crt` | |
| 2.7 | Validate Q1: test `ping-devops` chart with PingAM via `externalImage` | |

### Phase 3 — Core Deployment *(~2 sessions)*
Deploy PingDirectory, PingAM, and PingAccess via Helm.

| Step | Task | Done |
|------|------|------|
| 3.1 | Deploy PingDirectory: verify LDAPS, readiness probe, PVC | |
| 3.2 | Deploy PD schema tweaks (Job or init container): etag VA + structural objectclass | |
| 3.3 | Deploy PingAM: custom image import into k3s, init container for PD cert trust | |
| 3.4 | AM configuration Job: run `configure-am.sh` logic as a K8s Job | |
| 3.5 | AM post-config Job: create test users (`configure-am-post.sh` logic) | |
| 3.6 | Deploy PingAccess: init container for shadow JAR, ConfigMap mounts for specs/profiles | |
| 3.7 | PA configuration Job: `configure-pa.sh` + `configure-pa-plugin.sh` + `configure-pa-api.sh` | |
| 3.8 | WebAuthn journey import Job: `import-webauthn-journey.sh` | |
| 3.9 | Verify: all Pods Running/Completed, PD healthy, AM responding, PA proxying | |

### Phase 4 — Networking & Ingress *(~1 session)*
Configure Ingress for clean URL routing and external access.

| Step | Task | Done |
|------|------|------|
| 4.1 | Create Ingress resource: `/am/*` → PA Service, `/api/*` → PA Service | |
| 4.2 | Configure TLS termination at Ingress (Traefik on k3s) | |
| 4.3 | Test: `curl -sk https://localhost/api/v1/auth/login` returns field prompts | |
| 4.4 | Test: `curl -sk https://localhost/am/json/authenticate` returns callbacks | |
| 4.5 | Verify Host header handling (PA virtual host must match Ingress) | |

### Phase 5 — E2E Test Validation *(~1 session)*
Run the full 14-scenario Karate suite against the k3s cluster.

| Step | Task | Done |
|------|------|------|
| 5.1 | Update `karate-config.js` with k3s endpoints (Ingress URL or NodePort) | |
| 5.2 | Run `clean-url-login.feature` → 3/3 pass | |
| 5.3 | Run `clean-url-passkey.feature` → 2/2 pass | |
| 5.4 | Run `auth-login.feature` → 3/3 pass | |
| 5.5 | Run `auth-logout.feature` → 1/1 pass | |
| 5.6 | Run `auth-passkey.feature` → 3/3 pass | |
| 5.7 | Run `auth-passkey-usernameless.feature` → 2/2 pass | |
| 5.8 | Full suite: 14/14 pass → update `e2e-results.md` | |

### Phase 6 — Cloud Deployment Guides *(~1–2 sessions)*
Document cloud-specific values overlays for the three major providers.

| Step | Task | Done |
|------|------|------|
| 6.1 | Create `k8s/values-aks.yaml` — AKS-specific: StorageClass, Ingress (AGIC), ACR image refs | |
| 6.2 | Create `k8s/values-gke.yaml` — GKE-specific: StorageClass, GCE Ingress, GCR/Artifact Registry | |
| 6.3 | Create `k8s/values-eks.yaml` — EKS-specific: StorageClass (gp3), ALB Ingress, ECR | |
| 6.4 | Document container registry push process for each cloud | |
| 6.5 | Document cert-manager setup for production TLS | |

### Phase 7 — Docker Compose Removal & Documentation *(~1 session)*
Remove the old stack, rewrite the deployment guide.

| Step | Task | Done |
|------|------|------|
| 7.1 | Delete `docker-compose.yml` and `.env.template` | |
| 7.2 | Delete Docker-specific configure scripts (or archive to `scripts/legacy/`) | |
| 7.3 | Rewrite `platform-deployment-guide.md` for Kubernetes | |
| 7.4 | Update `llms.txt`, `knowledge-map.md`, root `PLAN.md` | |
| 7.5 | Update `README.md` in `deployments/platform/` | |
| 7.6 | Final commit: remove Docker Compose artifacts | |

---

## File Layout (Target State)

```
deployments/platform/
├── k8s/
│   ├── values-local.yaml          # k3s local deployment
│   ├── values-aks.yaml            # Azure Kubernetes Service
│   ├── values-gke.yaml            # Google Kubernetes Engine
│   ├── values-eks.yaml            # Amazon Elastic Kubernetes Service
│   ├── secrets/                   # Templates for K8s Secrets (not committed)
│   │   ├── pd-credentials.yaml.template
│   │   ├── am-credentials.yaml.template
│   │   ├── pa-credentials.yaml.template
│   │   └── pa-license.yaml.template
│   ├── jobs/
│   │   ├── configure-pd.yaml      # PD schema tweaks (Job)
│   │   ├── configure-am.yaml      # AM bootstrapper (Job)
│   │   ├── configure-pa.yaml      # PA Admin API wiring (Job)
│   │   └── import-journeys.yaml   # WebAuthn tree import (Job)
│   └── configmaps/
│       ├── kustomization.yaml     # Generates ConfigMaps from specs/ and profiles/
│       └── (or created via helm values)
├── specs/                         # Transform specs (unchanged)
├── profiles/                      # Profiles (unchanged)
├── e2e/                           # E2E tests (unchanged, config updated)
├── secrets/                       # TLS certs (existing, reused)
└── scripts/
    └── deploy-local.sh            # One-command local deployment script
```

---

## Quick Start (Target)

```bash
# 1. Install prerequisites (one-time)
curl -sfL https://get.k3s.io | sh -
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm repo add pingidentity https://helm.pingidentity.com/

# 2. Build the shadow JAR
cd /path/to/message-xform
./gradlew :adapter-pingaccess:shadowJar

# 3. Deploy
cd deployments/platform
./scripts/deploy-local.sh

# 4. Wait for all pods Ready
kubectl -n message-xform get pods -w

# 5. Run E2E tests
cd e2e && ./run-e2e.sh
```

---

## Cloud Quick Start (Target)

```bash
# AKS
az aks get-credentials --resource-group rg-mxform --name aks-mxform
helm install platform pingidentity/ping-devops \
  -n message-xform --create-namespace \
  -f k8s/values-local.yaml \
  -f k8s/values-aks.yaml

# GKE
gcloud container clusters get-credentials gke-mxform --zone us-central1-a
helm install platform pingidentity/ping-devops \
  -n message-xform --create-namespace \
  -f k8s/values-local.yaml \
  -f k8s/values-gke.yaml

# EKS
aws eks update-kubeconfig --name eks-mxform
helm install platform pingidentity/ping-devops \
  -n message-xform --create-namespace \
  -f k8s/values-local.yaml \
  -f k8s/values-eks.yaml
```

---

## Resource Requirements

| Component | CPU Request | Memory Request | Storage |
|-----------|-------------|----------------|---------|
| PingDirectory | 500m | 768Mi | 2Gi PVC |
| PingAM | 500m | 1Gi | 1Gi PVC |
| PingAccess | 250m | 512Mi | — (stateless) |
| Config Jobs | 100m | 128Mi | — (ephemeral) |
| **Total** | **1350m** | **~2.5 Gi** | **3 Gi** |

Machine capacity: 16 cores, 32 GB RAM, 383 GB disk — well within limits.

---

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|-----------|
| `ping-devops` chart doesn't support PingAM well | Medium | Fall back to standalone Deployment manifest for AM. PA and PD use the chart. |
| k3s Traefik ingress doesn't match PA virtual host expectations | Medium | Configure Traefik to pass Host header correctly, or use NodePort + direct curl. |
| Configuration Jobs fail due to pod ordering | High | Use `kubectl wait --for=condition=ready` in Job scripts, or init containers with readiness checks. |
| Hot-reload ConfigMap propagation delay | Low | kubelet syncs ConfigMaps every ~60s. Engine polls every 30s. Max delay ~90s, acceptable. |
| PD ↔ AM cert trust breaks in K8s (no shared filesystem) | Medium | Init container on AM pod imports PD CA cert. PD CA cert stored as K8s Secret. |
