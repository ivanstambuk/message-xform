# Kubernetes Deployment Operations Guide

> Operational knowledge for the Kubernetes-based platform deployment.
> Covers k3s local bootstrap, `ping-devops` Helm chart patterns,
> volume mount gotchas, and Ping container lifecycle hooks.
>
> **Last updated:** 2026-02-17 — Phases 1–3 (PD + PA via Helm, AM standalone)

---

## Table of Contents

| # | Section | Topics |
|---|---------|--------|
| 1 | [k3s Local Setup](#1-k3s-local-setup) | Installation, kubeconfig, Traefik ports |
| 2 | [Helm & ping-devops Chart](#2-helm--ping-devops-chart) | Chart structure, product-level overrides |
| 3 | [Volume Mount Patterns](#3-volume-mount-patterns) | The chart's three volume mechanisms |
| 4 | [Ping Container Lifecycle Hooks](#4-ping-container-lifecycle-hooks) | Hook ordering, staging vs output paths |
| 5 | [Init Container Pattern](#5-init-container-pattern) | Shadow JAR injection via busybox + emptyDir |
| 5a | [PingAM Standalone Deployment](#5a-pingam-standalone-deployment) | Image import, PD cert trust, standalone manifest |
| 6 | [License Mounting](#6-license-mounting) | PD and PA license Secrets |
| 7 | [ConfigMap Mounting](#7-configmap-mounting) | Transform specs and profiles |
| 8 | [K8s Secrets](#8-k8s-secrets) | Credentials, licenses |
| 9 | [Debugging & Troubleshooting](#9-debugging--troubleshooting) | Container failures, log inspection |
| 10 | [Command Reference](#10-command-reference) | One-liners for common operations |

---

## 1. k3s Local Setup

### Installation

```bash
# Install k3s (runs as a systemd service)
curl -sfL https://get.k3s.io | sh -

# Verify
k3s --version    # v1.34.4+k3s1
```

### kubeconfig

k3s writes its kubeconfig to `/etc/rancher/k3s/k3s.yaml`. Add to `.bashrc`:

```bash
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
alias k=kubectl
```

> **Gotcha:** The kubeconfig is owned by root. If you get permission errors,
> either run as root or copy the file:
> ```bash
> sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
> sudo chown $(id -u):$(id -g) ~/.kube/config
> ```

### Traefik Ingress (built-in)

k3s installs Traefik as the default ingress controller. It binds on **ports 80 and 443**.

Port conflict check:
```bash
ss -tlnp | grep -E ':80 |:443 '
```

If Tailscale or another service is on 443, check if it's bound to a specific IP
(e.g., `100.x.x.x:443`) — k3s Traefik binds to `0.0.0.0:443`, so they can coexist
as long as the specific-IP service bound first.

### System pods

After a clean install, expect these pods in `kube-system`:

| Pod | Status |
|-----|--------|
| `coredns-*` | Running |
| `local-path-provisioner-*` | Running |
| `metrics-server-*` | Running |
| `svclb-traefik-*` | Running |
| `traefik-*` | Running |
| `helm-install-traefik-*` | Completed |

---

## 2. Helm & ping-devops Chart

### Installation

```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm repo add pingidentity https://helm.pingidentity.com/
helm repo update
```

### Chart structure

The `ping-devops` chart (v0.11.17) is a **unified chart** covering all Ping Identity products.
Each product is a top-level key that defaults to `enabled: false`:

```yaml
# Products available in ping-devops chart
pingdirectory:       # PingDirectory (LDAP)
  enabled: false
pingaccess-admin:    # PingAccess Admin node (StatefulSet)
  enabled: false
pingaccess-engine:   # PingAccess Engine node (Deployment)
  enabled: false
pingfederate-admin:  # PingFederate Admin
  enabled: false
pingfederate-engine: # PingFederate Engine
  enabled: false
pingauthorize:       # PingAuthorize
  enabled: false
# ... and several more
```

> **Critical finding: PingAM is NOT in this chart.**
> PingAM (formerly ForgeRock Access Manager) is a ForgeRock-lineage product,
> not a native Ping Identity product. The chart's `externalImage` block is
> only for `pingtoolkit` and `pingaccess` helper init containers. PingAM must
> be deployed as a standalone K8s Deployment manifest.

### addReleaseNameToResource

By default, the chart **prepends** the Helm release name to all resource names
(e.g., `platform-pingaccess-admin`). Set to `none` for clean names:

```yaml
global:
  addReleaseNameToResource: none   # Resources named: pingaccess-admin, pingdirectory
```

### Product-level overrides

Each product supports overrides that **merge with** global values:

```yaml
pingaccess-admin:
  enabled: true
  container:
    resources: { ... }
    envs: { ... }
  services: { ... }
  workload:
    type: StatefulSet
```

---

## 3. Volume Mount Patterns

The chart has **three distinct mechanisms** for mounting volumes into the main container.
This is the single most confusing aspect of the chart and the source of most deployment failures.

### Mechanism 1: Product-level `volumes` + `volumeMounts` (RECOMMENDED)

Defined at the **product root level** (NOT under `container:`):

```yaml
pingaccess-admin:
  volumes:                    # ← Product root level
    - name: my-config
      configMap:
        name: my-configmap
  volumeMounts:               # ← Product root level
    - name: my-config
      mountPath: /config
      readOnly: true
```

The chart's `_workload.tpl` template checks `$v.volumeMounts` (where `$v` is the
product values) and merges these directly into the main container spec.

> **Trap:** Putting `volumeMounts` under `container:` does NOTHING.
> The chart ignores `container.volumeMounts` entirely:
> ```yaml
> # ❌ WRONG — chart ignores this
> pingaccess-admin:
>   container:
>     volumeMounts:
>       - name: my-config
>         mountPath: /config
>
> # ✅ CORRECT — chart uses this
> pingaccess-admin:
>   volumeMounts:
>     - name: my-config
>       mountPath: /config
> ```

### Mechanism 2: Top-level `volumes` + `includeVolumes` (for shared volumes)

For volumes shared across multiple products (e.g., emptyDir for init containers):

```yaml
# Top-level definitions
volumes:
  mxform-deploy:
    emptyDir: {}

# Product references them
pingaccess-admin:
  includeVolumes:
    - mxform-deploy
```

This adds the volume to the pod spec but does **NOT** auto-create a volumeMount
for the main container. You must also add a `volumeMount` at the product level.

### Mechanism 3: `secretVolumes` and `configMapVolumes` (convenience)

```yaml
pingaccess-admin:
  secretVolumes:
    my-secret:
      items:
        key-file: /opt/conf/secret.key
  configMapVolumes:
    my-config:
      items:
        config-file: /opt/conf/config.yaml
```

These auto-generate both volumes AND volumeMounts. The chart creates subPath mounts.

### Summary table

| Mechanism | Volume | VolumeMount | Use case |
|-----------|--------|-------------|----------|
| Product `volumes` + `volumeMounts` | ✅ you define | ✅ you define | Full control, any volume type |
| Top-level `volumes` + `includeVolumes` | ✅ shared def | ❌ manual | Shared with init containers |
| `secretVolumes` / `configMapVolumes` | ✅ auto | ✅ auto (subPath) | Simple key→path mapping |

---

## 4. Ping Container Lifecycle Hooks

Ping Identity Docker images use a **hook-based startup sequence** that copies files
from staging directories to output directories. Understanding this is critical for
placing license files and plugins.

### Directory layout inside the container

```
/opt/server/          # Read-only product installation (image layer)
/opt/staging/         # Staging area (writable, from container overlay)
  ├── hooks/          # Startup hook scripts (01-* through 90-*)
  ├── deploy/         # Plugin JARs to be deployed
  └── instance/
      └── conf/
          └── pingaccess.lic   # License file (staging location)
/opt/out/             # Runtime output (PVC-mounted for StatefulSets)
  └── instance/       # Running instance (hooks copy here from /opt/server)
      ├── conf/       # Configuration files
      ├── deploy/     # Deployed plugin JARs
      └── ...
```

### Hook execution order

| Hook | Name | What it does |
|------|------|-------------|
| `01` | start-server | Entrypoint |
| `02` | get-remote-server-profile | Pulls remote profiles (unused by us) |
| `03` | build-run-plan | Determines first-start vs restart |
| `04` | check-variables | Validates env vars |
| `06` | **copy-product-bits** | `cp -R /opt/server/* /opt/out/instance/` |
| `17` | **check-license** | Looks for license at `LICENSE_DIR` |
| `80` | post-start | Runs after start |
| `81` | after-start-process | Post-start hooks |
| `83` | **change-password** | Changes PA admin password |

### The staging → output copy trap

**Hook 06 copies** from `/opt/server/` (or `/opt/staging/`) to `/opt/out/instance/`.
If you mount a read-only volume (like a Secret subPath) at a path **within**
`/opt/out/instance/`, the copy operation fails with "Permission denied" because
the mount makes that path read-only.

> **Rule:** Mount secrets and configs at **`/opt/staging/`** paths, NOT at
> `/opt/out/instance/` paths. The hooks will copy them from staging to output.

```yaml
# ❌ WRONG — blocks hook 06 from writing to /opt/out/instance/conf/
volumeMounts:
  - name: pa-license
    mountPath: /opt/out/instance/conf/pingaccess.lic   # Blocks hook writes!
    subPath: pingaccess.lic
    readOnly: true

# ✅ CORRECT — staging path, hooks copy to output
volumeMounts:
  - name: pa-license
    mountPath: /opt/staging/instance/conf/pingaccess.lic
    subPath: pingaccess.lic
    readOnly: true
```

### LICENSE_DIR

The hooks check for license files at:

| Product | License file expected at |
|---------|------------------------|
| PingAccess | `/opt/out/instance/conf/pingaccess.lic` (but mount at `/opt/staging/instance/conf/`) |
| PingDirectory | `/opt/staging/pd.profile/server-root/pre-setup/PingDirectory.lic` |

PD checks the staging path directly (no copy hook for the PD profile).
PA checks the output path (populated by the staging → output copy).

---

## 5. Init Container Pattern

### Shadow JAR injection

The message-xform PingAccess adapter JAR is injected using an init container +
emptyDir pattern:

```
[hostPath: build/libs/] → [init: busybox cp] → [emptyDir] → [PA container: /opt/staging/deploy/]
```

#### How it works

1. **`mxform-jar-source`** volume: hostPath pointing at the Gradle build output directory
2. **`mxform-deploy`** volume: emptyDir shared between init container and main container
3. **Init container** (`busybox:1.36`): copies the JAR from source to emptyDir
4. **Main container** mounts emptyDir at `/opt/staging/deploy/`
5. **Hook 06** copies `/opt/staging/deploy/*` to `/opt/out/instance/deploy/`

#### Why `/opt/staging/deploy` and NOT `/opt/server/deploy`

Mounting an emptyDir at `/opt/server/deploy` **overlays** the image's directory,
hiding the original contents. This causes the hook 06 copy to fail or produce
unexpected results. The staging path is the correct injection point.

#### Init container definition (from values-local.yaml)

```yaml
initContainers:
  mxform-plugin:
    name: mxform-plugin-init
    image: busybox:1.36
    command:
      - sh
      - -c
      - |
        echo "Copying message-xform plugin JAR..."
        cp /source/adapter-pingaccess-0.1.0-SNAPSHOT.jar /target/message-xform-adapter.jar
        ls -la /target/
        echo "Done."
    volumeMounts:
      - name: mxform-jar-source
        mountPath: /source
        readOnly: true
      - name: mxform-deploy
        mountPath: /target
```

#### Referencing in products

```yaml
pingaccess-admin:
  includeInitContainers:
    - mxform-plugin     # References top-level `initContainers.mxform-plugin`
  includeVolumes:
    - mxform-jar-source # hostPath
    - mxform-deploy     # emptyDir
  volumeMounts:
    - name: mxform-deploy
      mountPath: /opt/staging/deploy
```

---

## 5a. PingAM Standalone Deployment

PingAM is NOT part of the `ping-devops` Helm chart. It's deployed as a standalone
K8s Deployment + Service + PVC.

### Image import into k3s

The PingAM Docker image is built locally and must be imported into k3s's containerd:

```bash
# Build the image (run once, or after WAR update)
cd binaries/pingam && docker build . -f docker/Dockerfile -t pingam:8.0.2

# Import into k3s containerd
docker save pingam:8.0.2 | sudo k3s ctr images import -

# Verify
sudo k3s ctr images list | grep pingam
```

> **Important:** The Deployment uses `imagePullPolicy: Never` to tell k3s to
> use the locally imported image without attempting a registry pull.

### PD cert trust (init container)

PingAM needs to connect to PingDirectory via LDAPS. The init container:
1. Retrieves PD's TLS certificate via `openssl s_client`
2. Copies the JVM's `cacerts` to a shared emptyDir (`/trust/cacerts`)
3. Imports the PD cert into the copy via `keytool -importcert`
4. The main container uses `-Djavax.net.ssl.trustStore=/trust/cacerts` in `CATALINA_OPTS`

This replaces the Docker Compose approach of `docker exec -u 0 keytool ...`.

### Required K8s resources

Before deploying, create these resources:

```bash
# ConfigMap: Tomcat server.xml
kubectl create configmap am-server-xml \
  --from-file=server.xml=config/server.xml \
  -n message-xform

# Secret: TLS keystore + public cert
kubectl create secret generic am-tls \
  --from-file=tlskey.p12=secrets/tlskey.p12 \
  --from-file=pubCert.crt=secrets/pubCert.crt \
  -n message-xform

# Secret: SSL password
kubectl create secret generic am-ssl-password \
  --from-literal=SSL_PWD=<your-ssl-password> \
  -n message-xform
```

### Deploy

```bash
kubectl apply -f k8s/pingam-deployment.yaml -n message-xform
```

### First-time setup

After deployment, AM responds with HTTP 302 (redirect to configurator).
The configuration Job (Step 3.4) posts to the AM configurator API to
complete initial setup. Until then, AM is alive but unconfigured.

### Verify

```bash
# Check HTTP status (302 = unconfigured, 200 = configured)
kubectl exec <pingam-pod> -n message-xform -c pingam -- \
  curl -sf -o /dev/null -w "%{http_code}" http://localhost:8080/am/

# Check init container cert import
kubectl logs <pingam-pod> -n message-xform -c trust-pd-cert
```

## 6. License Mounting

Ping products require development license files. We mount them from K8s Secrets.

### PingDirectory

PD checks `pd.profile/server-root/pre-setup/PingDirectory.lic` at startup.
This is a staging-time path (not affected by the copy hook):

```bash
# Create the Secret
kubectl create secret generic pd-license \
  --from-file=PingDirectory.lic=/path/to/PingDirectory-11.0.0.0-Development.lic \
  -n message-xform
```

```yaml
# values-local.yaml
pingdirectory:
  volumes:
    - name: pd-license
      secret:
        secretName: pd-license
  volumeMounts:
    - name: pd-license
      mountPath: /opt/staging/pd.profile/server-root/pre-setup/PingDirectory.lic
      subPath: PingDirectory.lic
      readOnly: true
```

### PingAccess

PA checks `/opt/out/instance/conf/pingaccess.lic` after hook 06 copies files.
Mount at the **staging** path so hook 06 copies it to the output path:

```bash
kubectl create secret generic pa-license \
  --from-file=pingaccess.lic=/path/to/PingAccess-9.0-Development.lic \
  -n message-xform
```

```yaml
pingaccess-admin:
  volumes:
    - name: pa-license
      secret:
        secretName: pa-license
  volumeMounts:
    - name: pa-license
      mountPath: /opt/staging/instance/conf/pingaccess.lic
      subPath: pingaccess.lic
      readOnly: true
```

> **Both PA Admin AND PA Engine need the license.** The engine runs independently
> and has its own hook chain. Don't forget to add the license to both.

---

## 7. ConfigMap Mounting

Transform specs and profiles are mounted as ConfigMaps. This enables hot-reload:
kubelet syncs ConfigMaps every ~60s, and the message-xform engine polls every 30s.

### Creation

```bash
# From the specs directory (creates one key per file)
kubectl create configmap mxform-specs \
  --from-file=deployments/platform/specs/ \
  -n message-xform

# From the profiles directory
kubectl create configmap mxform-profiles \
  --from-file=deployments/platform/profiles/ \
  -n message-xform
```

### Update (after editing specs)

```bash
kubectl create configmap mxform-specs \
  --from-file=deployments/platform/specs/ \
  -n message-xform \
  --dry-run=client -o yaml | kubectl apply -f -
```

### Values file mounting

```yaml
pingaccess-admin:
  volumes:
    - name: mxform-specs
      configMap:
        name: mxform-specs
    - name: mxform-profiles
      configMap:
        name: mxform-profiles
  volumeMounts:
    - name: mxform-specs
      mountPath: /specs
      readOnly: true
    - name: mxform-profiles
      mountPath: /profiles
      readOnly: true
```

---

## 8. K8s Secrets

### Credentials

```bash
# PingDirectory root user
kubectl create secret generic pd-credentials \
  --from-literal=root-user-password=2FederateM0re \
  -n message-xform

# PingAccess admin
kubectl create secret generic pa-credentials \
  --from-literal=admin-password=2Access \
  -n message-xform

# PingAM admin + encryption key
kubectl create secret generic am-credentials \
  --from-literal=admin-password=Password1 \
  --from-literal=enc-key=$(openssl rand -base64 24) \
  -n message-xform
```

### PA admin password env vars

PingAccess containers need **two** environment variables for the password change hook:

```yaml
container:
  envs:
    PA_ADMIN_PASSWORD_INITIAL: "2Access"   # Initial password (hooks check this)
    PING_IDENTITY_PASSWORD: "2Access"      # Same value (hooks also check this)
```

> **Gotcha:** If either is missing, hook 83 (`change-password.sh`) fails with:
> `ERROR: No valid administrator password found`

---

## 9. Debugging & Troubleshooting

### Check pod status

```bash
kubectl get pods -n message-xform
kubectl describe pod <pod-name> -n message-xform
```

### Check init container logs

```bash
# Init containers run before the main container
kubectl logs <pod-name> -n message-xform -c mxform-plugin-init
kubectl logs <pod-name> -n message-xform -c wait-for-pingaccess-admin
```

### Check main container logs

```bash
kubectl logs <pod-name> -n message-xform --tail=50
```

### Common failure patterns

| Symptom | Cause | Fix |
|---------|-------|-----|
| `CONTAINER FAILURE: License File absent` | License not mounted or wrong path | Mount at staging path (§6) |
| `cp: can't create ... Permission denied` in hook 06 | Secret/ConfigMap subPath mount blocks directory writes | Mount at `/opt/staging/` not `/opt/out/instance/` (§4) |
| `ERROR: No valid administrator password found` | Missing `PA_ADMIN_PASSWORD_INITIAL` or `PING_IDENTITY_PASSWORD` | Set both env vars to same value (§8) |
| Init container `cp: can't create`: "No such file or directory" | Wrong mount path or subPath creates file instead of directory | Use emptyDir without subPath for the target (§5) |
| `volumeMounts: null` in rendered template | `volumeMounts` placed under `container:` instead of product root | Move to product root level (§3) |

### Helm template debugging

```bash
# Render templates without deploying (inspect the YAML)
helm template platform pingidentity/ping-devops \
  -n message-xform \
  -f k8s/values-local.yaml > /tmp/rendered.yaml

# Check a specific resource
grep -A 40 'name: pingaccess-admin$' /tmp/rendered.yaml | head -50
```

### Verify deployed volumes

```bash
# Check what's mounted inside a running pod
kubectl exec <pod> -n message-xform -- ls -la /opt/staging/deploy/
kubectl exec <pod> -n message-xform -- ls /specs/
kubectl exec <pod> -n message-xform -- ls /profiles/
```

---

## 10. Command Reference

### Cluster management

```bash
# Start/stop k3s
sudo systemctl start k3s
sudo systemctl stop k3s

# Check cluster health
kubectl get nodes
kubectl get pods -A
```

### Helm operations

```bash
# Install
helm install platform pingidentity/ping-devops \
  -n message-xform \
  -f k8s/values-local.yaml

# Upgrade (after values change)
helm upgrade platform pingidentity/ping-devops \
  -n message-xform \
  -f k8s/values-local.yaml

# Uninstall (clean slate)
helm uninstall platform -n message-xform
kubectl delete pvc --all -n message-xform    # Delete persistent volumes too

# Dry run (validate without deploying)
helm install platform pingidentity/ping-devops \
  -n message-xform \
  -f k8s/values-local.yaml \
  --dry-run
```

### ConfigMap refresh

```bash
# Recreate specs ConfigMap after editing specs/*.yaml
kubectl create configmap mxform-specs \
  --from-file=specs/ \
  -n message-xform \
  --dry-run=client -o yaml | kubectl apply -f -
```

### Secret management

```bash
# List secrets
kubectl get secrets -n message-xform

# Decode a secret value
kubectl get secret pd-credentials -n message-xform -o jsonpath='{.data.root-user-password}' | base64 -d

# Delete and recreate (for rotation)
kubectl delete secret pa-credentials -n message-xform
kubectl create secret generic pa-credentials --from-literal=admin-password=NewPassword -n message-xform
```

### Health checks

```bash
# PA Admin heartbeat
kubectl exec pingaccess-admin-0 -n message-xform -- \
  curl -sk https://localhost:9000/pa/heartbeat.ping

# PA Engine heartbeat
kubectl exec <engine-pod> -n message-xform -- \
  curl -sk https://localhost:3000/pa/heartbeat.ping

# PD LDAPS
kubectl exec pingdirectory-0 -n message-xform -- \
  /opt/out/instance/bin/ldapsearch -h localhost -p 1636 -Z -X \
  -b "" -s base "(objectclass=*)" namingContexts

# AM health (302 = unconfigured, 200 = configured)
kubectl exec <pingam-pod> -n message-xform -c pingam -- \
  curl -sf -o /dev/null -w "%{http_code}" http://localhost:8080/am/
```

### Log inspection

```bash
# Last 50 lines from main container
kubectl logs <pod> -n message-xform --tail=50

# Follow logs in real-time
kubectl logs <pod> -n message-xform -f

# Init container logs
kubectl logs <pod> -n message-xform -c mxform-plugin-init
```
