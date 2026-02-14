# PingAccess Operations Guide

> Practitioner's guide for running, configuring, and debugging PingAccess
> with the message-xform adapter.

| Field        | Value                                                   |
|--------------|---------------------------------------------------------|
| Status       | Living document                                         |
| Last updated | 2026-02-14                                              |
| Audience     | Developers, operators, CI/CD pipeline authors           |
| See also     | [`pingaccess-deployment.md`](pingaccess-deployment.md) (architecture), [`pingaccess-sdk-guide.md`](../reference/pingaccess-sdk-guide.md) (SDK reference) |

---

## Table of Contents

1. [Docker Image Quick Reference](#1-docker-image-quick-reference)
2. [Starting PingAccess](#2-starting-pingaccess)
3. [Authentication & Password Hooks](#3-authentication--password-hooks)
4. [Admin REST API — General Usage](#4-admin-rest-api--general-usage)
5. [Admin API — Full Configuration Recipe](#5-admin-api--full-configuration-recipe)
6. [Virtual Host Matching (Critical)](#6-virtual-host-matching-critical)
7. [Application & Resource Configuration Gotchas](#7-application--resource-configuration-gotchas)
8. [Transform Rule — API vs Site Policy Buckets](#8-transform-rule--api-vs-site-policy-buckets)
9. [Transform Direction Behaviour](#9-transform-direction-behaviour)
10. [Debugging 403 Forbidden Errors](#10-debugging-403-forbidden-errors)
11. [Log Files Reference](#11-log-files-reference)
12. [Java Version Compatibility](#12-java-version-compatibility)
13. [Shadow JAR Deployment](#13-shadow-jar-deployment)
14. [E2E Test Infrastructure](#14-e2e-test-infrastructure)
15. [Common Pitfalls & Solutions](#15-common-pitfalls--solutions)
16. [Admin API Endpoint Reference](#16-admin-api-endpoint-reference)
17. [Vendor Documentation](#17-vendor-documentation)

---

## 1. Docker Image Quick Reference

**Image:** `pingidentity/pingaccess:latest` (9.0.1 as of Feb 2026)

| Property       | Value                           |
|----------------|---------------------------------|
| Base OS        | Alpine 3.23.3                   |
| Java           | Amazon Corretto **17** (`al17`) |
| Image size     | ~370 MB                         |
| Engine port    | `3000`                          |
| Admin API port | `9000`                          |
| HTTPS port     | `1443`                          |

> **No Java 21 images exist.** The official Docker Hub tags (`latest`, `edge`,
> sprint tags) are all Java 17 based. Adapter JARs **must** be compiled with
> `--release 17`. See [§12](#12-java-version-compatibility).

### Container Filesystem

```
/opt/
├── server/
│   ├── lib/                          ← PA runtime JARs (do NOT modify)
│   │   ├── pingaccess-sdk-9.0.1.0.jar
│   │   ├── pingaccess-engine-9.0.1.0.jar
│   │   └── (many third-party JARs)
│   └── deploy/                       ← Custom plugin JARs go HERE ★
│
├── staging/hooks/                    ← Lifecycle hook scripts
│   └── 83-change-password.sh        ← Password change hook
│
├── out/instance/
│   ├── bin/run.sh                    ← Entrypoint
│   ├── conf/                         ← License + config
│   │   └── pingaccess.lic           ← License file mount point
│   └── log/                          ← Log files (see §11)
│
└── java/                             ← JDK 17 installation
```

### Docker Hub Tagging Strategy

| Tag       | Description                                   | Stability      |
|-----------|-----------------------------------------------|----------------|
| `latest`  | Most recent stable release (slides monthly)   | Semi-stable    |
| `edge`    | Semi-weekly updates                           | Unstable       |
| `2206-*`  | Sprint-specific tag (e.g., `2206-11.1.0`)     | Pinned         |
| `@sha256` | Digest-pinned (recommended for production)    | Immutable      |

> **Retention:** Ping Identity retains images for **one year**. Mirror to a
> private registry for longer-term needs.

---

## 2. Starting PingAccess

### Minimal Docker Run

```bash
docker run -d --name pingaccess \
    -e PING_IDENTITY_ACCEPT_EULA=YES \
    -e PA_ADMIN_PASSWORD_INITIAL=2Access \
    -e PING_IDENTITY_PASSWORD=2Access \
    -v ./PingAccess.lic:/opt/out/instance/conf/pingaccess.lic:ro \
    -v ./my-plugin.jar:/opt/server/deploy/my-plugin.jar:ro \
    -p 9000:9000 \
    -p 3000:3000 \
    pingidentity/pingaccess:latest
```

### Key Environment Variables

| Variable                      | Default       | Description                                          |
|-------------------------------|---------------|------------------------------------------------------|
| `PING_IDENTITY_ACCEPT_EULA`   | `NO`          | **Must be `YES`** or PA won't start                  |
| `PA_ADMIN_PASSWORD_INITIAL`   | `2Access`     | Password used for first-boot authentication          |
| `PING_IDENTITY_PASSWORD`      | `2FederateM0re` | Password PA changes to after first boot (see §3)  |
| `OPERATIONAL_MODE`            | `STANDALONE`  | `STANDALONE` or `CLUSTERED`                          |
| `MAX_HEAP_SIZE`               | `384m`        | JVM maximum heap size                                |
| `JAVA_RAM_PERCENTAGE`         | `60.0`        | Alternative memory sizing (percentage of container)  |

### Readiness Detection

PA writes `"PingAccess running"` to stdout/container logs when fully started.
Wait for this before hitting the Admin API:

```bash
wait_for_pa() {
    local max_wait=120 elapsed=0
    while [[ $elapsed -lt $max_wait ]]; do
        if docker logs pingaccess 2>&1 | grep -q "PingAccess running"; then
            sleep 5  # allow post-start hooks to complete
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    echo "ERROR: PA did not start within ${max_wait}s"
    return 1
}
```

> **Why `"PingAccess running"` and not `"PingAccess is up"`?** Both appear in
> the logs, but `"PingAccess running"` is more reliable as a readiness signal
> because it appears after all hooks have executed.

---

## 3. Authentication & Password Hooks

PingAccess has a **two-phase password system** that catches everyone the first time:

```
Phase 1: Container boot
   PA starts with PA_ADMIN_PASSWORD_INITIAL (default: "2Access")

Phase 2: Hook script (83-change-password.sh)
   Authenticates with PA_ADMIN_PASSWORD_INITIAL
   Changes password to PING_IDENTITY_PASSWORD (default: "2FederateM0re")
   Accepts the EULA via API
```

### The Gotcha

If you set `PA_ADMIN_PASSWORD_INITIAL=MyPassword` but forget to also set
`PING_IDENTITY_PASSWORD=MyPassword`, the hook script will **change your password
to `2FederateM0re`** after boot. Your API calls using `MyPassword` will then
get `401 Unauthorized`.

### Recommended: Set Both Variables to the Same Value

```bash
docker run -d \
    -e PA_ADMIN_PASSWORD_INITIAL=2Access \
    -e PING_IDENTITY_PASSWORD=2Access \    # ← MUST match
    ...
```

### The Admin User

- Username: `administrator` (case-sensitive, lowercase)
- User ID: `1`
- The hook script also sets `slaAccepted: true` and `firstLogin: false`

---

## 4. Admin REST API — General Usage

### Base URL

```
https://localhost:9000/pa-admin-api/v3
```

### Required Headers

| Header            | Value              | Notes                                |
|-------------------|--------------------|--------------------------------------|
| `X-XSRF-Header`  | `PingAccess`       | **Required on every request** or 403 |
| `Content-Type`    | `application/json` | For POST/PUT requests                |
| Authorization     | Basic auth          | `administrator:<password>`           |

### Example: List Applications

```bash
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     https://localhost:9000/pa-admin-api/v3/applications
```

### API Versioning

- The current API version is **v3** (PingAccess 9.0)
- All endpoints are under `/pa-admin-api/v3/`
- Earlier PA versions may use `/pa-admin-api/v1/` or `/v2/`

### Swagger/OpenAPI Docs

PA serves its own API docs at:

```
https://localhost:9000/pa-admin-api/v3/api-docs
```

This is a live JSON OpenAPI spec. You can import it into Swagger UI, Postman,
or any other API tool for a full interactive reference.

---

## 5. Admin API — Full Configuration Recipe

This is the complete, tested sequence for configuring PA to route traffic
through the message-xform adapter. Each step builds on the previous one.

### Step 1: Verify PA is Ready

```bash
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     https://localhost:9000/pa-admin-api/v3/version
# → {"version":"9.0.1.0"}
```

### Step 2: Use the Default Virtual Host

PA ships with a default virtual host `localhost:3000` (id=1).
**Do not create a duplicate** — it will fail with a conflict error.

```bash
# Find the default VH
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     https://localhost:9000/pa-admin-api/v3/virtualhosts
# → items[0]: {"id":1, "host":"localhost", "port":3000, ...}
```

If you need a different host/port, create one:

```bash
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     -H 'Content-Type: application/json' \
     -X POST https://localhost:9000/pa-admin-api/v3/virtualhosts \
     -d '{"host":"api.example.com","port":443,"agentResourceCacheTTL":0,"keyPairId":0,"trustedCertificateGroupId":0}'
```

### Step 3: Create a Site (Backend Target)

```bash
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     -H 'Content-Type: application/json' \
     -X POST https://localhost:9000/pa-admin-api/v3/sites \
     -d '{
         "name": "My Backend",
         "targets": ["backend-host:8080"],
         "secure": false,
         "maxConnections": 10,
         "sendPaCookie": false,
         "availabilityProfileId": 1,
         "trustedCertificateGroupId": 0
     }'
# → {"id": 1, ...}
```

**Notes:**
- `targets` uses `host:port` format (no protocol prefix)
- `secure: false` for HTTP backends, `true` for HTTPS
- `availabilityProfileId: 1` is the default availability profile
- In Docker networks, use the container name as the host (e.g., `my-backend:8080`)

### Step 4: Create the Transform Rule

```bash
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     -H 'Content-Type: application/json' \
     -X POST https://localhost:9000/pa-admin-api/v3/rules \
     -d '{
         "className": "io.messagexform.pingaccess.MessageTransformRule",
         "name": "My Transform Rule",
         "supportedDestinations": ["Site"],
         "configuration": {
             "specsDir": "/specs",
             "profilesDir": "",
             "activeProfile": "",
             "errorMode": "PASS_THROUGH",
             "reloadIntervalSec": "0",
             "schemaValidation": "LENIENT",
             "enableJmxMetrics": "true"
         }
     }'
# → {"id": 1, ...}
```

**Notes:**
- `supportedDestinations` must be `["Site"]` — our rule is Site-mode only
- Configuration values are **strings**, even for booleans and integers
- If `specsDir` doesn't exist in the container, `configure()` throws `ValidationException`
- PA logs will show `Loaded spec: <filename>` for each discovered spec

### Step 5: Create the Application

```bash
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     -H 'Content-Type: application/json' \
     -X POST https://localhost:9000/pa-admin-api/v3/applications \
     -d '{
         "name": "My API",
         "contextRoot": "/api",
         "defaultAuthType": "API",
         "applicationType": "API",
         "spaSupportEnabled": false,
         "destination": "Site",
         "siteId": 1,
         "virtualHostIds": [1]
     }'
# → {"id": 1, "enabled": false, ...}  ← NOTE: enabled=false!
```

> ⚠️ **CRITICAL:** PA creates applications as **disabled** (`enabled: false`)
> by default. The `enabled` field is **silently ignored** in the POST body.
> You MUST do a separate PUT to enable it. See [§7](#7-application--resource-configuration-gotchas).

### Step 6: Enable the Application (Required!)

```bash
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     -H 'Content-Type: application/json' \
     -X PUT https://localhost:9000/pa-admin-api/v3/applications/1 \
     -d '{
         "name": "My API",
         "contextRoot": "/api",
         "defaultAuthType": "API",
         "applicationType": "API",
         "spaSupportEnabled": false,
         "destination": "Site",
         "siteId": 1,
         "virtualHostIds": [1],
         "enabled": true
     }'
# → {"id": 1, "enabled": true, ...}  ← NOW enabled
```

> The PUT body must include **all required fields** (name, contextRoot, etc.),
> not just `enabled`. PA's PUT is a full replacement, not a patch.

### Step 7: Configure the Root Resource

When you create an Application, PA auto-creates a **Root Resource** with
`unprotected: false`. For API-type applications without a token provider,
you must mark it as `unprotected: true` and attach the rule.

```bash
# First, find the root resource ID
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     https://localhost:9000/pa-admin-api/v3/applications/1/resources
# → items[0]: {"id": 1, "rootResource": true, "unprotected": false, ...}

# Then update it
curl -sk -u 'administrator:2Access' \
     -H 'X-XSRF-Header: PingAccess' \
     -H 'Content-Type: application/json' \
     -X PUT https://localhost:9000/pa-admin-api/v3/applications/1/resources/1 \
     -d '{
         "name": "Root Resource",
         "methods": ["*"],
         "pathPatterns": [{"type": "WILDCARD", "pattern": "/*"}],
         "unprotected": true,
         "enabled": true,
         "auditLevel": "ON",
         "rootResource": true,
         "policy": {
             "API": [
                 {"type": "Rule", "id": 1}
             ]
         }
     }'
```

**Key field: `policy`**

The `policy` object has different keys depending on the Application Type:

| App Type   | Policy key for rules |
|------------|---------------------|
| `Web`      | `"Web"`             |
| `API`      | `"API"`             |
| `Web+API`  | Both                |

See [§8](#8-transform-rule--api-vs-site-policy-buckets) for details.

---

## 6. Virtual Host Matching (Critical)

This section explains the #1 cause of unexplained 403 errors.

### How PA Routes Incoming Requests

```
Incoming request → Extract Host header → Match to Virtual Host → Route to Application
```

PA matches requests to applications **by the `Host` header value**, specifically
the `host:port` pair. If no virtual host matches, PA returns a **403 Forbidden**
with the "Unknown Resource Proxy" designation in the audit log.

### The Port-Mapping Problem

When running PA in Docker with port mapping:

```bash
docker run -p 13000:3000 pingidentity/pingaccess:latest
```

The virtual host inside PA is configured for `localhost:3000`, but the client
sends its request to `localhost:13000`. Curl automatically sets `Host: localhost:13000`.

```
Client  →  Host: localhost:13000  →  PA sees 13000  →  No VH match  →  403!
```

### Solution: Set the Host Header Explicitly

```bash
curl -H "Host: localhost:3000" https://localhost:13000/api/test
```

Or, if your host port matches the PA engine port (no port mapping):

```bash
docker run -p 3000:3000 ...
curl https://localhost:3000/api/test    # Host header matches naturally
```

### In the E2E Test Script

The `engine_request()` function sets `Host: localhost:3000` explicitly:

```bash
engine_request() {
    local args=(-sk -X "$method"
                -H "Host: localhost:3000"   # ← Must match PA virtual host
                -H "Content-Type: application/json"
                ...)
}
```

### How to Diagnose

Check the engine audit log (see §11):

```
Unknown Resource Proxy| | | 172.18.0.1| POST| /api/test| 403
```

If you see **"Unknown Resource Proxy"**, it means PA couldn't match the request
to any virtual host. The fix is always the Host header.

---

## 7. Application & Resource Configuration Gotchas

### Gotcha 1: Applications Are Created Disabled

PA's POST `/applications` endpoint **always** creates apps with `enabled: false`,
regardless of what you send in the request body. The `enabled` field is silently
ignored on create.

**Fix:** Follow the POST with a PUT that includes `"enabled": true`.

### Gotcha 2: Root Resource Defaults to Protected

When you create an Application, PA auto-creates a Root Resource with:
- `unprotected: false`
- `anonymous: false`
- `policy: {"Web": [], "API": []}` (no rules)

For unprotected API endpoints, you must PUT the Root Resource with
`"unprotected": true` and attach your rules to the correct policy bucket.

### Gotcha 3: PUT Is Full Replacement

PA's PUT endpoints are **full replacements**, not patches. If you PUT an
application with just `{"enabled": true}`, you'll lose the `name`,
`contextRoot`, `siteId`, etc. Always include all required fields.

### Gotcha 4: Virtual Host Conflicts

You cannot create a virtual host with the same `host:port` as an existing one.
PA ships with `localhost:3000` (id=1) by default. Query first, create only if
needed.

### Gotcha 5: Application Type Determines Auth Behaviour

| `applicationType` | `defaultAuthType` | Result when no token provider |
|--------------------|-------------------|-------------------------------|
| `"Web"`            | `"Web"`           | Requires WebSession → 403 without one |
| `"API"`            | `"API"`           | "Unprotected" if no auth server configured |
| `"Web+API"`        | Both              | API portion unprotected, Web requires session |

For the E2E test, use `"API"` with no access validator (`accessValidatorId: 0`
is the default) to get anonymous unprotected access.

---

## 8. Transform Rule — API vs Site Policy Buckets

PA Resources have a `policy` object with multiple "buckets":

```json
{
    "policy": {
        "Web": [],
        "API": [],
        "Site": []     // only at Application level
    }
}
```

Our MessageTransformRule is a **Site-mode** rule (interceptor). However, the
policy bucket it must be placed in depends on the **Application Type**:

| Application Type | Rule goes in Resource's... | Why |
|-----------------|---------------------------|-----|
| `"API"`         | `"API"` bucket             | API apps route through the API policy chain |
| `"Web"`         | `"Web"` bucket             | Web apps route through the Web policy chain |
| `"Web+API"`     | Both (if needed)           | Mixed-mode routes through both chains |

> ⚠️ This is confusing because our rule's `supportedDestinations` is `["Site"]`,
> but it goes in the `"API"` or `"Web"` policy bucket at the **resource** level.
> The `"Site"` designation is about where the rule intercepts in the pipeline,
> not about the policy bucket name.

At the **Application** level, there is also a `policy` field with a `"Site"` key.
This is for application-wide rules. For our E2E setup, we place rules at the
resource level for finer control.

---

## 9. Transform Direction Behaviour

The adapter applies transforms on both the **request** and **response** paths.
What expression runs depends on the spec type:

### Unidirectional Specs (Most Common)

```yaml
# spec.yaml
transform:
  lang: jslt
  expr: |
    { "userId": .user_id, "displayName": .first_name + " " + .last_name }
```

For unidirectional specs, `TransformEngine.resolveExpression()` returns the
**same expression** for both `REQUEST` and `RESPONSE` directions:

```java
// TransformEngine.java
private CompiledExpression resolveExpression(TransformSpec spec, Direction direction) {
    if (spec.isBidirectional()) {
        return direction == Direction.RESPONSE ? spec.forward() : spec.reverse();
    }
    return spec.compiledExpr(); // ← same for both directions
}
```

This means:
1. **Request path**: The JSLT runs on the client's request body
2. **Response path**: The JSLT runs on the backend's response body

If the backend returns a different JSON structure, the same JSLT will produce
different (potentially nonsensical) output. This is by design for transforms
that should be symmetrical. For asymmetric use cases, use bidirectional specs.

### Bidirectional Specs

```yaml
# spec.yaml with forward + reverse
forward:
  lang: jslt
  expr: |
    { "userId": .user_id }  # request transform

reverse:
  lang: jslt
  expr: |
    { "user_id": .userId }  # response transform
```

For bidirectional specs:
- `Direction.REQUEST` → `reverse` expression (outbound to backend)
- `Direction.RESPONSE` → `forward` expression (inbound from backend)

> The naming convention (`forward` = towards client, `reverse` = towards backend)
> may seem inverted but follows the spec's perspective of data flow.

### Impact on E2E Testing

Because unidirectional specs apply the same JSLT to both request and response,
the E2E test cannot simply check the echo backend's response body — it will
also be transformed. The test verifies:

1. HTTP 200 status (transform + routing worked end-to-end)
2. Response body is valid JSON (transform executed without error)
3. PA audit log shows the rule was applied to the correct app/resource

---

## 10. Debugging 403 Forbidden Errors

403 is the most common error when setting up PA. Here's a diagnostic flowchart:

```
403 Forbidden
     │
     ├─ Check engine audit log (§11)
     │     │
     │     ├─ "Unknown Resource Proxy"
     │     │     → Host header doesn't match any virtual host
     │     │     → Fix: Set Host header to match VH (§6)
     │     │
     │     ├─ No matching Application name
     │     │     → App exists but is disabled
     │     │     → Fix: Enable the app via PUT (§7, Gotcha 1)
     │     │
     │     ├─ Application name shown, but still 403
     │     │     → Resource is protected (unprotected=false)
     │     │     → Fix: Update root resource (§5, Step 7)
     │     │
     │     └─ "Access denied" with auth details
     │           → Auth server configured but no valid token
     │           → Fix: Remove access validator or provide token
     │
     └─ Check admin API logs
           → 401: Wrong password (check §3)
           → 403: Missing X-XSRF-Header
```

### Quick Diagnostic Commands

```bash
# Check engine audit log for request routing details
docker exec pingaccess cat /opt/out/instance/log/pingaccess_engine_audit.log | tail -5

# Check application state
curl -sk -u 'administrator:2Access' -H 'X-XSRF-Header: PingAccess' \
    https://localhost:9000/pa-admin-api/v3/applications | \
    python3 -c "import sys,json; [print(f'id={a[\"id\"]} name={a[\"name\"]} enabled={a[\"enabled\"]}') for a in json.load(sys.stdin).get('items',[])]"

# Check resource state
curl -sk -u 'administrator:2Access' -H 'X-XSRF-Header: PingAccess' \
    https://localhost:9000/pa-admin-api/v3/applications/1/resources | \
    python3 -c "import sys,json; [print(f'id={r[\"id\"]} unprotected={r[\"unprotected\"]} policy={r[\"policy\"]}') for r in json.load(sys.stdin).get('items',[])]"

# Check virtual hosts
curl -sk -u 'administrator:2Access' -H 'X-XSRF-Header: PingAccess' \
    https://localhost:9000/pa-admin-api/v3/virtualhosts | \
    python3 -c "import sys,json; [print(f'id={v[\"id\"]} host={v[\"host\"]}:{v[\"port\"]}') for v in json.load(sys.stdin).get('items',[])]"
```

---

## 11. Log Files Reference

All logs are in `/opt/out/instance/log/` inside the container.

| Log File                                | Contents                                                |
|-----------------------------------------|---------------------------------------------------------|
| `pingaccess.log`                        | Main PA log: startup, config, plugin lifecycle, errors  |
| `pingaccess_engine_audit.log`           | **Per-request audit**: app match, resource, HTTP status  |
| `pingaccess_api_audit.log`              | Admin API request audit                                 |
| `pingaccess_agent_audit.log`            | Agent request audit (not used in Site mode)              |
| `pingaccess_sideband_audit.log`         | Sideband client audit                                   |
| `pingaccess_sideband_client_audit.log`  | Sideband client connection audit                        |

### Engine Audit Log Format

```
timestamp| requestId| | totalMs| backendMs| routing| | | clientIP| method| path| status| | | appName| resourceName| pattern
```

Example entries:

```
# Successful request through our app
2026-02-14T11:09:32| hgN8| | 40 ms| 0 ms| localhost [] /api /*:3000| | | 172.18.0.1| POST| /api/test| 200| | | E2E Test App| Root Resource| /*

# Failed: Unknown Resource (Host header mismatch)
2026-02-14T11:05:21| vXEA| | 9 ms| 0 ms| Unknown Resource Proxy| | | 172.18.0.1| POST| /api/test| 403
```

### Plugin-Specific Log Lines

Look for `io.messagexform.pingaccess` in `pingaccess.log`:

```
# Spec loading
INFO io.messagexform.pingaccess.MessageTransformRule - Loaded spec: e2e-rename.yaml

# JMX registration
INFO io.messagexform.pingaccess.MessageTransformRule - JMX MBean registered: io.messagexform:type=TransformMetrics,instance=E2E Rule

# Configuration summary
INFO io.messagexform.pingaccess.MessageTransformRule - MessageTransformRule configured: specsDir=/specs, specs=4, profile=
```

### Quick Log Inspection

```bash
# All PA logs
docker logs pingaccess 2>&1

# Plugin-specific lines only
docker logs pingaccess 2>&1 | grep -i "messagexform\|MessageTransform"

# Engine audit (per-request)
docker exec pingaccess cat /opt/out/instance/log/pingaccess_engine_audit.log

# Errors only
docker logs pingaccess 2>&1 | grep -i "error\|exception\|fail"
```

---

## 12. Java Version Compatibility

### The Problem

The PingAccess Docker image uses **Java 17** (class file version 61). If the
adapter JAR is compiled for Java 21 (class file version 65), PA fails at
startup with:

```
UnsupportedClassVersionError: io/messagexform/pingaccess/MessageTransformRule
has been compiled by a more recent version of the Java Runtime (class file
version 65.0), this version of the Java Runtime only recognizes class file
versions up to 61.0
```

### The Solution

The project's `build.gradle.kts` sets `--release 17` for all modules except
`adapter-standalone` (which targets Java 21):

```kotlin
// Root build.gradle.kts
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)   // ← All modules target Java 17
    }
}

// adapter-standalone/build.gradle.kts (exception)
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)       // ← Standalone targets Java 21
}
```

### Verification

```bash
# Check class file version in the shadow JAR
javap -verbose -cp adapter-pingaccess/build/libs/*.jar \
    io.messagexform.pingaccess.MessageTransformRule | grep "major version"
# → major version: 61   (Java 17)
```

### Java 21 API Restrictions

The `core` and `adapter-pingaccess` modules must **not** use Java 21+ APIs:
- No `Thread.ofVirtual()`, `StructuredTaskScope`, `ScopedValue`
- No `SequencedCollection` (e.g., `List.getFirst()`)
- No `sealed` interfaces (Java 17 preview only, fully supported in 17+)

The `adapter-standalone` module **can** use Java 21 APIs.

---

## 13. Shadow JAR Deployment

The adapter is distributed as a **shadow (fat) JAR** that bundles all dependencies
except those provided by PingAccess at runtime.

### Build

```bash
./gradlew :adapter-pingaccess:shadowJar
# → adapter-pingaccess/build/libs/adapter-pingaccess-0.1.0-SNAPSHOT.jar
```

### Size Budget

| Metric    | Limit  | Rationale                              |
|-----------|--------|----------------------------------------|
| JAR size  | < 5 MB | NFR-002-02: Keep deployment lightweight |

Current size: ~4.6 MB.

### What's Excluded from the Shadow JAR

The shadow JAR excludes PA-provided classes to avoid classpath conflicts:

- `com.pingidentity.pa.sdk.*` — PA SDK (provided by PA at runtime)
- `com.fasterxml.jackson.*` — Jackson (provided by PA)
- `org.slf4j.*` — SLF4J (provided by PA)
- `jakarta.*` — Jakarta APIs (provided by PA)

These exclusions are managed via the `pa-provided.versions.toml` catalog in
`adapter-pingaccess/build.gradle.kts`.

### Deployment Path

Mount the shadow JAR to `/opt/server/deploy/` in the PA container:

```bash
-v ./my-adapter.jar:/opt/server/deploy/message-xform-adapter.jar:ro
```

PA automatically discovers JARs in this directory on startup.

### SPI Registration

The shadow JAR includes the SPI service file:

```
META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor
```

Contents: `io.messagexform.pingaccess.MessageTransformRule`

This is what tells PA to discover and register our plugin.

---

## 14. E2E Test Infrastructure

### Script: `scripts/pa-e2e-test.sh`

The E2E test script validates the full plugin lifecycle against a real PA instance.

```
┌─────────┐     ┌─────────────────┐     ┌──────────────┐
│  Test    │────►│  PingAccess     │────►│  Echo Backend │
│  Script  │     │  (port 13000)   │     │  (port 8080)  │
│          │◄────│  Engine         │◄────│  Python HTTP   │
└─────────┘     │                 │     └──────────────┘
                │  Admin API      │
                │  (port 19000)   │
                └─────────────────┘
```

### Echo Backend

A minimal Python HTTP server that echoes back any request:

```python
import http.server, json

class Handler(http.server.BaseHTTPRequestHandler):
    def handle(self):
        body = self.rfile.read(content_length)
        response = json.dumps({
            "echo": {
                "method": self.command,
                "path": self.path,
                "headers": dict(self.headers),
                "body": json.loads(body)
            }
        })
        self.send_response(200)
        self.wfile.write(response.encode())
```

### Docker Network

The test uses a dedicated Docker network (`pa-e2e-net`) so PA can reach the
echo backend by container name:

```bash
docker network create pa-e2e-net
docker run --network pa-e2e-net --name pa-e2e-echo ...
docker run --network pa-e2e-net --name pa-e2e-test ...
# → PA site target: "pa-e2e-echo:8080"
```

### Port Mapping

| Host Port | Container Port | Service              |
|-----------|----------------|----------------------|
| `19000`   | `9000`         | PA Admin API         |
| `13000`   | `3000`         | PA Engine            |
| `18080`   | `8080`         | Echo backend (direct)|

### Test Specs

Located in `e2e/pingaccess/specs/`:

| File                       | Purpose                                |
|----------------------------|----------------------------------------|
| `e2e-rename.yaml`          | JSLT rename: snake_case → camelCase    |
| `e2e-header-inject.yaml`   | Header injection via transform spec    |

### Usage

```bash
./scripts/pa-e2e-test.sh              # full (build + test)
./scripts/pa-e2e-test.sh --skip-build # skip Gradle build
```

---

## 15. Common Pitfalls & Solutions

### Pitfall: "403 Forbidden" on engine requests

| Cause | Symptom in audit log | Fix |
|-------|---------------------|-----|
| Host header mismatch | `Unknown Resource Proxy` | Add `-H "Host: localhost:3000"` (§6) |
| App disabled | Request not logged at all | PUT to enable the app (§7) |
| Resource protected | App name in log, still 403 | PUT root resource with `unprotected: true` (§5) |

### Pitfall: "401 Unauthorized" on admin API

| Cause | Fix |
|-------|-----|
| Password was changed by hook | Set `PING_IDENTITY_PASSWORD` to match `PA_ADMIN_PASSWORD_INITIAL` (§3) |
| Missing `X-XSRF-Header` | Add `-H 'X-XSRF-Header: PingAccess'` |
| Wrong username case | Use `administrator` (lowercase) |

### Pitfall: `UnsupportedClassVersionError` at startup

| Cause | Fix |
|-------|-----|
| JAR compiled for Java 21 | Set `options.release.set(17)` in `build.gradle.kts` (§12) |
| Using Java 21 APIs in core/adapter | Remove `List.getFirst()`, `Thread.ofVirtual()`, etc. |

### Pitfall: Virtual host creation conflict

| Cause | Fix |
|-------|-----|
| `localhost:3000` already exists (id=1) | Query `/virtualhosts` first; use existing VH |

### Pitfall: Rule not executing on requests

| Cause | Fix |
|-------|-----|
| Rule in wrong policy bucket | Use `"API"` bucket for API apps, `"Web"` for Web apps (§8) |
| Rule not attached to resource | PUT the resource with the rule in its `policy` |
| `specsDir` doesn't exist in container | Verify volume mount: `-v ./specs:/specs:ro` |

### Pitfall: Transform produces garbage on response

| Cause | Fix |
|-------|-----|
| Unidirectional spec runs same JSLT on response | Expected behaviour (§9); use bidirectional spec if needed |

---

## 16. Admin API Endpoint Reference

Quick reference for the endpoints used in adapter configuration. All paths
are relative to `https://<host>:9000/pa-admin-api/v3`.

### Version & Health

| Method | Path       | Description           |
|--------|------------|-----------------------|
| GET    | `/version` | PA version and build  |

### Virtual Hosts

| Method | Path               | Description               |
|--------|--------------------|---------------------------|
| GET    | `/virtualhosts`    | List virtual hosts        |
| POST   | `/virtualhosts`    | Create virtual host       |
| GET    | `/virtualhosts/{id}` | Get virtual host        |
| PUT    | `/virtualhosts/{id}` | Update virtual host     |
| DELETE | `/virtualhosts/{id}` | Delete virtual host     |

### Sites (Backend Targets)

| Method | Path          | Description     |
|--------|---------------|-----------------|
| GET    | `/sites`      | List sites      |
| POST   | `/sites`      | Create site     |
| GET    | `/sites/{id}` | Get site        |
| PUT    | `/sites/{id}` | Update site     |
| DELETE | `/sites/{id}` | Delete site     |

### Rules (Plugins)

| Method | Path                          | Description                          |
|--------|-------------------------------|--------------------------------------|
| GET    | `/rules`                      | List rules                           |
| POST   | `/rules`                      | Create rule                          |
| GET    | `/rules/{id}`                 | Get rule                             |
| PUT    | `/rules/{id}`                 | Update rule                          |
| DELETE | `/rules/{id}`                 | Delete rule                          |
| GET    | `/rules/descriptors`          | List all rule type descriptors       |
| GET    | `/rules/descriptors/{type}`   | Get descriptor for a specific type   |

### Applications

| Method | Path                 | Description              |
|--------|----------------------|--------------------------|
| GET    | `/applications`      | List applications        |
| POST   | `/applications`      | Create application       |
| GET    | `/applications/{id}` | Get application          |
| PUT    | `/applications/{id}` | Update (⚠️ full replace) |
| DELETE | `/applications/{id}` | Delete application       |

### Resources (Per-Application)

| Method | Path                                    | Description              |
|--------|-----------------------------------------|--------------------------|
| GET    | `/applications/{appId}/resources`       | List resources           |
| POST   | `/applications/{appId}/resources`       | Create resource          |
| GET    | `/applications/{appId}/resources/{id}`  | Get resource             |
| PUT    | `/applications/{appId}/resources/{id}`  | Update (⚠️ full replace) |
| DELETE | `/applications/{appId}/resources/{id}`  | Delete resource          |

### OpenAPI Spec

| Method | Path         | Description                        |
|--------|--------------|------------------------------------|
| GET    | `/api-docs`  | Full OpenAPI JSON specification    |

---

## 17. Vendor Documentation

### Files in This Repository

| File | Description | Size |
|------|-------------|------|
| `docs/reference/vendor/pingaccess-9.0.pdf` | Official PA 9.0 documentation | PDF |
| `docs/reference/vendor/pingaccess-9.0.txt` | Same content, plain text (searchable) | ~49,500 lines |
| `docs/reference/vendor/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` | SDK JAR | 131 KB |
| `docs/reference/pingaccess-sdk-guide.md` | Our SDK guide (~2000 lines) | Markdown |
| `docs/research/pingaccess-docker-and-sdk.md` | Docker image research notes | Markdown |
| `docs/research/pingaccess-plugin-api.md` | Plugin API research notes | Markdown |
| `docs/operations/pingaccess-deployment.md` | Deployment architecture guide | Markdown |

### Key Sections in the Vendor Documentation (`pingaccess-9.0.txt`)

These line numbers are stable references for the plain-text version:

| Topic | Approx. lines | Description |
|-------|--------------|-------------|
| Application field descriptions | 18000–18200 | Application Type, Context Root, Destination, etc. |
| Resource configuration | 18600–18700 | Anonymous vs Unprotected, policy configuration |
| Unknown Resources | 26420–26490 | How PA handles requests with no matching app |
| Adding an application (use case) | 17963–18000 | Step-by-step UI walkthrough |
| Adding an API application | 1960–2010 | API-specific use case guide |

### Live Admin API Docs

PA serves its own OpenAPI spec at runtime:

```
https://localhost:9000/pa-admin-api/v3/api-docs
```

This is the most authoritative and complete reference for the Admin API,
including all request/response schemas.

---

## Changelog

| Date       | Change                                           |
|------------|--------------------------------------------------|
| 2026-02-14 | Initial version — consolidated from E2E debugging sessions |
