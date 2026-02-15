# Karate DSL — E2E Patterns & Pitfalls

_Created:_ 2026-02-15
_Status:_ Active reference
_Context:_ Lessons learned from migrating `scripts/pa-e2e-test.sh` (1,701 lines)
to a Karate DSL suite (`e2e-pingaccess/`, 26 scenarios, 8 feature files).
_Audience:_ Future sessions implementing E2E tests for other features/adapters.

---

## 1. Architecture: Gradle-Integrated Docker Lifecycle + Karate

The E2E suite is split into three layers:

| Layer | Responsibility | Technology |
|-------|---------------|------------|
| **IDE** (`Karate Runner` extension) | Click-to-run scenarios, CodeLens, debug | VS Code extension |
| **Build** (`build.gradle.kts` + scripts) | Docker lifecycle, shadow JAR build, task graph | Gradle 9.x + Bash |
| **Tests** (`e2e-pingaccess/`) | PA Admin API provisioning, HTTP assertions, payload verification | Karate DSL |

**Gradle task graph:**
```
:adapter-pingaccess:shadowJar → dockerUp → test → dockerDown
```

Docker lifecycle is **automatic** — clicking "Run" in the Karate Runner extension
or running `./gradlew :e2e-pingaccess:test` triggers the full flow.
The `dockerUp` script is **idempotent** (skips if PA is already running).
The `dockerDown` script is **marker-gated** (only tears down what Gradle started).

> **Full reference:** See [E2E Karate Operations Guide](../operations/e2e-karate-operations-guide.md)
> for complete setup instructions, extension patches, and porting template.

---

## 2. Project Layout

```
e2e-pingaccess/
├── build.gradle.kts              # Karate dependency + JUnit5 runner config
├── src/test/java/
│   ├── karate-config.js          # Global config: ports, URLs, SSL, timeouts
│   ├── logback-test.xml          # Suppress noisy Karate logs
│   └── e2e/
│       ├── PingAccessE2ETest.java # JUnit5 runner (discovers all .feature)
│       ├── setup/
│       │   └── pa-provision.feature   # PA Admin API provisioning (callonce)
│       ├── helpers/
│       │   ├── basic-auth.js          # HTTP Basic Auth header builder
│       │   ├── find-resource.feature  # Lookup PA resource by name
│       │   └── create-if-absent.feature # Idempotent PA resource creation
│       ├── phase1-plugin/
│       │   └── plugin-discovery.feature
│       ├── phase3-routing/
│       │   └── profile-routing.feature
│       ├── phase4-context/
│       │   └── context-variables.feature
│       ├── phase5-edge/
│       │   └── body-status-edge.feature
│       ├── phase6-error/
│       │   └── error-modes.feature
│       ├── phase8-oauth/
│       │   └── oauth-identity.feature
│       └── phase8b-websession/
│           └── web-session-oidc.feature
```

**Convention:** Feature files are grouped into `phase*-<topic>/` directories
matching the test phases from the expansion plan. This makes it easy to run
a single phase: `./gradlew :e2e-pingaccess:test --tests '*phase3*'`.

---

## 3. Key Karate Patterns Used

### 3a. One-Time Setup via `callonce`

All test features share a single provisioning call:

```gherkin
Background:
  * callonce read('classpath:e2e/setup/pa-provision.feature')
```

`callonce` executes the feature **once per JVM lifetime** and caches the
result. All exported variables (`siteId`, `ruleId`, `appId`, etc.) are
available to every feature. This replaces the shell script's sequential
provisioning section.

> **Pitfall:** `callonce` caching is global. If a test modifies PA state
> (e.g., deletes a rule), the cached provisioning data becomes stale.
> Make provisioning **idempotent** — check if resources exist before creating.

### 3b. Idempotent Provisioning (Lookup-or-Create)

```gherkin
* def existingId = findId('/sites', 'Echo Backend')
* if (existingId != null) siteId = existingId
# Only create if absent:
* if (existingId == null) ...create via POST...
```

This pattern survived from the shell script's `jq` lookups. In Karate, the
helper features (`find-resource.feature`, `create-if-absent.feature`) are
reusable across any PA Admin API entity.

### 3c. Container Log Inspection via `karate.exec()`

PingAccess strips some backend response headers during proxying.
To verify that a transform rule executed, inspect the PA container log:

```gherkin
* def cmd = 'docker exec ' + paContainer + ' grep -qF "Loaded spec: e2e-rename" ' + paLogFile
* def result = karate.exec(cmd)
* assert result.exitCode == 0
```

This replaces the shell script's `docker exec ... grep -qF ...` pattern.

> **Pitfall:** `karate.exec()` runs on the **host** (JVM process), not
> inside the container. Always use `docker exec <container> ...` to
> run commands inside Docker.
>
> **Pitfall:** Never capture the entire log into a variable — it can
> exceed Karate's string limits. Use `grep -qF` (quiet + fixed string)
> to return only the exit code.

### 3d. Conditional Test Skipping

```gherkin
* eval if (__arg.phase8Skip) karate.abort()
```

Provisioning detects whether OAuth/OIDC infrastructure is available and
exports skip flags. Test features check these flags at scenario start.
`karate.abort()` is a clean no-op that marks the scenario as skipped.

### 3e. Basic Auth Helper (JS)

```javascript
// helpers/basic-auth.js
function() {
  return 'Basic ' + java.util.Base64.getEncoder().encodeToString(
    new java.lang.String(paUser + ':' + paPassword).getBytes());
}
```

Karate's inline JS can call Java classes directly. This avoids importing
external libraries for simple operations.

### 3f. SSL and Redirect Configuration

```javascript
// karate-config.js
karate.configure('ssl', true);                // Skip TLS verify (self-signed PA)
karate.configure('followRedirects', false);    // OIDC flow requires hop-by-hop control
```

These are **global** settings. Individual scenarios can override them.

### 3g. JSON Response Matching

Karate's `match` is the killer feature for JSON assertions:

```gherkin
# Exact match
And match response.userId == 'u123'

# Contains (subset)
And match response contains { userId: '#notnull' }

# Deep nested
And match response.session.subject == 'testuser'

# Array contains
And match response.items contains { name: 'Echo Backend' }
```

This replaces the shell script's `jq` piping and string comparison.

---

## 4. Pitfalls Discovered During Migration

### Pitfall 1: PingAccess Strips Backend Response Headers

**Problem:** Tests that asserted on `X-Echo-*` response headers failed because
PingAccess strips custom headers from backend responses during proxying.

**Symptom:** `responseHeaders['x-echo-req-content-type']` is `null` even though
the echo backend sends it.

**Solution:** Verify transformations via:
1. **Response body content** (always preserved)
2. **PA container logs** (`docker exec ... grep`)
3. **Direct echo probe** (bypass PA, hit echo on port 18080)

> This is the #1 pitfall for any PA E2E test. Plan assertions around body
> content and log inspection, not backend response headers.

### Pitfall 2: `$session` May Be Null, Empty Object, or Absent

**Problem:** JSLT renders `SessionContext.empty()` differently depending on
binding — `null`, `{}`, or completely absent key.

**Symptom:** Assertion `match response.session == null` fails when JSLT
produces `{}` instead.

**Solution:** Use flexible assertion:
```gherkin
* def sessionVal = response.session
* assert sessionVal == null || (typeof sessionVal == 'object' && Object.keys(sessionVal).length == 0)
```

### Pitfall 3: `callonce` Caching Is JVM-Scoped

**Problem:** If a test modifies PA state (creates/deletes resources), the
cached `callonce` result still holds the old IDs.

**Solution:** Make provisioning idempotent. Use lookup-or-create pattern.
Never delete shared resources inside a test — use separate test-specific
resources if destructive operations are needed.

### Pitfall 4: Karate's `karate.exec()` Return Type

**Problem:** `karate.exec()` returns an object with `exitCode` and `output`.
Using `* def result = karate.exec(cmd)` and then checking `result` as a
string fails silently.

**Solution:** Always check `result.exitCode`:
```gherkin
* def result = karate.exec(cmd)
* assert result.exitCode == 0
```

### Pitfall 5: Host Header Must Match PA Virtual Host

**Problem:** PA returns 403 with "Unknown Resource Proxy" if the `Host`
header doesn't match the configured virtual host.

**Symptom:** All engine requests return 403.

**Solution:** Always set `Host` to match the PA virtual host (e.g.,
`localhost:3000`), not the externally mapped port (e.g., `localhost:13000`):
```gherkin
And header Host = paEngineHost    # 'localhost:3000' — not 'localhost:13000'
```

### Pitfall 6: Karate Feature File Discovery

**Problem:** Karate discovers `.feature` files via classpath scanning. Helper
features (like `find-resource.feature`) are also discovered and run as tests,
causing failures if they require arguments.

**Solution:** Tag helper features with `@ignore`:
```gherkin
@ignore
Feature: Find a PA resource by name
```

Alternatively, use the JUnit runner's tag exclusion.

### Pitfall 7: OAuth Token Endpoint Content-Type

**Problem:** OAuth token endpoints expect `application/x-www-form-urlencoded`,
but Karate defaults to `application/json` for `request` bodies.

**Solution:** Explicitly set the content type and use `form field`:
```gherkin
And header Content-Type = 'application/x-www-form-urlencoded'
And form field grant_type = 'client_credentials'
```

---

## 5. Conventions for Future Features

### 5a. File Naming

| Purpose | Pattern | Example |
|---------|---------|---------|
| Test feature | `phase*-<topic>/<descriptive-name>.feature` | `phase3-routing/profile-routing.feature` |
| Setup/provisioning | `setup/<system>-provision.feature` | `setup/pa-provision.feature` |
| Helper (reusable) | `helpers/<verb>-<noun>.feature` | `helpers/find-resource.feature` |
| JS helper | `helpers/<purpose>.js` | `helpers/basic-auth.js` |
| Global config | `karate-config.js` | (root of test classpath) |

### 5b. Module Naming for Other Gateways

When adding Karate E2E for another adapter (e.g., standalone proxy), create
a new Gradle subproject:

```
e2e-standalone/             # Standalone proxy E2E
e2e-pingaccess/             # PingAccess E2E (existing)
e2e-<gateway>/              # Future gateway E2E
```

Each module has its own `karate-config.js`, `build.gradle.kts`, and
`setup/` directory. Shared helpers can be extracted to a `e2e-common/`
module if patterns emerge.

### 5c. Assertion Strategy Priority

When writing assertions for gateway E2E tests:

1. **Response body fields** — most reliable, always preserved
2. **Response status code** — reliable
3. **Container logs** (via `karate.exec()`) — for gateway-internal events
4. **Response headers** — **unreliable** through reverse proxies (PA strips them)
5. **Direct backend probe** — useful as a control test

### 5d. Bootstrap Script Template

Future gateway bootstrap scripts should follow this structure:

```bash
#!/usr/bin/env bash
set -euo pipefail

# 1. CLI args (--skip-build)
# 2. Preflight checks (Docker, license, dependencies)
# 3. Build (Gradle shadowJar)
# 4. Docker network + containers
# 5. Readiness wait loop
# 6. Delegate to Karate: ./gradlew :e2e-<gateway>:test
# 7. Cleanup (trap EXIT)
```

### 5e. Karate Version

Current: Karate 1.4.1 (via `com.intuit.karate:karate-junit5`).
Pin in `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("com.intuit.karate:karate-junit5:1.4.1")
}
```

---

## 6. Running Tests

```bash
# Via Gradle (Docker lifecycle managed automatically):
./gradlew :e2e-pingaccess:test

# Single scenario (Docker auto-starts):
./gradlew :e2e-pingaccess:test \
    -Dkarate.options="classpath:e2e/phase4-context/context-variables.feature:12"

# Full lifecycle for CI:
./scripts/pa-e2e-bootstrap.sh
./scripts/pa-e2e-bootstrap.sh --skip-build

# IDE: click "Karate: Run" on any Scenario line (requires Karate Runner extension)
# → automatically triggers: shadowJar → dockerUp → test → dockerDown

# Karate HTML report:
# → e2e-pingaccess/build/karate-reports/karate-summary.html
```

---

## 7. Known Limitations

1. **Tests 23-24 (Web Session/OIDC):** Skipped — require PingFederate runtime
   for full L4 session state validation. Mock OIDC server handles JWKS/token
   but not the full WebSession→OIDC redirect flow PA expects.

2. **No parallel test execution:** Tests modify shared PA state (rules, apps).
   Parallel execution would cause race conditions. Karate's default serial
   execution is correct for our use case.

3. **Container startup time:** PA takes 30-60 seconds to start. The bootstrap
   script polls `/version` with a retry loop. Total E2E runtime is ~2-3 minutes.

4. **Log inspection fragility:** `grep` against container logs is inherently
   fragile — log format changes across PA versions. Prefer body/status
   assertions when possible.

---

## See Also

- [E2E Karate Operations Guide](../operations/e2e-karate-operations-guide.md) —
  Full setup, extension patches, Gradle integration, and porting template
- [E2E Test Framework Research](e2e-test-framework-research.md) — Tooling
  analysis that led to Karate selection
- [PingAccess Operations Guide](../operations/pingaccess-operations-guide.md)
  §14 — E2E test infrastructure reference
- [E2E Expansion Plan](../architecture/features/002/e2e-expansion-plan.md) —
  Phase-by-phase test coverage plan
