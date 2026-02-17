# Platform Deployment — PingAccess + PingAM + PingDirectory

> End-to-end identity platform combining PingAccess (reverse proxy with
> message-xform plugin), PingAM (authentication journeys), and PingDirectory
> (config store, CTS, user store — single directory for all PingAM needs).

## Architecture

```
                 ┌─────────────────────────┐
   Browser ────▶│     PingAccess  (9.0)    │
                │  (reverse proxy)         │
                │  + message-xform plugin  │
                │  port 3000               │
                └───────────┬──────────────┘
                            │ proxied HTTP
                            ▼
                 ┌─────────────────────────┐
                 │     PingAM  (8.0.2)     │
                 │  (authentication)       │
                 │  journeys, OAuth2       │
                 │  port 8080 (/am)        │
                 └──────────┬──────────────┘
                            │ LDAPS (config +
                            │ CTS + users)
                            ▼
                 ┌─────────────────────────┐
                 │  PingDirectory (11.0)   │
                 │  (all-in-one store)     │
                 │  AM config + CTS +      │
                 │  user/identity store    │
                 │  port 1636              │
                 └─────────────────────────┘
```

### Deployment Mode: Kubernetes

The platform is deployed on **Kubernetes** using the `ping-devops` Helm chart
(PD + PA) and a standalone Deployment manifest (PingAM). Supported targets:

| Target | StorageClass | Ingress | Image Source |
|--------|-------------|---------|--------------|
| **k3s** (local) | `local-path` | Traefik (built-in) | `k3s ctr images import` |
| **AKS** | `managed-csi` | nginx / AGIC | ACR |
| **GKE** | `standard-rwo` | nginx / GCE | Artifact Registry |
| **EKS** | `gp3` | nginx / ALB | ECR |

### Key Finding: Single PingDirectory

Live testing (Feb 2026) confirmed **PingAM 8.0.2 runs directly on PingDirectory
11.0** without needing PingDS (ForgeRock DS). Two PD config tweaks are required:

1. **Schema relaxation**: `single-structural-objectclass-behavior: accept`
2. **ETag virtual attribute**: Mirror VA maps `etag` → `ds-entry-checksum`

## Products

| Product | Version | Role |
|---------|---------|------|
| PingAccess | 9.0 | Reverse proxy, message-xform plugin host |
| PingAM | 8.0.2 | Authentication engine, journeys, OAuth2 |
| PingDirectory | 11.0 | Config store, CTS, policy store, user/identity store |

## Quick Start (Local k3s)

```bash
# 1. Install k3s + Helm
curl -sfL https://get.k3s.io | sh -
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm repo add pingidentity https://helm.pingidentity.com/

# 2. Build the shadow JAR
cd /path/to/message-xform
./gradlew :adapter-pingaccess:shadowJar

# 3. Build & import PingAM image
cd binaries/pingam && docker build . -f docker/Dockerfile -t pingam:8.0.2
docker save pingam:8.0.2 | sudo k3s ctr images import -

# 4. Create namespace, secrets, ConfigMaps
kubectl create namespace message-xform
# (see Kubernetes Operations Guide for full commands)

# 5. Deploy via Helm
cd deployments/platform
helm install platform pingidentity/ping-devops \
  -n message-xform -f k8s/values-local.yaml

# 6. Deploy PingAM (standalone)
kubectl apply -f k8s/pingam-deployment.yaml -n message-xform

# 7. Deploy Ingress
kubectl apply -f k8s/ingress.yaml -n message-xform

# 8. Wait for all pods Ready
kubectl get pods -n message-xform -w

# 9. Run E2E tests
cd e2e && ./run-e2e.sh --env k8s
```

## Directory Layout

```
deployments/platform/
├── README.md                     ← This file
├── PLAN.md                       ← Migration plan with live tracker
├── k8s/
│   ├── values-local.yaml         ← Helm values: k3s local deployment
│   ├── values-aks.yaml           ← Helm values: AKS overlay
│   ├── values-gke.yaml           ← Helm values: GKE overlay
│   ├── values-eks.yaml           ← Helm values: EKS overlay
│   ├── pingam-deployment.yaml    ← PingAM standalone Deployment + Service + PVC
│   ├── ingress.yaml              ← Traefik IngressRoute (k3s local)
│   ├── cloud-deployment-guide.md ← Cloud deployment guide (AKS/GKE/EKS)
│   └── docker/
│       └── Dockerfile.plugin     ← Plugin init container image (cloud only)
├── config/
│   ├── server.xml                ← Tomcat SSL configuration for PingAM
│   ├── pd-post-setup.dsconfig    ← PD schema relaxation + etag VA
│   ├── etag-schema.ldif          ← etag attribute schema definition
│   └── test-users.ldif           ← Test user LDIF entries
├── specs/                        ← Transform specs (mounted via ConfigMap)
├── profiles/                     ← Transform profiles (mounted via ConfigMap)
├── journeys/                     ← PingAM authentication tree exports
├── e2e/                          ← E2E Karate test suite (14 scenarios)
├── secrets/                      ← Generated TLS certs (gitignored)
└── scripts/
    ├── generate-keys.sh          ← TLS keypair generation
    └── legacy/                   ← Docker Compose era scripts (archived)
```

## E2E Test Results

The full 14-scenario E2E suite passes on both Docker and Kubernetes:

| Environment | Result | Date |
|-------------|--------|------|
| Docker Compose | 14/14 ✅ | 2026-02-17 |
| Kubernetes (k3s) | 14/14 ✅ | 2026-02-17 |

See [e2e/e2e-results.md](e2e/e2e-results.md) for the detailed breakdown.

## See Also

- [Implementation Plan](./PLAN.md) — phased migration plan with live tracker
- [Kubernetes Operations Guide](../../docs/operations/kubernetes-operations-guide.md) — k3s, Helm, volume mounts, debugging
- [Cloud Deployment Guide](./k8s/cloud-deployment-guide.md) — AKS, GKE, EKS
- [Platform Deployment Guide](../../docs/operations/platform-deployment-guide.md) — comprehensive walkthrough
- [PingAM Operations Guide](../../docs/operations/pingam-operations-guide.md) — image build, REST API patterns
- [PingAccess Operations Guide](../../docs/operations/pingaccess-operations-guide.md) — Admin API, plugin setup
- [E2E Testing Guide](../../docs/operations/e2e-karate-operations-guide.md) — Karate test suite documentation
