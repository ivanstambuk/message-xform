# Cloud Deployment Guide

> How to deploy the message-xform platform on AKS, GKE, and EKS.
> Covers container image preparation, registry push, Helm installation,
> Ingress configuration, and production TLS via cert-manager.

---

## Table of Contents

| # | Section | Description |
|---|---------|-------------|
| 1 | [Architecture](#1-architecture) | Cloud deployment architecture overview |
| 2 | [Prerequisites](#2-prerequisites) | Common requirements for all providers |
| 3 | [Container Images](#3-container-images) | How to build and push images to cloud registries |
| 4 | [AKS Deployment](#4-aks-deployment) | Azure Kubernetes Service |
| 5 | [GKE Deployment](#5-gke-deployment) | Google Kubernetes Engine |
| 6 | [EKS Deployment](#6-eks-deployment) | Amazon Elastic Kubernetes Service |
| 7 | [Ingress Configuration](#7-ingress-configuration) | Cloud-native Ingress controllers |
| 8 | [cert-manager & Production TLS](#8-cert-manager--production-tls) | Automated certificate management |
| 9 | [PingAM on Cloud](#9-pingam-on-cloud) | Adapting the standalone Deployment |
| 10 | [Verification](#10-verification) | Post-deployment health checks |

---

## 1. Architecture

The cloud deployment uses the same Helm-based architecture as local k3s,
with three key differences:

| Concern | Local (k3s) | Cloud (AKS/GKE/EKS) |
|---------|-------------|----------------------|
| **Plugin JAR** | `hostPath` volume from build dir | Init container image from cloud registry |
| **StorageClass** | `local-path` | Cloud-native (`managed-csi` / `standard-rwo` / `gp3`) |
| **Ingress** | Traefik (k3s built-in) | Cloud-native (AGIC / GCE / ALB) or nginx-ingress |
| **TLS** | Self-signed (Traefik default) | cert-manager + Let's Encrypt |
| **PingAM image** | `k3s ctr images import` | Cloud container registry |

### Values file layering

```bash
# Cloud deployments layer a provider-specific overlay on top of the base:
helm install platform pingidentity/ping-devops \
  -n message-xform --create-namespace \
  -f k8s/values-local.yaml \          # Base config (products, resources, mounts)
  -f k8s/values-<provider>.yaml       # Cloud overrides (storage, images)
```

The base `values-local.yaml` is **not renamed** — it contains all product
configuration, volume mounts, and resource definitions. The cloud overlay
file only overrides the few settings that change per provider.

---

## 2. Prerequisites

Common to all cloud providers:

1. **Kubernetes cluster** — 1.26+ recommended (all providers)
2. **kubectl** — configured with cluster credentials
3. **Helm 3.12+** — `helm repo add pingidentity https://helm.pingidentity.com/`
4. **Shadow JAR** — build before deploying: `./gradlew :adapter-pingaccess:shadowJar`
5. **PingAM WAR image** — built from `binaries/pingam/docker/Dockerfile`
6. **Secrets** — PD, AM, PA credentials + licenses (same as local, see ops guide)
7. **ConfigMaps** — `mxform-specs` and `mxform-profiles` (same as local)

---

## 3. Container Images

### 3.1 Plugin init container image

The message-xform adapter JAR is injected into PingAccess via an init container.
Locally, we use a `hostPath` volume. On cloud, we package the JAR into a
minimal container image.

**Dockerfile** (`k8s/docker/Dockerfile.plugin`):

```dockerfile
FROM busybox:1.36
COPY adapter-pingaccess/build/libs/adapter-pingaccess-0.1.0-SNAPSHOT.jar /
```

**Build** (from project root):

```bash
# First, build the shadow JAR
./gradlew :adapter-pingaccess:shadowJar

# Then build the container image
docker build -t mxform-plugin:0.1.0 \
  -f deployments/platform/k8s/docker/Dockerfile.plugin .
```

### 3.2 PingAM image

PingAM uses a custom image (WAR deployed on Tomcat). The same image used
locally needs to be pushed to the cloud registry.

```bash
# Build (if not already built)
cd binaries/pingam && docker build . -f docker/Dockerfile -t pingam:8.0.2
```

### 3.3 Ping Identity images

PingDirectory and PingAccess images come from Docker Hub (`pingidentity/*`).
For cloud deployments, either:
- **Pull directly** from Docker Hub (requires internet access from nodes)
- **Mirror** to your cloud registry for airgap/performance:

```bash
# Mirror example (generic):
docker pull pingidentity/pingdirectory:11.0.0.1-latest
docker tag pingidentity/pingdirectory:11.0.0.1-latest <registry>/pingdirectory:11.0.0.1
docker push <registry>/pingdirectory:11.0.0.1
```

---

## 4. AKS Deployment

### 4.1 Create ACR and attach to AKS

```bash
# Create Azure Container Registry
az acr create --resource-group rg-mxform --name mxformacr --sku Basic

# Attach ACR to AKS (enables pull without imagePullSecrets)
az aks update --resource-group rg-mxform --name aks-mxform --attach-acr mxformacr
```

### 4.2 Push images to ACR

```bash
# Plugin init container
az acr build -r mxformacr \
  -t mxform-plugin:0.1.0 \
  -f deployments/platform/k8s/docker/Dockerfile.plugin .

# PingAM
docker tag pingam:8.0.2 mxformacr.azurecr.io/pingam:8.0.2
az acr login -n mxformacr
docker push mxformacr.azurecr.io/pingam:8.0.2
```

### 4.3 Update values overlay

Edit `k8s/values-aks.yaml` — replace `<acr-name>` with your ACR login server:

```yaml
initContainers:
  mxform-plugin:
    image: mxformacr.azurecr.io/mxform-plugin:0.1.0
```

### 4.4 Update PingAM deployment

Edit `k8s/pingam-deployment.yaml` for AKS:

```yaml
# Change image reference:
image: mxformacr.azurecr.io/pingam:8.0.2
imagePullPolicy: Always    # was: Never

# Change StorageClass in the PVC:
storageClassName: managed-csi  # was: local-path
```

### 4.5 Deploy

```bash
az aks get-credentials --resource-group rg-mxform --name aks-mxform

# Create namespace and deploy secrets/ConfigMaps (same as local)
kubectl create namespace message-xform
# ... (see ops guide for secret and ConfigMap creation commands)

# Deploy Helm chart
helm install platform pingidentity/ping-devops \
  -n message-xform \
  -f k8s/values-local.yaml \
  -f k8s/values-aks.yaml

# Deploy PingAM
kubectl apply -f k8s/pingam-deployment.yaml -n message-xform
```

> **AKS StorageClass note:** `managed-csi` uses Azure Managed Disks with CSI
> driver (default on AKS 1.26+). For higher IOPS, use `managed-csi-premium`.

---

## 5. GKE Deployment

### 5.1 Create Artifact Registry repository

```bash
gcloud artifacts repositories create mxform \
  --repository-format=docker \
  --location=us-central1 \
  --description="message-xform container images"
```

### 5.2 Push images to Artifact Registry

```bash
# Configure Docker for Artifact Registry
gcloud auth configure-docker us-central1-docker.pkg.dev

# Plugin init container
docker build -t us-central1-docker.pkg.dev/my-project/mxform/mxform-plugin:0.1.0 \
  -f deployments/platform/k8s/docker/Dockerfile.plugin .
docker push us-central1-docker.pkg.dev/my-project/mxform/mxform-plugin:0.1.0

# OR use Cloud Build (no local Docker needed):
gcloud builds submit \
  --tag us-central1-docker.pkg.dev/my-project/mxform/mxform-plugin:0.1.0 \
  -f deployments/platform/k8s/docker/Dockerfile.plugin .

# PingAM
docker tag pingam:8.0.2 us-central1-docker.pkg.dev/my-project/mxform/pingam:8.0.2
docker push us-central1-docker.pkg.dev/my-project/mxform/pingam:8.0.2
```

### 5.3 Update values overlay

Edit `k8s/values-gke.yaml` — replace the placeholder image references:

```yaml
initContainers:
  mxform-plugin:
    image: us-central1-docker.pkg.dev/my-project/mxform/mxform-plugin:0.1.0
```

### 5.4 Deploy

```bash
gcloud container clusters get-credentials gke-mxform --zone us-central1-a

helm install platform pingidentity/ping-devops \
  -n message-xform --create-namespace \
  -f k8s/values-local.yaml \
  -f k8s/values-gke.yaml

kubectl apply -f k8s/pingam-deployment.yaml -n message-xform
```

> **GKE StorageClass note:** `standard-rwo` (pd-balanced) is the default on
> GKE 1.26+. For latency-sensitive LDAP workloads, `premium-rwo` (pd-ssd)
> provides up to 100K IOPS.

---

## 6. EKS Deployment

### 6.1 Create ECR repositories

```bash
aws ecr create-repository --repository-name mxform-plugin --region eu-west-1
aws ecr create-repository --repository-name pingam --region eu-west-1
```

### 6.2 Push images to ECR

```bash
# Authenticate Docker to ECR
aws ecr get-login-password --region eu-west-1 | \
  docker login --username AWS \
  --password-stdin 123456789012.dkr.ecr.eu-west-1.amazonaws.com

# Plugin init container
docker build -t 123456789012.dkr.ecr.eu-west-1.amazonaws.com/mxform-plugin:0.1.0 \
  -f deployments/platform/k8s/docker/Dockerfile.plugin .
docker push 123456789012.dkr.ecr.eu-west-1.amazonaws.com/mxform-plugin:0.1.0

# PingAM
docker tag pingam:8.0.2 123456789012.dkr.ecr.eu-west-1.amazonaws.com/pingam:8.0.2
docker push 123456789012.dkr.ecr.eu-west-1.amazonaws.com/pingam:8.0.2
```

### 6.3 Install EBS CSI driver

```bash
# EKS does NOT install the EBS CSI driver by default (1.23+).
# Without it, PVC provisioning will hang indefinitely.

# Option A: AWS CLI
aws eks create-addon \
  --cluster-name eks-mxform \
  --addon-name aws-ebs-csi-driver \
  --service-account-role-arn arn:aws:iam::123456789012:role/EBS_CSI_DriverRole

# Option B: eksctl
eksctl create addon \
  --name aws-ebs-csi-driver \
  --cluster eks-mxform \
  --force
```

> **Critical EKS gotcha:** The `gp3` StorageClass requires the EBS CSI driver
> addon. If PVCs stay in `Pending` state, this is almost certainly the cause.

### 6.4 Create gp3 StorageClass (if not present)

```bash
# Check if gp3 StorageClass exists
kubectl get sc gp3

# If not, create it:
cat <<EOF | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: gp3
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  fsType: ext4
reclaimPolicy: Delete
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
EOF
```

### 6.5 Deploy

```bash
aws eks update-kubeconfig --name eks-mxform --region eu-west-1

helm install platform pingidentity/ping-devops \
  -n message-xform --create-namespace \
  -f k8s/values-local.yaml \
  -f k8s/values-eks.yaml

kubectl apply -f k8s/pingam-deployment.yaml -n message-xform
```

---

## 7. Ingress Configuration

The local k3s deployment uses Traefik CRDs (`IngressRoute`). Cloud deployments
should use the standard Kubernetes `Ingress` resource with the cloud-native
ingress controller.

### Cloud Ingress manifest

Create `k8s/ingress-cloud.yaml` (or adapt `k8s/ingress.yaml`):

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: platform-ingress
  namespace: message-xform
  annotations:
    # --- AKS (AGIC) ---
    # kubernetes.io/ingress.class: azure/application-gateway
    #
    # --- GKE (GCE) ---
    # kubernetes.io/ingress.class: gce
    #
    # --- EKS (ALB) ---
    # kubernetes.io/ingress.class: alb
    # alb.ingress.kubernetes.io/scheme: internet-facing
    # alb.ingress.kubernetes.io/target-type: ip
    #
    # --- Common (nginx-ingress — works on all providers) ---
    # kubernetes.io/ingress.class: nginx
    # nginx.ingress.kubernetes.io/backend-protocol: HTTPS
    # nginx.ingress.kubernetes.io/ssl-passthrough: "false"
spec:
  ingressClassName: nginx    # Adjust per provider
  tls:
    - hosts:
        - mxform.example.com
      secretName: mxform-tls  # Created by cert-manager (see §8)
  rules:
    - host: mxform.example.com
      http:
        paths:
          - path: /am
            pathType: Prefix
            backend:
              service:
                name: pingaccess-engine
                port:
                  number: 3000
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: pingaccess-engine
                port:
                  number: 3000
```

### Provider-specific Ingress notes

| Provider | Controller | Annotation | Notes |
|----------|-----------|------------|-------|
| **AKS** | AGIC (Application Gateway) | `kubernetes.io/ingress.class: azure/application-gateway` | Built-in if AGIC addon is enabled. L7 load balancer. |
| **AKS** | nginx-ingress | `kubernetes.io/ingress.class: nginx` | Install via `helm install ingress-nginx ingress-nginx/ingress-nginx`. More flexible than AGIC. |
| **GKE** | GCE Ingress | `kubernetes.io/ingress.class: gce` | Default GKE ingress controller. HTTP(S) Load Balancer. |
| **EKS** | ALB Ingress | `kubernetes.io/ingress.class: alb` | Requires AWS Load Balancer Controller addon. |
| **Any** | nginx-ingress | `kubernetes.io/ingress.class: nginx` | Works on all providers — recommended for consistency. |

> **Recommendation:** Use `nginx-ingress` across all providers for consistent
> behavior. The cloud-native ingress controllers have provider-specific quirks
> (e.g., GCE requires health check paths, ALB requires target-type annotations).

### PA Virtual Host configuration

PingAccess must have a Virtual Host matching the Ingress hostname and port:

| Environment | PA Virtual Host | Why |
|-------------|----------------|-----|
| Local k3s | `*:443` | Traefik connects to PA on port 3000 with Host header implying port 443 |
| Cloud (nginx) | `*:443` | nginx-ingress re-encrypts to PA; Host header port depends on controller config |
| Cloud (host-specific) | `mxform.example.com:443` | If you want to restrict by hostname |

---

## 8. cert-manager & Production TLS

### 8.1 Install cert-manager

```bash
# Works on all providers
helm repo add jetstack https://charts.jetstack.io
helm install cert-manager jetstack/cert-manager \
  -n cert-manager --create-namespace \
  --set crds.enabled=true
```

### 8.2 Create a ClusterIssuer

#### Let's Encrypt (recommended for public endpoints)

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: admin@example.com
    privateKeySecretRef:
      name: letsencrypt-prod-key
    solvers:
      # HTTP-01 solver — requires Ingress to be publicly reachable
      - http01:
          ingress:
            ingressClassName: nginx
```

#### Cloud-native CA issuers

| Provider | Issuer | Notes |
|----------|--------|-------|
| **AKS** | [Azure Key Vault Issuer](https://cert-manager.io/docs/configuration/vault/) | Uses Azure Key Vault as a CA |
| **GKE** | [Google Cloud CAS Issuer](https://github.com/jetstack/google-cas-issuer) | Uses Google Cloud Certificate Authority Service |
| **EKS** | [AWS Private CA Issuer](https://github.com/cert-manager/aws-privateca-issuer) | Uses AWS Private Certificate Authority |

### 8.3 Request certificates

Add a `Certificate` resource or annotate the Ingress:

```yaml
# Option A: Annotate the Ingress (auto-creates Certificate)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: platform-ingress
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  tls:
    - hosts:
        - mxform.example.com
      secretName: mxform-tls    # cert-manager creates this Secret

# Option B: Explicit Certificate resource
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: mxform-tls
  namespace: message-xform
spec:
  secretName: mxform-tls
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  dnsNames:
    - mxform.example.com
```

### 8.4 Verify certificate

```bash
kubectl get certificate mxform-tls -n message-xform
# STATUS should be True

kubectl describe certificate mxform-tls -n message-xform
# Check: Ready condition, expiry date, last renewal
```

---

## 9. PingAM on Cloud

The standalone PingAM Deployment (`k8s/pingam-deployment.yaml`) needs these
changes for cloud deployments:

| Setting | Local (k3s) | Cloud |
|---------|-------------|-------|
| `image` | `docker.io/library/pingam:8.0.2` | `<registry>/pingam:8.0.2` |
| `imagePullPolicy` | `Never` | `Always` |
| `storageClassName` (PVC) | `local-path` | Provider-specific (see §4–6) |

### Template for cloud PingAM PVC

```yaml
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: managed-csi    # AKS / standard-rwo (GKE) / gp3 (EKS)
  resources:
    requests:
      storage: 1Gi
```

---

## 10. Verification

After deployment on any cloud provider, run these checks:

```bash
# 1. All pods Running
kubectl get pods -n message-xform

# 2. PD health (LDAPS)
kubectl exec pingdirectory-0 -n message-xform -- ldapsearch \
  -p 1636 --useSSL --trustAll \
  -D "cn=administrator" -w "Password1" \
  -b "cn=config" "(objectclass=ds-cfg-root-dn)" dn

# 3. AM health
kubectl exec deploy/pingam -n message-xform -c pingam -- \
  curl -sf http://localhost:8080/am/ -o /dev/null -w "%{http_code}"

# 4. PA Admin API
kubectl exec pingaccess-admin-0 -n message-xform -- \
  curl -sk https://localhost:9000/pa-admin-api/v3/version \
  -u administrator:2Access \
  -H "X-XSRF-Header: PingAccess"

# 5. End-to-end via Ingress
curl -sk https://mxform.example.com/api/v1/auth/login \
  -X POST -H "Content-Type: application/json" -d '{}'
# Expected: transformed fields[] response

# 6. E2E tests (from outside the cluster)
cd deployments/platform/e2e
# Update karate-config.js with the Ingress URL, then:
./run-e2e.sh --env k8s
```
