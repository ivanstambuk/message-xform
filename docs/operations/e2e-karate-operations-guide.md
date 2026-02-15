# E2E Testing with Karate DSL — Architecture & Operations Guide

_Created:_ 2026-02-15
_Status:_ Active reference
_Audience:_ Future sessions implementing or modifying E2E tests for any gateway adapter.

---

## Table of Contents

1. [Overview](#1-overview)
2. [The Generic Pattern](#2-the-generic-pattern)
3. [PingAccess Reference Implementation](#3-pingaccess-reference-implementation)
4. [Docker Infrastructure](#4-docker-infrastructure)
5. [Gradle Build Integration](#5-gradle-build-integration)
6. [Karate Runner IDE Extension](#6-karate-runner-ide-extension)
7. [How a Test Executes End-to-End](#7-how-a-test-executes-end-to-end)
8. [Writing Karate Tests](#8-writing-karate-tests)
9. [Running Tests](#9-running-tests)
10. [Karate Runner Extension Patches](#10-karate-runner-extension-patches)
11. [Pitfalls & Lessons Learned](#11-pitfalls--lessons-learned)
12. [Porting to Other Gateways](#12-porting-to-other-gateways)
13. [Troubleshooting](#13-troubleshooting)
14. [Reference](#14-reference)

---

## 1. Overview

Our E2E tests exercise the message-xform adapter deployed inside a **real gateway
container** with live HTTP traffic flowing through an echo backend, optionally
with a mock OAuth2/OIDC server. The framework is **gateway-neutral** — the same
pattern applies to PingAccess, PingGateway, NGINX, Envoy, or any reverse proxy
that can load our adapter.

The test framework is **Karate DSL** — a BDD-style HTTP testing framework that
runs on JUnit 5.

### Why Karate?

- Native HTTP client with JSON/XML matching built in
- Gherkin syntax for readable test scenarios
- `callonce` for shared setup state (provision gateway once, run many tests)
- `karate.exec()` for container log inspection
- Runs on JUnit 5 → integrates with Gradle's standard `test` task
- Zero external dependencies beyond the JAR

### History

The E2E suite was migrated from a monolithic 1,701-line Bash script
(`scripts/pa-e2e-test.sh`) to Karate across 5 phases. The original migration
preserved all 26 test scenarios; subsequent phases (9–10) added 5 more, bringing
the total to **31 scenarios** across 9 feature files. The migration gained
type-safe JSON assertions, IDE test runner support, and Gradle task-graph
integration for automatic Docker lifecycle.

### Current E2E Modules

| Module | Gateway | Scenarios | Status |
|--------|---------|:---------:|--------|
| `e2e-pingaccess` | PingAccess 9.x | 31 | ✅ Active |
| `e2e-pinggateway` | PingGateway | — | 📋 Planned |
| `e2e-standalone` | Standalone Proxy | — | 📋 Planned |

---

## 2. The Generic Pattern

Every gateway E2E module follows the same blueprint. **PingAccess is the
reference implementation** — all examples in this guide use it as the concrete
case. When porting to another gateway, substitute the gateway-specific parts
while keeping the pattern unchanged.

### Blueprint

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Developer Workflow                            │
│                                                                      │
│   Click "Karate: Run" on scenario    OR    ./gradlew :e2e-…:test    │
│              │                                      │                │
│              ▼                                      ▼                │
│   Karate Runner Extension           ─────────────────────────────    │
│   generates command:                                                 │
│   ./gradlew clean test --tests ... -Dkarate.options="classpath:..." │
│              │                                                       │
│              ▼                                                       │
│   ┌─────────────────── Gradle Task Graph ──────────────────────┐    │
│   │                                                             │    │
│   │  :adapter-<gw>:shadowJar         (build the adapter)       │    │
│   │         │                                                   │    │
│   │         ▼                                                   │    │
│   │  :e2e-<gw>:dockerUp             (idempotent infra start)  │    │
│   │         │                                                   │    │
│   │         ▼                                                   │    │
│   │  :e2e-<gw>:test                 (Karate scenarios)         │    │
│   │         │                                                   │    │
│   │         ▼                                                   │    │
│   │  :e2e-<gw>:dockerDown           (marker-gated teardown)   │    │
│   └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

### Three Layers

| Layer | Responsibility | Technology |
|-------|---------------|------------|
| **IDE** | Click-to-run scenarios via CodeLens | Karate Runner extension |
| **Build** | Task graph, Docker lifecycle, adapter build | Gradle 9.x + Bash scripts |
| **Tests** | HTTP assertions, JSON matching, gateway provisioning | Karate DSL 1.4.1 |

### What's Gateway-Specific vs Generic

| Component | Generic (reuse as-is) | Gateway-specific (implement per gateway) |
|-----------|----------------------|------------------------------------------|
| Gradle task pattern | `dockerUp → test → dockerDown` | Guard condition, adapter task name |
| Infra scripts | Marker-file mechanism, echo backend, OIDC server | Gateway container config, readiness checks |
| karate-config.js | SSL, timeouts, `karate.env` | Ports, admin URL, provisioning headers |
| Feature structure | `setup/`, `helpers/`, `phase*/` layout | Provisioning steps, admin API calls |
| Echo backend | Shared across all gateways | — |
| IDE extension patches | Same patches for all modules | Runner class name in settings |
| Assertion patterns | Body > status > logs > headers | Log file paths, header behavior |

---

## 3. PingAccess Reference Implementation

The `e2e-pingaccess` module is the fully implemented reference. All detailed
examples below use PingAccess. When porting, refer to
[Section 12 (Porting)](#12-porting-to-other-gateways) for gateway substitutions.

### Docker Containers (PingAccess)

| Container | Image | Host Port | Purpose |
|-----------|-------|-----------|---------|
| `pa-e2e-test` | `pingidentity/pingaccess:latest` | 19000 (admin), 13000 (engine), 19999 (JMX) | Gateway under test |
| `pa-e2e-echo` | `python:3.12-alpine` | 18080 | Echo backend (shared across gateways) |
| `pa-e2e-oidc-backend` | `ghcr.io/navikt/mock-oauth2-server:latest` | — (internal only) | OIDC backend with native HTTPS via `SERVER_SSL_*` env vars |
| `pa-e2e-oidc` | `python:3.12-alpine` | 18443 | TLS proxy: patches OIDC metadata (`token_endpoint_auth_methods_supported`, `ping_end_session_endpoint`), sets `Host` header for correct issuer |

All containers share the `pa-e2e-net` Docker network.

### Project Layout

```
message-xform/
├── scripts/
│   ├── pa-e2e-bootstrap.sh       # Full lifecycle: build + Docker + test + teardown
│   ├── pa-e2e-infra-up.sh        # Idempotent Docker startup (used by Gradle)
│   └── pa-e2e-infra-down.sh      # Docker teardown (marker-file-gated)
│
├── e2e/pingaccess/
│   ├── specs/                     # Transform specs mounted into PA container
│   ├── profiles/                  # Profile configs mounted into PA container
│   └── staging/                   # Hot-reload staging dir for Phase 9 tests
│
├── e2e-pingaccess/                # Gradle subproject
│   ├── build.gradle.kts           # Karate deps + Docker lifecycle tasks
│   ├── gradlew                    # Symlink → ../gradlew (for Karate Runner)
│   └── src/test/java/
│       ├── karate-config.js       # Global config: ports, URLs, SSL, timeouts
│       ├── logback-test.xml       # Log suppression
│       └── e2e/
│           ├── PingAccessE2ETest.java   # JUnit 5 runner
│           ├── setup/
│           │   ├── pa-provision.feature       # PA Admin API provisioning (callonce)
│           │   ├── create-atv.feature         # Token validator setup
│           │   ├── create-session-app.feature # OAuth session app setup
│           │   ├── create-virtualhost.feature # Virtual host setup
│           │   └── create-web-session.feature # Web session setup
│           ├── helpers/
│           │   ├── basic-auth.js               # HTTP Basic header builder
│           │   ├── find-resource.feature       # Gateway resource lookup by name
│           │   ├── create-if-absent.feature    # Idempotent resource creation
│           │   ├── concurrent-request.feature  # Concurrent HTTP helper
│           │   ├── follow-redirect.feature     # Manual redirect following
│           │   ├── jmx-query.feature           # JMX MBean query via docker exec
│           │   └── oidc-login-form.feature     # OIDC login flow helper
│           ├── plugin-discovery.feature    # Plugin registration + descriptor tests
│           ├── profile-routing.feature     # Profile-based spec routing
│           ├── context-variables.feature   # Cookie, query, session context
│           ├── body-status-edge.feature    # Edge cases: empty body, status codes
│           ├── error-modes.feature         # PASS_THROUGH vs DENY error handling
│           ├── oauth-identity.feature      # Bearer token / L1-L3 identity
│           ├── web-session-oidc.feature    # OIDC web session / L4 identity
│           ├── hot-reload.feature          # Spec hot-reload tests
│           └── jmx-metrics.feature         # JMX MBean metrics verification
│
├── .vscode/settings.json          # Karate Runner extension configuration
└── .gitignore                     # Includes .e2e-infra-started marker
```

---

## 4. Docker Infrastructure

### The Pattern (All Gateways)

Every gateway E2E module uses three scripts:

| Script | Purpose | Idempotent? |
|--------|---------|:-----------:|
| `<gw>-e2e-infra-up.sh` | Start Docker containers, wait for readiness | ✅ Yes |
| `<gw>-e2e-infra-down.sh` | Tear down containers | Marker-gated |
| `<gw>-e2e-bootstrap.sh` | Full lifecycle: build + start + test + teardown | N/A |

**Marker-file mechanism:** `infra-up.sh` writes `.e2e-infra-started` when it
starts containers. `infra-down.sh` checks for this file before tearing down.
This prevents destroying manually-started infrastructure during iterative
development.

### PingAccess: `scripts/pa-e2e-infra-up.sh`

**Idempotent** — checks if PingAccess is already responding before starting anything.

```bash
# Idempotency check — skip if gateway is already responsive
curl -sfk -u "administrator:$PA_PASSWORD" \
    -H "X-XSRF-Header: PingAccess" \
    "https://localhost:19000/pa-admin-api/v3/version"
# Exit 0 if already running
```

Startup sequence:
1. Preflight checks (Docker, license file, shadow JAR)
2. Clean stale containers
3. Create `pa-e2e-net` Docker network
4. Start echo backend (Python HTTP server — shared across gateways)
5. Start mock-oauth2-server (wait up to 30s for OIDC discovery endpoint)
6. Start PingAccess with:
   - License file mounted
   - Shadow JAR mounted at `/opt/server/deploy/`
   - Specs and profiles mounted at `/specs` and `/profiles`
   - JMX remote access enabled (port 19999)
7. Wait for PA readiness (up to 120s, polling container logs for "PingAccess is up")
8. Write **marker file** `.e2e-infra-started`

### PingAccess: `scripts/pa-e2e-infra-down.sh`

```bash
# Safety: won't destroy manually-started infra
if [[ ! -f "$MARKER" ]]; then
    echo "Marker not found — skipping teardown"
    exit 0
fi
docker rm -f pa-e2e-test pa-e2e-echo pa-e2e-oidc
docker network rm pa-e2e-net
rm -f "$MARKER"
```

Use `--force` to override the marker check.

### PingAccess: `scripts/pa-e2e-bootstrap.sh`

Full lifecycle for CI or manual runs:
```bash
./scripts/pa-e2e-bootstrap.sh              # build + Docker + test + teardown
./scripts/pa-e2e-bootstrap.sh --skip-build  # skip shadow JAR build
```

### Echo Backend (Shared Across Gateways)

The echo backend is a lightweight Python HTTP server that supports three modes:

| Route | Behavior | Use Case |
|-------|----------|----------|
| `POST/GET/PUT /` | Echoes request body back as response body | Transform verification |
| `GET /html/*` | Returns static HTML body | Content-type handling tests |
| `ANY /status/{code}` | Returns the given HTTP status code | Error mode / edge case tests |

All responses include `X-Echo-*` headers with request metadata (method, path,
original request headers). These are useful for **direct echo probe** tests
but are stripped by most reverse proxies — see [P1](#p1-reverse-proxies-strip-backend-response-headers).

---

## 5. Gradle Build Integration

### The Pattern (All Gateways)

```kotlin
// Guard: only activate for explicit E2E invocations
val isE2EExplicit = gradle.startParameter.taskNames.any {
    it.contains("e2e-<gateway>") || it == "test"
}

// Start Docker infrastructure (idempotent)
val dockerUp by tasks.registering(Exec::class) {
    enabled = isE2EExplicit
    dependsOn(":adapter-<gateway>:shadowJar")  // Build adapter first
    commandLine("bash", "${rootDir}/scripts/<gw>-e2e-infra-up.sh")
}

// Tear down Docker infrastructure
val dockerDown by tasks.registering(Exec::class) {
    enabled = isE2EExplicit
    commandLine("bash", "${rootDir}/scripts/<gw>-e2e-infra-down.sh")
}

// Test task wiring
tasks.withType<Test>().configureEach {
    enabled = isE2EExplicit
    dependsOn(dockerUp)
    finalizedBy(dockerDown)
    systemProperty("karate.options", System.getProperty("karate.options", ""))
    systemProperty("karate.env", System.getProperty("karate.env", "docker"))
}
```

### Task Graph

```
:adapter-<gw>:shadowJar → :e2e-<gw>:dockerUp → :e2e-<gw>:test → :e2e-<gw>:dockerDown
```

### Guard Behavior (PingAccess Example)

| Command | Docker starts? | E2E tests run? |
|---------|:-:|:-:|
| `./gradlew :e2e-pingaccess:test` | ✅ | ✅ |
| `./gradlew test` (bare) | ✅ | ✅ |
| `./gradlew clean test --tests e2e.PingAccessE2ETest` | ✅ | ✅ |
| `./gradlew check` | ❌ | ❌ |
| `./gradlew build` | ❌ | ❌ |

### Karate System Properties

Two system properties are forwarded from the Gradle command line to Karate:

- `karate.options` — feature file path + line number (for single-scenario runs)
- `karate.env` — environment selection (default: `docker`)

### Karate Environments (`karate.env`)

Configured in each module's `karate-config.js`:

| Value | Purpose | When Used |
|-------|---------|-----------|
| `docker` (default) | Local Docker containers on localhost with mapped ports | Local development, Karate Runner |
| `ci` | Override ports/hosts for CI environment | CI pipelines |
| Custom | Any gateway-specific overrides | Staging, remote targets |

---

## 6. Karate Runner IDE Extension

### Extension: `kirkslota.karate-runner` (v1.2.5)

This VS Code extension adds **CodeLens** ("Karate: Run" / "Karate: Debug")
above each `Feature:` and `Scenario:` line in `.feature` files. Clicking it
runs the test via Gradle.

### VS Code Settings (`.vscode/settings.json`)

```json
{
    "karateRunner.karateRunner.default": "e2e.PingAccessE2ETest",
    "karateRunner.karateRunner.promptToSpecify": false,
    "karateRunner.buildSystem.useWrapper": true
}
```

| Setting | Value | Why |
|---------|-------|-----|
| `default` | `e2e.PingAccessE2ETest` | The JUnit 5 runner class for the active gateway |
| `promptToSpecify` | `false` | Don't ask which runner to use every time |
| `useWrapper` | `true` | Use `./gradlew` instead of system `gradle` |

> **Multi-gateway note:** When switching between gateways, update
> `karateRunner.karateRunner.default` to the correct runner class
> (e.g., `e2e.PingGatewayE2ETest`). In the future, if working across
> multiple gateways simultaneously, set `promptToSpecify: true` to be
> asked each time.

### Gradle Wrapper Symlink

The extension runs from the subproject directory, but `gradlew` lives at the
project root. Each E2E module needs a symlink:

```bash
ln -s ../gradlew e2e-pingaccess/gradlew
# For future modules:
ln -s ../gradlew e2e-pinggateway/gradlew
```

> **Note:** Commit these symlinks to Git so all developers get them.

### Extension Patches (Compatibility with Gradle 9.x and Karate)

Two patches were applied to the extension's compiled code at
`~/.antigravity-server/extensions/kirkslota.karate-runner-1.2.5/out/commands.js`.
These are **gateway-neutral** and apply to all E2E modules.

#### Patch 1: Remove `-b` flag (Gradle 9.x compatibility)

Gradle 9.x removed the `-b` (build file) flag. The extension hardcoded it:
```javascript
// BEFORE (broken with Gradle 9.x):
runCommand = `${gradleCmd} ${runPhases} -b "${runFilePath}" ...`;
// AFTER (fixed):
runCommand = `${gradleCmd} ${runPhases} ...`;
```

Applied in two locations (~line 272 and ~line 306).

#### Patch 2: Convert absolute paths to classpath-relative (Karate compatibility)

Karate's `Runner.Builder.resolveAll()` prepends the `relativeTo` classpath
prefix to non-classpath paths. An absolute path like
`/home/user/.../context-variables.feature:12` gets mangled to
`classpath:e2e/home/user/.../context-variables.feature:12.feature`, causing
a `NumberFormatException`.

```javascript
// AFTER (fixed):
let classPathKarateOptions = karateOptions;
let srcTestJavaIndex = classPathKarateOptions.indexOf('/src/test/java/');
if (srcTestJavaIndex !== -1) {
    classPathKarateOptions = 'classpath:' +
        classPathKarateOptions.substring(srcTestJavaIndex + '/src/test/java/'.length);
}
runCommand = `... -Dkarate.options="${classPathKarateOptions}" ...`;
```

**⚠️ These patches are applied to the compiled extension output.** They will be
lost if the extension is updated. Re-apply after any extension update.

### Generated Command

When you click "Karate: Run" on a scenario at line 23, the extension generates:

```bash
./gradlew clean test \
    --tests e2e.PingAccessE2ETest \
    -Dkarate.options="classpath:e2e/context-variables.feature:23"
```

This triggers the full Gradle task graph: `shadowJar → dockerUp → test → dockerDown`.

---

## 7. How a Test Executes End-to-End

Here's the complete flow when clicking "Karate: Run" on a scenario
(using PingAccess as the example):

```
1. IDE: Karate Runner extension reads .feature file, finds Scenario at line N
2. IDE: Generates command:
   ./gradlew clean test --tests e2e.PingAccessE2ETest
     -Dkarate.options="classpath:e2e/<phase>/<file>.feature:N"
3. Gradle: isE2EExplicit = true (taskNames contains "test")
4. Gradle: :adapter-pingaccess:shadowJar  (builds the adapter JAR)
5. Gradle: :e2e-pingaccess:dockerUp
   └─ pa-e2e-infra-up.sh
      ├─ Curl gateway admin API → already running? Skip.
      ├─ Start echo backend, mock-OIDC, gateway container
      └─ Wait for readiness → write .e2e-infra-started marker
6. Gradle: :e2e-pingaccess:test
   └─ JUnit 5 runs PingAccessE2ETest
      └─ Karate.run().relativeTo(getClass())
         └─ Reads -Dkarate.options → filters to specific .feature:N
            └─ Executes:
               a. karate-config.js (global config)
               b. callonce <gw>-provision.feature (idempotent gateway setup)
               c. The specific Scenario at line N
7. Gradle: :e2e-pingaccess:dockerDown
   └─ pa-e2e-infra-down.sh
      ├─ Check .e2e-infra-started marker → exists? Tear down.
      └─ docker rm -f <containers>
```

**Typical timing (PingAccess):** ~35-45 seconds total
- Shadow JAR build: ~3s (incremental) / ~10s (clean)
- Docker startup: ~25s (gateway boot)
- Test execution: ~3-5s per scenario
- Docker teardown: ~2s

> **Gateway startup variance:** PingAccess boots in ~25s. Lighter gateways
> (NGINX, standalone proxy) may boot in <5s. Heavier gateways (PingGateway +
> PingFederate) may take 60-90s. Adjust the readiness timeout in your
> `infra-up.sh` accordingly.

---

## 8. Writing Karate Tests

### Global Configuration (`karate-config.js`)

Each gateway module has its own `karate-config.js`. PingAccess example:

| Variable | Value | Purpose |
|----------|-------|---------|
| `paAdminUrl` | `https://localhost:19000/pa-admin-api/v3` | Gateway admin API base URL |
| `paEnginePort` | `13000` | Gateway engine mapped port |
| `paEngineHost` | `localhost:3000` | Gateway virtual host (must match gateway config!) |
| `echoPort` | `18080` | Echo backend mapped port |
| `paContainer` | `pa-e2e-test` | Container name for `docker exec` |
| `paAdminHeaders` | `{X-XSRF-Header, Content-Type}` | Required gateway admin headers |
| `jmxPort` | `19999` | JMX remote port |

> **For new gateways:** Define equivalent variables with gateway-appropriate
> names (e.g., `pgAdminUrl`, `pgContainer` for PingGateway).

### Provisioning (`setup/<gw>-provision.feature`)

Called via `callonce` from every test feature's `Background:`. The provisioning
feature uses the gateway's admin API to configure:

- **Backends/Sites:** Echo backend as upstream target
- **Rules/Filters:** Transform rules with different error modes
- **Routes/Applications:** URL paths mapped to backends with rules applied
- **OAuth/OIDC:** Token validators, session configs (conditional)

PingAccess-specific provisioning creates: Sites, Rules, Applications, ATVs, and
Web Session configs via the PA Admin API.

Provisioning is **idempotent** — uses find-by-name-then-create pattern.

> **`@setup` tag:** The provisioning feature is tagged `@setup` at the top.
> This is a Karate convention that tells the framework this feature should
> run before other features during full-suite execution. Combined with
> `callonce`, it ensures provisioning happens exactly once regardless of
> execution order.

### Feature Template

```gherkin
Feature: My Test Phase

  Background:
    * callonce read('classpath:e2e/setup/<gw>-provision.feature')
    * url 'https://localhost:' + enginePort
    * configure ssl = true
    # IMPORTANT: Reset headers leaked by callonce provisioning
    * configure headers = null

  Scenario: Test description (S-002-XX)
    Given path '/api/test-endpoint'
    And header Host = engineHost    # Must match gateway virtual host!
    And request { field: 'value' }
    When method POST
    Then status 200
    And match response.transformedField == 'expected'
```

### Assertion Priority (All Gateways)

When asserting through a reverse proxy, reliability varies by what the gateway
preserves:

1. ✅ **Response body fields** — always preserved, most reliable
2. ✅ **Response status code** — reliable
3. ⚠️ **Container logs** (`karate.exec('docker exec ...')`) — for gateway-internal events
4. ❌ **Response headers** — **unreliable** (many gateways strip custom backend headers)
5. ℹ️ **Direct echo probe** (port 18080, bypass gateway) — useful as a control test

---

## 9. Running Tests

### From the IDE (Karate Runner Extension)

Click the **"Karate: Run"** CodeLens above any `Scenario:` line. The extension
handles everything automatically (build → Docker → test → teardown).

### From the Command Line

```bash
# Full lifecycle (recommended for CI):
./scripts/pa-e2e-bootstrap.sh

# Via Gradle (Docker lifecycle managed automatically):
./gradlew :e2e-pingaccess:test

# Single scenario by line number:
./gradlew :e2e-pingaccess:test \
    -Dkarate.options="classpath:e2e/context-variables.feature:12"

# Single phase — use karate.options with a directory path:
./gradlew :e2e-pingaccess:test \
    -Dkarate.options="classpath:e2e/profile-routing.feature"
```

> **Note:** `--tests '*routing*'` does NOT filter by Karate feature path — it
> filters by JUnit class name. Since all scenarios run through a single runner
> class, this pattern always resolves to the full suite. Use `-Dkarate.options`
> with a classpath directory to run a subset.

### Iterative Development (Keep Docker Up)

For iterative development, avoid the gateway startup delay on every run:

```bash
# 1. Start infra once (manually — no marker file written by Gradle)
bash scripts/pa-e2e-infra-up.sh

# 2. Run individual tests as many times as needed — dockerUp skips (gateway
#    already responding), dockerDown skips (no marker file)
./gradlew :e2e-pingaccess:test \
    -Dkarate.options="classpath:e2e/context-variables.feature:12"

# 3. Tear down when done
bash scripts/pa-e2e-infra-down.sh --force
```

This works because:
- `infra-up.sh` detects the gateway is already running → exits immediately
- `infra-down.sh` checks for the marker file → no marker → exits without
  tearing down your manually-started infra

### Karate HTML Report

After every run, Karate generates an HTML report at:
```
e2e-<gateway>/build/karate-reports/karate-summary.html
```

---

## 10. Karate Runner Extension Patches

This section provides the technical deep-dive into why the extension patches
from [Section 6](#extension-patches-compatibility-with-gradle-9x-and-karate) are
needed. These patches are **gateway-neutral**.

### Root Cause: Karate Path Resolution Bug

Karate's `Runner.Builder.resolveAll()` (in `Runner.java` line ~234) does this:

```java
if (relativeTo != null) {
    paths = paths.stream().map(p -> {
        if (p.startsWith("classpath:")) {
            return p;  // ← classpath: paths pass through unchanged
        }
        if (!p.endsWith(".feature")) {
            p = p + ".feature";  // ← Appends .feature to "file.feature:12"!
        }
        return relativeTo + "/" + p;
    }).collect(Collectors.toList());
}
```

An absolute path like `/home/.../file.feature:12` doesn't end with `.feature`
(it ends with `:12`), so Karate appends `.feature`, creating
`classpath:e2e/home/.../file.feature:12.feature`. Then `ResourceUtils.findFeatureFiles`
tries to parse `12.feature` as a line number at this line:

```java
line = Integer.valueOf(path.substring(pos + 9));  // pos+9 = after ".feature:"
```

→ `NumberFormatException: For input string: "12.feature"`

The `classpath:` prefix fix short-circuits the `relativeTo` prepending because
Karate's first check (`p.startsWith("classpath:")`) returns the path unchanged.

---

## 11. Pitfalls & Lessons Learned

### Universal Pitfalls (All Gateways)

#### P1: Reverse Proxies Strip Backend Response Headers

Most reverse proxies (PA, NGINX, Envoy) remove or modify custom `X-*` headers
from backend responses. Assert on **body content** and **container logs** instead.

#### P2: `callonce` Leaks Headers into Calling Scope

After `callonce read('classpath:e2e/setup/<gw>-provision.feature')`, the admin
headers persist in the calling feature's scope. Always reset:

```gherkin
* configure headers = null
```

#### P2a: `callonce` Caching Is JVM-Scoped

`callonce` caches its result for the **entire JVM lifetime**. If a test modifies
gateway state (creates/deletes resources), the cached provisioning data still
holds the old IDs.

**Solution:** Make provisioning idempotent. Use the lookup-or-create pattern.
Never delete shared resources inside a test — use separate test-specific
resources if destructive operations are needed.

#### P3: Helper Features Must Be Tagged `@ignore`

Karate auto-discovers all `.feature` files. Helper features that require
arguments will fail if run directly. Tag them:

```gherkin
@ignore
Feature: Helper — find resource by name
```

#### P4: `karate.exec()` Returns an Object, Not a String

`karate.exec()` returns `{ exitCode, output }`. Checking the result as a
boolean or string fails silently:

```gherkin
# WRONG:
* def result = karate.exec(cmd)
* assert result    # always truthy — it's an object!

# RIGHT:
* def result = karate.exec(cmd)
* assert result.exitCode == 0
```

#### P5: Don't Capture Full Container Logs

Never do `karate.exec('docker logs <container>')` — the output can exceed
Karate's string limits. Use `grep -qF` (quiet + fixed string) to return only
an exit code:

```gherkin
* def cmd = 'docker exec ' + gwContainer + ' grep -qF "Loaded spec" ' + logFile
* def result = karate.exec(cmd)
* assert result.exitCode == 0
```

#### P6: OAuth Token Endpoint Requires Form Encoding

```gherkin
And header Content-Type = 'application/x-www-form-urlencoded'
And form field grant_type = 'client_credentials'
```

#### P6a: Conditional Test Skipping

When optional infrastructure (e.g., OAuth/OIDC server) may not be available,
use `karate.abort()` for clean no-op skipping:

```gherkin
* eval if (__arg.oauthSkip) karate.abort()
```

Provisioning detects availability and exports skip flags. Test features check
these flags at scenario start. `karate.abort()` marks the scenario as skipped
without failure.

#### P7: Configuration Cache Compatibility (Gradle 9.x)

Use `enabled = isE2EExplicit` (configuration-time boolean) NOT
`onlyIf { isE2EExplicit }` (execution-time closure). Closures capture Gradle
script object references which can't be serialized for the config cache.

#### P8: Gradle Wrapper Must Be Accessible from Subproject Directory

The Karate Runner extension runs `./gradlew` from the subproject dir. Create
a symlink (commit it to Git):

```bash
ln -s ../gradlew e2e-<gateway>/gradlew
```

### PingAccess-Specific Pitfalls

#### P9: `Host` Header Must Match PA Virtual Host

PA returns 403 "Unknown Resource Proxy" if the `Host` header doesn't match the
configured virtual host. Use `localhost:3000` (the PA internal port), not
`localhost:13000` (the Docker-mapped port):

```gherkin
And header Host = paEngineHost    # 'localhost:3000'
```

> **Other gateways:** NGINX and PingGateway may not enforce this — check your
> gateway's virtual host / server_name matching behavior.

#### P10: `$session` Can Be `null`, `{}`, or Absent

JSLT renders `SessionContext.empty()` differently depending on binding.
Use flexible assertions:

```gherkin
* def s = response.session
* assert s == null || (typeof s == 'object' && Object.keys(s).length == 0)
```

#### P11: HTTP Response Header Casing Is Case-Sensitive in Karate Bracket Notation

Karate's `responseHeaders['Location']` uses **case-sensitive** JavaScript
object property access. Different servers return different casings:

- PingAccess: `Location` (capital L)
- mock-oauth2-server: `location` (lowercase l)
- Some proxies: mixed casing

Use a helper function to try both casings:

```gherkin
* def getLocation = function(hdrs){ return (hdrs['Location'] || hdrs['location'] || [null])[0] }

# Then use it:
* def redirectUrl = getLocation(responseHeaders)
```

> **Note:** Karate's `responseHeaders` has case-insensitive access via
> **dot notation** (`responseHeaders.Location`), but this doesn't work
> when the header name contains special characters or when accessed from
> JavaScript expressions. Bracket notation is always case-sensitive.

#### P12: mock-oauth2-server HTTPS Configuration

The `ghcr.io/navikt/mock-oauth2-server` supports two HTTPS configuration
methods. Only one works reliably for E2E testing:

| Method | Env vars / Config | Works? | Issue |
|--------|-------------------|:------:|-------|
| `SERVER_SSL_*` env vars | `SERVER_SSL_ENABLED=true`, `SERVER_SSL_KEY_STORE=...`, `SERVER_PORT=8080` | ✅ | Uses Spring Boot's embedded Tomcat with full SSL support |
| `JSON_CONFIG` with `NettyWrapper` | `JSON_CONFIG='{"httpServer": {"type": "NettyWrapper", "ssl": {...}}}'` | ❌ | Binds to wrong port; SSL initialization is unreliable |
| `JSON_CONFIG` with `MockWebServerWrapper` | `JSON_CONFIG='{"httpServer": {"type": "MockWebServerWrapper", "ssl": {...}}}'` | ⚠️ | Works but ignores `SERVER_PORT`; binds to 8080 regardless |

**Recommendation:** Use `SERVER_SSL_*` environment variables. Set
`SERVER_PORT=8080` (the default) and connect to port 8080 over HTTPS.

#### P13: JMX/RMI Port Mapping Through Docker

JMX uses RMI (Remote Method Invocation), which has a **two-phase connection**:

1. Client connects to the RMI registry at `hostname:port`
2. The registry returns an RMI stub containing the **server-side** port number
3. Client re-connects to `hostname:stubPort`

If the container port (9999) ≠ host port (19999), the stub tells the client
to connect to `localhost:9999`, which **doesn't exist** on the host (only
19999 is mapped). Result: `Connection refused`.

**Fix:** Use the **same** port number on both sides:

```bash
JMX_PORT=19999
JMX_CONTAINER_PORT=19999  # Must equal JMX_PORT!

# JVM args
-Dcom.sun.management.jmxremote.port=$JMX_CONTAINER_PORT
-Dcom.sun.management.jmxremote.rmi.port=$JMX_CONTAINER_PORT
-Djava.rmi.server.hostname=localhost

# Docker port mapping
-p "$JMX_PORT:$JMX_CONTAINER_PORT"  # 19999:19999
```

> **Note:** `java.rmi.server.hostname=localhost` ensures RMI returns
> `localhost` (not the container's internal hostname) in the stub. Combined
> with matching port numbers, the client sees `localhost:19999` — exactly
> what Docker maps.

### Known Limitations

1. **No parallel test execution:** Tests modify shared gateway state (rules, apps).
   Parallel execution would cause race conditions. Karate's default serial
   execution is correct for our use case.

2. **Container startup time:** PingAccess takes 25-60 seconds to start. The infra
   script polls readiness with a retry loop. Total E2E runtime is ~2-3 minutes.

3. **Log inspection fragility:** `grep` against container logs is inherently
   fragile — log format changes across gateway versions. Prefer body/status
   assertions when possible.

---

## 12. Porting to Other Gateways

### Step-by-Step Checklist

To add E2E tests for a new gateway:

#### 1. Create the Gradle subproject

```
e2e-<gateway>/
├── build.gradle.kts        # Copy from e2e-pingaccess, adjust names
├── gradlew                 # Symlink → ../gradlew
└── src/test/java/
    ├── karate-config.js    # Gateway-specific ports and URLs
    ├── logback-test.xml    # Copy from e2e-pingaccess
    └── e2e/
        ├── <Gateway>E2ETest.java
        ├── setup/
        │   └── <gw>-provision.feature
        └── helpers/
            └── (reuse or copy from e2e-pingaccess)
```

#### 2. Register in `settings.gradle.kts`

```kotlin
include("e2e-<gateway>")
```

#### 3. Create infrastructure scripts

```bash
scripts/<gw>-e2e-infra-up.sh    # Gateway-specific Docker startup
scripts/<gw>-e2e-infra-down.sh  # Gateway-specific teardown
scripts/<gw>-e2e-bootstrap.sh   # Full lifecycle (optional, for CI)
```

The echo backend and mock-OIDC server can be shared — start the same containers
with the same names/ports. The gateway container is the only thing that differs.

#### 4. Adapt `build.gradle.kts`

```kotlin
val isE2EExplicit = gradle.startParameter.taskNames.any {
    it.contains("e2e-<gateway>") || it == "test"
}
// ... dockerUp depends on ":adapter-<gateway>:shadowJar"
```

#### 5. Create the wrapper symlink

```bash
ln -s ../gradlew e2e-<gateway>/gradlew
```

#### 6. Update IDE settings

```json
{
    "karateRunner.karateRunner.default": "e2e.<Gateway>E2ETest"
}
```

#### 7. Write the provisioning feature

This is the biggest gateway-specific work. Each gateway has its own admin API
or config mechanism:

| Gateway | Provisioning Method | Config Style |
|---------|-------------------|--------------|
| PingAccess | REST Admin API (HTTPS, Basic auth) | JSON via API calls |
| PingGateway | REST Admin API or static config | JSON/YAML routes |
| NGINX | Config files + `nginx -s reload` | `nginx.conf` directives |
| Envoy | xDS API or static config | JSON/YAML |
| Standalone Proxy | Config file | YAML spec files |

#### 8. Port test phases

Re-use the same phase structure. Most phases (plugin, routing, context, edge,
error, OAuth) apply to all gateways — only the provisioning and assertion
details change.

### Shared Patterns

If multiple E2E subprojects emerge, extract shared helpers to a common module:
```
e2e-common/
├── src/test/java/e2e/helpers/
│   ├── basic-auth.js
│   └── concurrent-request.feature
```

### Gateway Comparison Reference

| Aspect | PingAccess | PingGateway | Standalone | NGINX |
|--------|-----------|-------------|------------|-------|
| Admin API | HTTPS REST | REST/Config | N/A (file) | N/A (file) |
| License | Required | Required | No | No |
| Boot time | ~25s | ~60-90s | ~2s | ~1s |
| Header behavior | Strips custom | Preserves most | Preserves all | Configurable |
| Auth required | `X-XSRF-Header` | Bearer token | N/A | N/A |
| Adapter mount | `/opt/server/deploy/` | `/opt/gateway/deploy/` | classpath | module path |

---

## 13. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` on gateway port | Docker infra not running | Run `bash scripts/<gw>-e2e-infra-up.sh` |
| `NumberFormatException` in Karate | Absolute path in `karate.options` | Re-apply classpath patch to `commands.js` |
| `Unknown build option '-b'` | Gradle 9.x removed `-b` flag | Re-apply `-b` removal patch to `commands.js` |
| `403 Unknown Resource Proxy` | `Host` header mismatch (PA-specific) | Use `paEngineHost` (`localhost:3000`), not mapped port |
| `Task ':e2e-<gw>:test' skipped` | Guard evaluated to false | Ensure command includes `test` or `:e2e-<gw>:test` |
| `Shadow JAR not found` | Clean build without rebuild | Run `./gradlew :adapter-<gw>:shadowJar` first |
| Config cache errors | Used `onlyIf` closure | Use `enabled = isE2EExplicit` instead |
| Extension "Run" button missing | `.feature` not in `src/test/java` | Karate Runner only shows CodeLens in recognized paths |
| Tests pass but `BUILD FAILED` | Config cache warnings | Fixed by using `enabled` instead of `onlyIf` |
| Docker teardown destroys manual infra | Marker file from earlier Gradle run | Delete `.e2e-infra-started` or use `--force` flag |
| Gateway started but tests fail | Adapter JAR stale after code change | Run `./gradlew :adapter-<gw>:shadowJar` then restart |
| JMX `Connection refused` | Container JMX port ≠ host-mapped port | Set `JMX_CONTAINER_PORT` = `JMX_PORT` (same value). See [P13](#p13-jmxrmi-port-mapping-through-docker) |
| OIDC 403 on Web Session request | PingFederate Runtime not configured | `PUT /pingfederate/runtime` with OIDC issuer. See [PA Ops Guide §25](pingaccess-operations-guide.md#25-oidc--common-token-provider-configuration) |
| OIDC metadata missing fields | Proxy not injecting required fields | Ensure proxy injects `token_endpoint_auth_methods_supported` and `ping_end_session_endpoint` |
| JMX counters always 0 | PA creates multiple rule instances; MBean points to a different instance than the one handling requests | Use `MessageTransformMetrics.forInstance()` static registry for shared counters. See [PA Ops Guide — JMX Pitfall 2](pingaccess-operations-guide.md#pitfall-2--pa-creates-multiple-rule-instances-shared-metrics-required) |
| JMX counter diff assertion flaky | Wildcard `instance=*` with 2+ JMX-enabled rules returns non-deterministic MBean | Query exact instance name. See [PA Ops Guide — JMX Pitfall 3](pingaccess-operations-guide.md#pitfall-3--wildcard-mbean-queries-with-multiple-rules) |
| Code changes not reflected in E2E | Gradle build cache returns stale shadow JAR | Use `--no-build-cache`: `./gradlew clean :adapter-pingaccess:shadowJar --no-build-cache` |

---

## 14. Reference

### File Quick Reference (PingAccess)

| File | Purpose |
|------|---------|
| `scripts/pa-e2e-bootstrap.sh` | Full lifecycle: build + Docker + test + teardown |
| `scripts/pa-e2e-infra-up.sh` | Idempotent Docker startup (Gradle integration) |
| `scripts/pa-e2e-infra-down.sh` | Marker-gated Docker teardown |
| `e2e-pingaccess/build.gradle.kts` | Gradle tasks: dockerUp, test, dockerDown |
| `e2e-pingaccess/src/test/java/karate-config.js` | Global Karate configuration |
| `e2e-pingaccess/src/test/java/e2e/PingAccessE2ETest.java` | JUnit 5 test runner |
| `e2e-pingaccess/src/test/java/e2e/setup/pa-provision.feature` | PA provisioning |
| `.vscode/settings.json` | Karate Runner extension settings |
| `.e2e-infra-started` | Marker file (gitignored) — tracks who started Docker |

### Port Registry (PingAccess)

| Port | Service | Protocol |
|------|---------|----------|
| 19000 | PA Admin API | HTTPS |
| 13000 | PA Engine | HTTPS |
| 18080 | Echo Backend | HTTP |
| 18443 | Mock OIDC Proxy (TLS) | HTTPS |
| 19999 | JMX Remote | TCP |

### Dependencies

| Component | Version | Purpose |
|-----------|---------|---------|
| Karate DSL | 1.4.1 | Test framework |
| JUnit 5 | (BOM-managed) | Test runner |
| Gradle | 9.2.0 | Build tool |
| Karate Runner | 1.2.5 | IDE extension (patched) |

### See Also

- [E2E Test Framework Research](../research/e2e-test-framework-research.md) — Framework selection rationale
- [E2E Validation Record](../../e2e-pingaccess/docs/e2e-results.md) — Test results and coverage
- [PingAccess Operations Guide](pingaccess-operations-guide.md) — PA deployment reference
