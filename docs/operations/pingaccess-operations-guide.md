# PingAccess Operations Guide

> Practitioner's guide for running, configuring, and debugging PingAccess
> with the message-xform adapter.

| Field        | Value                                                   |
|--------------|---------------------------------------------------------|
| Status       | Living document                                         |
| Last updated | 2026-02-14                                              |
| Audience     | Developers, operators, CI/CD pipeline authors           |
| See also     | [`pingaccess-sdk-guide.md`](../reference/pingaccess-sdk-guide.md) (SDK reference) |

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
18. [Deployment Architecture (Two-Level Matching, Request/Response Lifecycle, PA Object Model)](#18-deployment-architecture)
19. [Per-Instance Configuration](#19-per-instance-configuration)
20. [File System Layout](#20-file-system-layout)
21. [Deployment Patterns](#21-deployment-patterns)
22. [Spec Hot-Reload](#22-spec-hot-reload)
23. [Deployment FAQ](#23-deployment-faq)
24. [Rule Execution Order](#24-rule-execution-order)

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

### Bidirectional Specs (Recommended for E2E Testing)

```yaml
# spec.yaml with forward + reverse
# reverse = applied in REQUEST direction (client → backend)
reverse:
  lang: jslt
  expr: |
    { "userId": .user_id }  # snake_case → camelCase for the backend

# forward = applied in RESPONSE direction (backend → client)
forward:
  lang: jslt
  expr: |
    { "user_id": .userId }  # camelCase → snake_case for the client
```

For bidirectional specs:
- `Direction.REQUEST` → `reverse` expression (outbound to backend)
- `Direction.RESPONSE` → `forward` expression (inbound from backend)

> The naming convention (`forward` = towards client, `reverse` = towards backend)
> may seem inverted but follows the spec's perspective of data flow.

### Impact on E2E Testing

The E2E test uses a **bidirectional** spec so that both request and response
transforms can be independently verified with actual payload assertions:

1. Client sends snake_case → `reverse` JSLT → backend receives camelCase
2. Echo backend returns the body as-is
3. `forward` JSLT → client receives snake_case (round-trip)

Assertions verify:
- **Response payload fields** match the original snake_case structure (round-trip proof)
- **Direct echo probe** (bypassing PA) confirms the backend received camelCase
- **PA audit log** confirms the rule was applied to the correct app/resource

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

### Script: `scripts/pa-e2e-bootstrap.sh` + `e2e-pingaccess/` (Karate DSL)

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

A minimal Python HTTP server that returns the **raw request body as-is**
(no wrapper envelope). Request metadata is exposed via `X-Echo-*` response
headers for test inspection:

```python
import http.server, json

class EchoHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else b"{}"
        # Return raw body — no envelope wrapping
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Echo-Method", self.command)
        self.send_header("X-Echo-Path", self.path)
        # Forward request headers as X-Echo-Req-* for header injection tests
        for name, value in self.headers.items():
            self.send_header(f"X-Echo-Req-{name}", value)
        self.end_headers()
        self.wfile.write(body)
```

> **Why raw body?** The response JSLT needs to operate on the same JSON
> structure as the request JSLT output. Wrapping the body in an `{"echo":{...}}`
> envelope would cause the response JSLT to receive an incompatible structure,
> making field-level assertions impossible.

### Direct Echo Probing

The echo backend is exposed on host port `18080`. Tests can hit it directly
(bypassing PA) to verify what the backend received after the request transform:

```bash
# Verify the backend received camelCase fields
curl -s http://localhost:18080/api/transform \
    -d '{"userId":"u123","firstName":"John"}'
# → {"userId":"u123","firstName":"John"}  (raw echo, no PA)
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
| `18443`   | `8080`         | Mock OIDC server     |
| `19999`   | `9999`         | JMX RMI (Phase 10)   |

### Enabling JMX in Docker

PA's `run.sh` appends `$JVM_OPTS` to the Java command separately from
`$JAVA_OPTS` (which controls heap/GC from `jvm-memory.options`). Set
`JVM_OPTS` to inject JMX flags without overriding memory settings:

```bash
docker run -e JVM_OPTS="-Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.rmi.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Djava.rmi.server.hostname=localhost" \
  -p 19999:9999 ...
```

> **Key:** Both `jmxremote.port` and `jmxremote.rmi.port` must be the same
> to avoid RMI using a random ephemeral port, which can't be Docker-mapped.

### Test Specs

Located in `e2e/pingaccess/specs/`:

| File                       | Purpose                                |
|----------------------------|----------------------------------------|
| `e2e-rename.yaml`          | JSLT rename: snake_case → camelCase    |
| `e2e-header-inject.yaml`   | Header injection via transform spec    |

### Usage

```bash
./scripts/pa-e2e-bootstrap.sh              # full (build + test)
./scripts/pa-e2e-bootstrap.sh --skip-build # skip Gradle build
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
| Unidirectional spec runs same JSLT on response | Use a **bidirectional spec** with separate `forward`/`reverse` expressions (§9). Unidirectional specs apply the same JSLT to both directions, which only works if the input and output JSON structures are identical. |

### Pitfall: Admin API silently ignores unknown fields

| Cause | Fix |
|-------|-----|
| PA accepts unknown JSON fields without error | Always verify field names against the live API docs at `/pa-admin-api/v3/api-docs`. E.g., the ATV uses `thirdPartyService` (not `thirdPartyServiceId`). |

### Pitfall: Token Provider "Common" type requires OAS

| Cause | Fix |
|-------|-----|
| PA 9.0's `Common` token provider type requires an OAuth Authorization Server via `/tokenProvider/oauthAuthorizationServer`, which is non-functional without real PingFederate | Keep the default `PingFederate` token provider type. Use a Third-Party Service + JWKS ATV for token validation against external OIDC providers. |

### Pitfall: Plugin LOG.info not in docker logs

| Cause | Fix |
|-------|-----|
| Plugin `LOG.info()` goes to `pingaccess.log`, not docker stdout | Use `docker exec grep -qF` inside the container instead of `docker logs`. Never capture the entire log into a shell variable — it can exceed bash argument limits. |

### Pitfall: JWKS ATV doesn't populate all OAuth fields

| Cause | Fix |
|-------|-----|
| JWKS ATV populates `subject` and `tokenType` but not `clientId`/`scopes` | These fields require introspection or a PingFederate Token Provider connection. Use best-effort assertions in E2E tests. |

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
| `docs/reference/pingaccess-sdk-guide.md` | Our SDK guide (~3000 lines) | Markdown |
| `docs/research/pingaccess-plugin-api.md` | Plugin API research notes | Markdown |

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

## 18. Deployment Architecture

The message-xform PingAccess adapter is a **Rule plugin** deployed into PingAccess.
PingAccess administrators bind rule instances to Applications (API endpoints) through
the PA admin console. The plugin itself does not control *which* requests it processes —
PingAccess decides that. The plugin only controls *what* happens to the request/response
once PA hands it the `Exchange`.

This creates a clean **two-level matching** architecture:

```
Level 1: PingAccess routing          Level 2: Engine profile matching
─────────────────────────────        ──────────────────────────────────
PA decides WHICH rule fires    →     Engine decides WHICH spec applies
(Application → Site → Rules)         (profile path/method/content-type)
```

### Level 1 — PingAccess Routing (Product-Defined)

PingAccess owns request routing. The admin binds rule instances to Applications.
Key points:
- Each Application can have **zero or more** MessageTransform rule instances.
- Each rule instance is an **independent plugin instance** with its own configuration.
- PA fires rules **sequentially in policy-manager order** for every request matching the Application's context root. See [§24](#24-rule-execution-order).
- Our plugin has **no visibility** into which Application it belongs to.

### Level 2 — Engine Profile Matching (message-xform)

Once PA fires our rule, the engine performs its own matching within the active
**transform profile** using `requestPath`, `requestMethod`, and `contentType`.
The engine uses **most-specific-wins** resolution (ADR-0006). If no profile entry
matches, the request **passes through** untouched.

### Request/Response Lifecycle

The same rule instance handles **both directions**. PA calls `handleRequest()` on
the way in and `handleResponse()` on the way back. The full lifecycle:

```
Client                 PA Engine               Our Rule (Processing)          Site (Backend)
  │                       │                        │                              │
  │── POST /api/foo ─────►│                        │                              │
  │                       │  access control rules  │                              │
  │                       │  (tier 1/2) run first  │                              │
  │                       │                        │                              │
  │                       │── handleRequest() ────►│                              │
  │                       │   (processing rule,    │                              │
  │                       │    tier 3/4)           │                              │
  │                       │                        │── wrapRequest(exchange)      │
  │                       │                        │   → reads URI, headers, body │
  │                       │                        │── engine.transform(REQUEST)  │
  │                       │                        │   → body JSLT (reverse expr) │
  │                       │                        │   → headers.add / remove     │
  │                       │                        │   → url.path.expr            │
  │                       │                        │── applyChanges(exchange)     │
  │                       │                        │   → mutates Exchange in-place │
  │                       │                        │                              │
  │                       │◄── return CONTINUE ────│                              │
  │                       │                        │                              │
  │                       │── forwards modified request to Site ─────────────────►│
  │                       │                        │                              │
  │                       │◄── backend responds ──────────────────────────────────│
  │                       │                        │                              │
  │                       │── handleResponse() ───►│                              │
  │                       │                        │── wrapResponse(exchange)     │
  │                       │                        │   → reads status, headers,   │
  │                       │                        │     response body            │
  │                       │                        │── engine.transform(RESPONSE) │
  │                       │                        │   → body JSLT (forward expr) │
  │                       │                        │   → headers.add / remove     │
  │                       │                        │   → status.set               │
  │                       │                        │── applyChanges(exchange)     │
  │                       │                        │   → mutates Exchange in-place │
  │                       │                        │                              │
  │                       │◄── return CONTINUE ────│                              │
  │                       │                        │                              │
  │◄── transformed resp ──│                        │                              │
```

### PA Object Model

| PA Object | What it is | Role in the flow |
|-----------|-----------|------------------|
| **Virtual Host** | Hostname + port binding (e.g., `api.example.com:443`) | Entry point — PA selects the VH based on the `Host` header |
| **Application** | Context root (e.g., `/api/customers`) bound to a VH | Routes requests by path prefix to a set of Resources |
| **Site** | Backend target (e.g., `backend-service:8080`) | Where PA forwards the request *after* all processing rules run |
| **Resource** | Path pattern within an Application (e.g., `/*`, `/orders/*`) | Attaches policy (rules) at sub-path granularity |
| **Rule** (ours) | `MessageTransformRule` — a processing rule | Transforms request before backend, response after backend |

**Destination constraint:** Our rule is annotated with `@RuleInterceptorDestinations(Site)`,
meaning it only works on Applications whose destination is a **Site** (backend proxy mode).
It will not activate for PA Agent destinations.

**Key observations:**

- The **same rule instance** handles both request and response. There is no
  separate "request rule" and "response rule" — PA calls both lifecycle methods
  on the same object.
- Spec direction is controlled by `forward`/`reverse` expressions (bidirectional)
  or a single `transform.expr` (unidirectional, applied in both directions).
  See [§9](#9-transform-direction-behaviour) for details.
- The rule returns `Outcome.CONTINUE` — it never blocks the request. Blocking is
  the job of access control rules (tier 1/2). If the transform fails, `errorMode`
  controls whether to pass through or deny (HTTP 500).

---

## 19. Per-Instance Configuration

Each rule instance has its **own configuration**, set through the PA admin UI or
REST API. There is no "global" configuration shared between instances.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `specsDir` | Path | `/specs` | Directory containing transform spec YAML files |
| `profilesDir` | Path | `/profiles` | Directory containing transform profile files |
| `activeProfile` | String | (empty) | Profile name to activate (empty = no profile) |
| `errorMode` | Enum | `PASS_THROUGH` | Behaviour on transform failure |
| `reloadIntervalSec` | Integer | `0` | Seconds between spec file re-reads (0 = disabled) |
| `schemaValidation` | Enum | `LENIENT` | JSON Schema validation mode |
| `enableJmxMetrics` | Boolean | `false` | Enable JMX MBean metrics |

Each instance initializes its own `TransformEngine` during the plugin lifecycle's
`configure()` step. Instances are **completely independent** — they don't share
state, specs, or profiles.

---

## 20. File System Layout

### Single-Instance Layout (Simple)

```
/opt/server/
├── deploy/
│   └── message-xform-adapter.jar        ← plugin JAR (shadow JAR)
│
├── specs/                                ← specsDir (volume mount)
│   ├── customer-transform.yaml
│   ├── payment-transform.yaml
│   └── order-transform.yaml
│
└── profiles/                             ← profilesDir (volume mount)
    └── api-transforms.yaml              ← active profile
```

### Multi-Instance Layout (Per-Team / Per-API)

```
/opt/server/
├── deploy/
│   └── message-xform-adapter.jar        ← single JAR, shared by all instances
│
├── specs/
│   ├── customer/                         ← Instance A: specsDir=/specs/customer
│   │   ├── create-customer.yaml
│   │   └── update-customer.yaml
│   └── payment/                          ← Instance B: specsDir=/specs/payment
│       ├── process-payment.yaml
│       └── refund-payment.yaml
│
└── profiles/
    ├── customer-api.yaml                ← Instance A: activeProfile=customer-api
    └── payment-api.yaml                 ← Instance B: activeProfile=payment-api
```

---

## 21. Deployment Patterns

### Key Principle: Profile = Per-Operation Router

A profile entry matches on **path + method + content-type** — the same tuple
that defines an OpenAPI operation. This means profiles already provide
per-operation routing within a single rule instance. There is no need to
create separate PA Resources or separate MessageTransformRule instances to
handle different API operations.

Example: a single profile covering an entire Customer API:

```yaml
profile: customer-api
version: "1.0.0"
description: "Routes all customer API operations"

transforms:
  # POST /api/customers → create-customer spec (request transform)
  - spec: create-customer@1.0.0
    direction: request
    match:
      path: "/api/customers"
      method: POST

  # GET /api/customers/* → get-customer spec (response transform)
  - spec: get-customer@1.0.0
    direction: response
    match:
      path: "/api/customers/*"
      method: GET

  # PUT /api/customers/*/orders → update-order spec (request transform)
  - spec: update-order@1.0.0
    direction: request
    match:
      path: "/api/customers/*/orders"
      method: PUT
```

Each spec can independently transform the body, inject/remove headers, rewrite
the URL, and override the status code — all in a single YAML file. There is no
need for multiple rules to achieve multi-dimension transforms per operation.

### When to Use Which Pattern

| Pattern | When | Why |
|---------|------|-----|
| **One rule + one profile** | Same `errorMode` and `reloadIntervalSec` across operations | Simplest config. One engine instance. Atomic hot-reload. |
| **One rule per Application** | Different teams or different `errorMode` per API | Team isolation. Per-API error handling. Independent reload. |
| **Per-Resource rules** | Different `errorMode` per operation within the same app | Rare. Only when you need DENY for mutations but PASS_THROUGH for reads *within* the same context root. |
| **Multiple MessageTransformRules on one Resource** | ❌ **Never do this** | See anti-pattern below. |

### Pattern 1: One Rule per Application + Profile Routing (Recommended)

One MessageTransform rule instance per Application. The profile handles
per-operation routing internally.

```
Application: /api/customers  →  Rule: customer-transforms
  profile: customer-api (routes POST/GET/PUT/DELETE to individual specs)

Application: /api/payments   →  Rule: payment-transforms
  profile: payment-api (routes POST/GET to individual specs)
```

**Pros:** Simple PA config. One engine instance per API. All routing controlled
by the profile. Atomic hot-reload. Team isolation at the Application level.
**Cons:** All operations within one app share the same `errorMode`.

### Pattern 2: Shared Specs, Separate Profiles

All instances point to the same `specsDir` but use different `activeProfile` values
to select which specs apply.

**Pros:** Single source of truth for specs. Profile controls routing.
**Cons:** Both instances load all specs (wasted memory for unused specs).

### Pattern 3: Per-Resource Rules (Advanced)

Split rules across PA Resources when you need **different `errorMode`** per
operation. For example, DENY for payment mutations and PASS_THROUGH for catalog
reads, both under the same context root.

```
Application: /api
  Resource: /payments/* → Rule: payment-transforms (errorMode=DENY)
  Resource: /catalog/*  → Rule: catalog-transforms (errorMode=PASS_THROUGH)
```

This is the only legitimate reason to attach multiple MessageTransformRule
instances under one Application. The cost is multiple engine instances and
independent reload cycles.

### Anti-Pattern: Multiple MessageTransformRules on One Resource

> ⚠️ **Do not chain multiple MessageTransformRule instances on the same
> Resource.** This is never necessary.

A single transform spec already supports body transformation, header injection,
URL rewriting, and status code overrides — all in one YAML file. Attaching
two MessageTransformRules to the same Resource would:

- Create **two independent engine instances**, each loading its own specs
- Run **both transforms sequentially** (§24), with Rule B seeing Rule A's
  modified request — making the interaction order-dependent and fragile
- Waste memory and CPU on duplicate engines
- Create confusion about which spec handles which aspect of the transform

If you need different transforms for different operations under the same
application, use **profile routing** (path + method matching) instead.

---

## 22. Spec Hot-Reload

When `reloadIntervalSec > 0`, the plugin periodically re-reads spec YAML files from
disk. This enables **zero-downtime spec updates** — operators can update the volume-
mounted files without restarting PingAccess.

**Failure is safe:** If a reload fails (malformed YAML, I/O error), the previous
valid registry stays active. No requests are affected.

**Note:** Hot-reload applies to spec/profile **YAML files only** — not to the plugin
JAR itself. Updating the JAR requires a PA restart.

---

## 23. Deployment FAQ

**Q: Can two rule instances process the same request?**
Yes, if the PA admin binds two MessageTransform rules to the same Application.
They execute **sequentially** in policy-manager order — each rule's `CompletionStage`
completes before the next rule starts. See [§24](#24-rule-execution-order).
However, **this is not recommended** for MessageTransformRules — use profile
routing instead (§21).

**Q: Do I need a separate rule for each API operation?**
No. A single profile handles per-operation routing using path + method matching.
One rule per Application with a profile is the recommended pattern. See §21.

**Q: Can a single spec handle body, headers, URL, and status transforms?**
Yes. A transform spec supports `transform.expr` (body), `headers.add/remove`,
`url.path.expr`, and `status.set` — all in one YAML file. There is no need for
multiple rules or specs to cover different transform dimensions.

**Q: What if no profile entry matches the request?**
The engine returns `PASSTHROUGH` — the request/response goes through **completely
untouched**.

**Q: Can I use the plugin without profiles?**
Yes. Set `activeProfile` to empty. The engine will match specs directly by their
`match` blocks. Profiles are recommended for explicit control.

**Q: Does the plugin see the original client URL or the rewritten URL?**
The **rewritten URL** — i.e., `Request.getUri()` after any upstream rule rewrites.

**Q: How do I update specs without downtime?**
Set `reloadIntervalSec` to a value like `30`. Update the YAML files on disk.
The plugin picks up changes at the next poll. No PA restart needed.

**Q: What happens during a reload if a spec is invalid?**
The reload fails gracefully — the previous valid registry stays active.

---

## 24. Rule Execution Order

> **TL;DR:** PingAccess executes rules **sequentially (async-serial)**, not in parallel.
> Each rule's `CompletionStage` completes before the next rule starts. The order
> is the policy-manager order (top-to-bottom in the UI).

### Four-Tier Execution Order

PingAccess 9.0 documentation (page 369, "Processing order") states:

> "Access control rules are applied before processing rules. For each type of
> rule, **the rules are applied in the order configured in the policy manager.**"

The full order is:
1. **Application access control rules**
2. **Resource access control rules**
3. **Resource processing rules**
4. **Application processing rules**

Within each tier, rules execute top-to-bottom as configured. The Rule Sets
UI documentation (page 412) confirms: *"Processing occurs from top to bottom."*

### Rule Categories

PingAccess rules belong to one of two categories. The `@Rule` annotation's
`category` field determines which tier a rule belongs to.

**Access control rules** are **gatekeepers** — they decide whether a request
should be allowed or denied. Their outcome is binary: allow or reject.
Examples from the PA documentation (page 369):

- Test user attributes (OAuth attribute rules)
- Check the time of day the request was made (time range rules)
- Verify source IP addresses (network range rules)
- Test OAuth access token scopes (OAuth scope rules)

If an access control rule denies a request, processing stops immediately —
no backend request is made, and no processing rules run.

**Processing rules** are **transformers** — they modify the request or response
in transit. Examples:

- Modify headers (rewrite response header rules)
- Rewrite URLs (rewrite URL rules)
- **Transform JSON bodies (our adapter)**

Our adapter is registered as a processing rule:

```java
@Rule(
    category = RuleInterceptorCategory.Processing,
    destination = {RuleInterceptorSupportedDestination.Site},
    ...
)
public class MessageTransformRule extends AsyncRuleInterceptorBase<MessageTransformConfig> { ... }
```

This means our rule always runs in **tiers 3 or 4** — after all access control
has already passed.

| Tier | Category | Purpose | Example |
|------|----------|---------|---------|
| 1 | Access control (app) | Reject unauthorized users early | "Only allow users with `admin` scope" |
| 2 | Access control (resource) | Fine-grained per-path access | "Only allow IPs in 10.0.0.0/8 for `/api/internal`" |
| 3 | **Processing (resource)** | Transform requests for specific paths | **Our adapter** — rename fields, inject headers |
| 4 | Processing (app) | Transform requests across all paths | Global header injection |

**Why access control runs first:** The PA documentation explains the performance
rationale — *"If an access control rule is more likely to reject access, it
should appear near the top of the list to reduce the amount of processing that
occurs before that rule is applied."* There is no point spending CPU on JSON
body transformation if the request is going to be rejected.

### The "Unprotected" Resource Nuance

The PA documentation (page 338) describes two modes for resources without
authentication requirements:

| Mode | Access control rules | Processing rules |
|------|---------------------|-----------------|
| **Anonymous** | ✅ Applied | ✅ Applied |
| **Unprotected** | ❌ Skipped entirely | ✅ Applied |

This is why our E2E tests work with `unprotected: true` — our processing
rule still fires even though there is no access control. Identity mappings
are only applied in Anonymous mode if the user is already authenticated.

### Flow Strategy Classes

The PA engine (`pingaccess-engine-9.0.1.0.jar`) implements two flow strategies
for rule evaluation in the `com.pingidentity.pa.interceptor.flow` package:

| Class | Success Criteria | Behaviour |
|-------|-----------------|-----------|
| `FailOnFirstFailRuleInterceptorFlowStrategy` | **All** | Every rule must succeed; first failure stops the chain |
| `AtleastOneMustPassRuleInterceptorFlowStrategy` | **Any** | First success stops the chain; all failures = denied |

A `RulesetEvaluationSuccessCriteria` enum maps the admin-configured success
criteria to these strategies. Individual rules (not in a RuleSet) always use
the **All** strategy.

### Sequential Execution Model

Both strategies iterate rules using a `java.util.Iterator` and follow an
**async-serial continuation** pattern:

1. The engine calls the first rule's `handleRequestAsync()` method
2. It **returns** immediately — no further rules are invoked yet
3. When the rule's `CompletionStage` completes, a callback evaluates the outcome
4. If `CONTINUE` → the engine **recursively** calls the iterator for the **next** rule
5. If denied (All strategy) or succeeded (Any strategy) → the chain stops

**Execution flow for rules A → B → C (All strategy):**
1. Iterator yields rule A → call `handleRequestAsync()` → return
2. A completes with `CONTINUE` → push A's response interceptor → recurse
3. Iterator yields rule B → call `handleRequestAsync()` → return
4. B completes with `CONTINUE` → push B's response interceptor → recurse
5. Iterator yields rule C → call `handleRequestAsync()` → return
6. C completes with `CONTINUE` → no more rules → return `CONTINUE`
7. If any rule returns non-`CONTINUE` → `handleDenial()` → pipeline stops

**This is an async-serial chain.** The next rule never starts until the previous
rule's `CompletionStage` has completed.

### The "Any" Strategy — Short-Circuit Behaviour

The `AtleastOneMustPassRuleInterceptorFlowStrategy` also iterates sequentially
but uses inverted termination semantics:

- **On success:** Stops immediately — remaining rules are **not** evaluated
- **On failure:** Continues to the next rule (opposite of "All")
- After all rules are evaluated, calls `evaluatePolicyDecision()` to determine
  the aggregate outcome

> ⚠️ **Implication for processing rules:** The PA documentation explicitly warns:
> *"When Success Criteria is set to Any, the first processing rule that succeeds
> causes PingAccess not to evaluate all other rules in the set."*
> This means multiple processing rules in an "Any" RuleSet will **not** all execute.

### Implications for Multi-Rule Chains

Because execution is sequential:

1. **URI rewrites from Rule A are visible to Rule B.** `Request.getUri()` reflects
   modifications from earlier rules — confirmed by scenario S-002-27.
2. **Body modifications from Rule A are visible to Rule B.** `Request.getBodyContent()`
   returns the modified body.
3. **Order is deterministic.** It follows the policy-manager order (UI drag position).
4. **Single-rule-per-RuleSet is safest** for processing rules to avoid
   short-circuit surprises with "Any" criteria.

### Why the SDK API Looks Non-Deterministic

The `AsyncRuleInterceptor` SPI returns `CompletionStage<Outcome>`, which
*suggests* parallel evaluation. However, the engine's flow strategies
**serialize** these stages — the async contract is for the rule's **internal**
async work (e.g., HTTP calls to external services), not for parallel rule
evaluation.

---

## See Also

- [Feature 002 Spec](../architecture/features/002/spec.md) — Full functional requirements
- [PingAccess SDK Guide](../reference/pingaccess-sdk-guide.md) — SDK class reference
- [ADR-0023](../decisions/ADR-0023-cross-profile-routing-is-product-defined.md) — Cross-profile routing is product-defined
- [ADR-0006](../decisions/ADR-0006-profile-match-resolution.md) — Most-specific-wins match resolution
- [ADR-0031](../decisions/ADR-0031-pa-provided-dependencies.md) — PA-provided dependencies

---

## Changelog

| Date       | Change                                           |
|------------|--------------------------------------------------|
| 2026-02-14 | Initial version — consolidated from E2E debugging sessions |
| 2026-02-14 | Merged `pingaccess-deployment.md` and `pingaccess-docker-and-sdk.md` into this single file |
| 2026-02-15 | Added §24 — Rule Execution Order analysis |
| 2026-02-15 | Expanded §21 — Profile-as-operation-router guidance, anti-pattern for multi-rule chaining, per-Resource pattern, new FAQ entries in §23 |
| 2026-02-15 | Expanded §18 — Request/Response Lifecycle diagram, PA Object Model table, destination constraint |
