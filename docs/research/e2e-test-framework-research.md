# E2E Test Framework Research: Replacing the Shell Script

## Metadata

- **Date**: 2026-02-14
- **Author**: Ivan (AI-assisted)
- **Status**: Research — revised with script-derived requirements
- **Context**: `scripts/pa-e2e-test.sh` is 1,701 lines and growing. This research
  evaluates open-source alternatives that can replace it without sacrificing any
  capability.

---

## 1. Problem Statement

The PingAccess E2E script `scripts/pa-e2e-test.sh` is a monolithic Bash workflow that combines:

1. Build orchestration (`gradlew :adapter-pingaccess:shadowJar`)
2. Docker lifecycle (network + 3 containers + cleanup trap)
3. PingAccess Admin API provisioning (28 chained GET/POST/PUT calls)
4. Engine/API validation with mixed assertions and best-effort checks
5. Runtime diagnostics (`docker exec`, `docker logs`, JWT decode, log grep)
6. Artifact validation (`javap`, `unzip`, `stat`, size constraints)
7. Aggregated pass/fail accounting and non-trivial skip semantics
8. Inline data manipulation between calls (JSON parsing, string operations, derived values)

The replacement must cover these behaviors end-to-end, not just HTTP assertions.

---

## 2. Baseline Selection Criteria

| # | Criterion | Weight | Notes |
|---|-----------|--------|-------|
| 1 | CLI-first (no UI required) | Must | UI optional |
| 2 | Open source + permissive license | Must | MIT / Apache-2.0 / BSD preferred |
| 3 | REST API calls with JSON assertions | Must | JSONPath or equivalent |
| 4 | Shell/command execution with stdout + exit code capture | Must | `docker exec`, `javap`, etc. |
| 5 | Variable capture and chaining | Must | IDs from one call feed next calls |
| 6 | Text-based config (YAML/plain text) | Must | Git-friendly |
| 7 | No JavaScript runtime requirement | Strong preference | Go/Rust preferred |
| 8 | Fast startup | Strong preference | Native binary favored |
| 9 | CI report output | Nice | JUnit XML/TAP/JSON |
| 10 | Active maintenance | Nice | Recent releases, issue activity |

---

## 3. Script-Derived Replacement Requirements

These requirements were extracted directly from `scripts/pa-e2e-test.sh` behavior.

| ID | Requirement | Why it exists | Script evidence |
|----|-------------|---------------|-----------------|
| RQ-01 | Unified HTTP + shell execution in one suite | Script interleaves admin API, engine calls, and shell diagnostics | `scripts/pa-e2e-test.sh:77`, `scripts/pa-e2e-test.sh:103`, `scripts/pa-e2e-test.sh:1193` |
| RQ-02 | Deterministic always-run teardown/finally | Containers/network must be removed even on failure | `scripts/pa-e2e-test.sh:200`, `scripts/pa-e2e-test.sh:205` |
| RQ-03 | Conditional branching (`if`/`else`) | OAuth/Web Session paths and skip behavior are branch-heavy | `scripts/pa-e2e-test.sh:670`, `scripts/pa-e2e-test.sh:1375`, `scripts/pa-e2e-test.sh:1543` |
| RQ-04 | Retry/loop/wait with explicit timeout | Wait for PingAccess and OIDC readiness | `scripts/pa-e2e-test.sh:143`, `scripts/pa-e2e-test.sh:337` |
| RQ-05 | Response extraction + cross-step templating | Create site/rule/app, then reuse IDs | `scripts/pa-e2e-test.sh:423`, `scripts/pa-e2e-test.sh:432`, `scripts/pa-e2e-test.sh:441` |
| RQ-06 | Fine HTTP control: custom headers, cookies, redirects, host rewrite, TLS skip | Host-based PA routing and OIDC callback flow depend on this | `scripts/pa-e2e-test.sh:119`, `scripts/pa-e2e-test.sh:936`, `scripts/pa-e2e-test.sh:974`, `scripts/pa-e2e-test.sh:1006` |
| RQ-07 | Soft-assert / best-effort assertion semantics | Some checks intentionally pass as informational outcomes | `scripts/pa-e2e-test.sh:1355`, `scripts/pa-e2e-test.sh:1444`, `scripts/pa-e2e-test.sh:1646` |
| RQ-08 | Continue-after-failure + aggregate summary | Script reports comprehensive totals, not first failure only | `scripts/pa-e2e-test.sh:55`, `scripts/pa-e2e-test.sh:1688` |
| RQ-09 | Command output assertions for logs and artifacts | Verifies loaded specs, plugin class version, SPI file, log lines | `scripts/pa-e2e-test.sh:1170`, `scripts/pa-e2e-test.sh:1192`, `scripts/pa-e2e-test.sh:1208`, `scripts/pa-e2e-test.sh:1341` |
| RQ-10 | Modular test composition | 24 tests across phases are too large for one flat file | `scripts/pa-e2e-test.sh:1120`, `scripts/pa-e2e-test.sh:1224`, `scripts/pa-e2e-test.sh:1369` |
| RQ-11 | CI-friendly machine-readable report output | Needed for GitHub Actions and regression visibility | Implied by overall E2E gate role (`FR-002-12`) |
| RQ-12 | Local-dev friendly execution model | Current flow runs on a developer machine without Kubernetes | `scripts/pa-e2e-test.sh:213`, `scripts/pa-e2e-test.sh:249`, `scripts/pa-e2e-test.sh:357` |
| RQ-13 | Pre-request and post-request transformation hooks | Need scriptable payload/header/query preparation and response-derived value shaping | Postman-like workflow requirement; partly mirrored by inline processing in `scripts/pa-e2e-test.sh:926`, `scripts/pa-e2e-test.sh:1426`, `scripts/pa-e2e-test.sh:1510` |
| RQ-14 | Multi-scope variables (request/local, collection/suite, environment, global) | Need explicit variable lifecycles across steps and suites, not only ad-hoc templates | Postman-like workflow requirement; current script uses process-level globals throughout |
| RQ-15 | Stateful cookie jar/session container | Automatically persist `Set-Cookie` responses and replay cookies to subsequent requests in-scope | Required for OIDC/web-session chains (currently hand-managed in `scripts/pa-e2e-test.sh:973`, `scripts/pa-e2e-test.sh:1104`) |
| RQ-16 | Programmatic cookie + arbitrary header mutation | Ability to set/remove/transform cookies and headers before/after requests via script/expression hooks | Needed for Host rewrites and dynamic auth/header shaping (`scripts/pa-e2e-test.sh:119`, `scripts/pa-e2e-test.sh:936`, `scripts/pa-e2e-test.sh:1158`) |
| RQ-17 | Multi-step redirect following with per-hop header/status inspection | OIDC auth code flow: 4+ chained redirects, each hop must be individually inspected for Location header, status code, and cookies before following; must support **controlled** redirect following (not automatic) | `scripts/pa-e2e-test.sh:974–1117` — `oidc_login()` does: GET PA (302) → extract Location → rewrite hostname → GET OIDC (302 or 200) → POST login form (302) → follow callback → extract cookie from jar |
| RQ-18 | Inline complex JSON/data processing beyond simple JSONPath | Some assertions require iterating arrays, filtering by predicate, extracting nested keys, computing set differences — more than `$.field` | `scripts/pa-e2e-test.sh:395–402` (iterate array, filter by field), `scripts/pa-e2e-test.sh:881–887` (find rootResource in items array), `scripts/pa-e2e-test.sh:1426–1431` (join list to string), `scripts/pa-e2e-test.sh:1607–1627` (compute L4 vs L1L2 key sets) |
| RQ-19 | Host-local filesystem artifact inspection | Some tests run against host-side files (not inside containers): JAR class version via `javap`, JAR size via `stat`, SPI file contents via `unzip -p` | `scripts/pa-e2e-test.sh:1193` (`javap` on local JAR), `scripts/pa-e2e-test.sh:1196` (`stat` on local JAR), `scripts/pa-e2e-test.sh:1209` (`unzip -p` on local JAR) |
| RQ-20 | Numeric/arithmetic comparisons and floating-point formatting | JAR size check requires byte→MB conversion via `bc` and numeric less-than comparison, not string equality | `scripts/pa-e2e-test.sh:1196–1205` (`bc` for float, `-lt` for integer comparison) |
| RQ-21 | Embedded test infrastructure (echo server) startup | The echo backend is an inline Python HTTP server started via `docker run ... python3 -c '...'` with path-specific routing behavior; any replacement must either keep this pattern or provide an equivalent fixture mechanism | `scripts/pa-e2e-test.sh:259–322` (60+ lines of inline Python echo server) |
| RQ-22 | Preflight validation and CLI argument parsing | Script validates prerequisites (Docker available, license file exists, image present, JAR exists) and supports `--skip-build` flag before any test runs | `scripts/pa-e2e-test.sh:62–67` (CLI args), `scripts/pa-e2e-test.sh:213–226` (preflight), `scripts/pa-e2e-test.sh:237–240` (JAR existence) |

### Additional governance guardrails

- Prefer non-JS runtime if possible (project preference)
- Avoid introducing infrastructure-heavy dependencies (e.g., mandatory Kubernetes)
- The echo server (RQ-21) will remain as a Docker container regardless of tool choice; this is infra, not test logic
- Preflight checks (RQ-22) likely remain in shell or `docker compose` — they are infrastructure gating, not test assertions

---

## 4. Candidate Tools (With Representative Snippets)

### 4.1 Venom (Go)

| Attribute | Value |
|-----------|-------|
| License | Apache-2.0 |
| Latest release checked | `v1.3.0` (2026-01-06) |
| Repo | <https://github.com/ovh/venom> |

**Strengths**: strong HTTP + exec + assertions + built-in report formats.
**Risk area**: less explicit control-flow/finally semantics than Runn.

Representative scenario snippet:

```yaml
name: pa-plugin-discovery
vars:
  pa_admin: https://localhost:19000/pa-admin-api/v3
  pa_user: administrator
  pa_password: 2Access

testcases:
  - name: discover-plugin-and-check-log
    steps:
      - type: http
        method: GET
        url: "{{.pa_admin}}/rules/descriptors/MessageTransform"
        basic_auth_user: "{{.pa_user}}"
        basic_auth_password: "{{.pa_password}}"
        skip_verify: true
        headers:
          X-XSRF-Header: PingAccess
        assertions:
          - result.statuscode ShouldEqual 200
          - result.bodyjson.className ShouldEqual io.messagexform.pingaccess.MessageTransformRule

      - type: exec
        script: docker exec pa-e2e-test grep -qF "Loaded profile: e2e-profile" /opt/out/instance/log/pingaccess.log
        assertions:
          - result.code ShouldEqual 0
```

---

### 4.2 Runn (Go)

| Attribute | Value |
|-----------|-------|
| License | MIT |
| Latest release checked | `v1.3.1` (2026-02-14) |
| Repo | <https://github.com/k1LoW/runn> |

**Strengths**: richer orchestration controls (`if`, `loop`, `defer`) in one runbook.
**Risk area**: no native JUnit XML output built-in.

Representative scenario snippet:

```yaml
desc: pa-setup-and-discovery

runners:
  pa:
    endpoint: https://localhost:19000/pa-admin-api/v3
    skipVerify: true
    basicAuth:
      username: administrator
      password: 2Access

steps:
  create_site:
    pa:
      /sites:
        post:
          headers:
            X-XSRF-Header: PingAccess
          body:
            name: Echo Backend
            targets: [pa-e2e-echo:8080]
            secure: false
    test: current.res.status == 200

  capture_site:
    bind:
      site_id: "{{ steps.create_site.res.body.id }}"

  check_profile_log:
    exec:
      command: docker exec pa-e2e-test grep -qF "Loaded profile: e2e-profile" /opt/out/instance/log/pingaccess.log
    test: current.res.exit_code == 0

  cleanup:
    defer:
      exec:
        command: docker rm -f pa-e2e-test pa-e2e-echo pa-e2e-oidc || true
```

---

### 4.3 Hurl (Rust)

| Attribute | Value |
|-----------|-------|
| License | Apache-2.0 |
| Latest release checked | `7.1.0` (2025-11-20) |
| Repo | <https://github.com/Orange-OpenSource/hurl> |

**Strengths**: best HTTP DSL and report output.
**Blocking gap**: no in-file shell execution/orchestration.

Representative scenario snippet (HTTP-only, requires external orchestrator):

```hurl
GET https://localhost:19000/pa-admin-api/v3/rules/descriptors/MessageTransform
[BasicAuth]
administrator:2Access
[Options]
insecure: true
[Asserts]
status == 200
jsonpath "$.className" == "io.messagexform.pingaccess.MessageTransformRule"
```

---

### 4.4 Karate DSL (JVM)

| Attribute | Value |
|-----------|-------|
| License | MIT (core) |
| Runtime | JVM (Java 17+) |
| Repo | <https://github.com/karatelabs/karate> |

**Strengths**: very powerful full-stack framework; supports command execution.
**Risk area**: heavier runtime and JS-based scripting in the DSL.

Representative scenario snippet:

```gherkin
Feature: PingAccess plugin discovery

Scenario: Verify plugin and log line
  * configure ssl = true
  * configure headers = { X-XSRF-Header: 'PingAccess' }
  Given url 'https://localhost:19000/pa-admin-api/v3/rules/descriptors/MessageTransform'
  And header Authorization = call read('classpath:basic-auth.js')
  When method get
  Then status 200
  And match response.className == 'io.messagexform.pingaccess.MessageTransformRule'
  * def out = karate.exec('docker exec pa-e2e-test grep -qF "Loaded profile: e2e-profile" /opt/out/instance/log/pingaccess.log; echo $?')
  * match out contains '0'
```

---

### 4.5 Tavern (Python/pytest)

| Attribute | Value |
|-----------|-------|
| License | MIT |
| Runtime | Python + pytest |
| Repo | <https://github.com/taverntesting/tavern> |

**Strengths**: pytest ecosystem and fixtures.
**Risk area**: shell orchestration needs Python hook code.

Representative scenario snippet:

```yaml
test_name: pa plugin discovery

stages:
  - name: discover plugin
    request:
      url: https://localhost:19000/pa-admin-api/v3/rules/descriptors/MessageTransform
      method: GET
      verify: false
      auth:
        - administrator
        - 2Access
      headers:
        X-XSRF-Header: PingAccess
    response:
      status_code: 200
      json:
        className: io.messagexform.pingaccess.MessageTransformRule

  - name: check profile log via hook
    request:
      url: http://localhost:1/placeholder
      method: GET
    response:
      status_code: 200
      verify_response_with:
        function: tests.helpers:assert_pa_log_contains_profile
```

---

### 4.6 Step CI (Node.js)

| Attribute | Value |
|-----------|-------|
| License | MPL-2.0 |
| Runtime | Node.js |
| Repo | <https://github.com/stepci/stepci> |

**Strengths**: modern API workflow syntax.
**Blocking gaps**: JS runtime + no first-class shell orchestration parity for this use case.

Representative scenario snippet:

```yaml
version: "1.1"
name: pa-plugin-discovery

tests:
  plugin-discovery:
    steps:
      - name: GET descriptor
        http:
          url: https://localhost:19000/pa-admin-api/v3/rules/descriptors/MessageTransform
          method: GET
          insecure: true
          auth:
            basic:
              username: administrator
              password: 2Access
          headers:
            X-XSRF-Header: PingAccess
        check:
          - status: 200
          - jsonpath: $.className
            equals: io.messagexform.pingaccess.MessageTransformRule
```

---

### 4.7 BATS-core (Bash)

| Attribute | Value |
|-----------|-------|
| License | MIT |
| Runtime | Bash |
| Repo | <https://github.com/bats-core/bats-core> |

**Strengths**: better test structure than raw shell.
**Risk area**: still mostly manual HTTP/json/flow control plumbing.

Representative scenario snippet:

```bash
#!/usr/bin/env bats

@test "plugin discovery" {
  run curl -sk -u administrator:2Access \
    -H 'X-XSRF-Header: PingAccess' \
    https://localhost:19000/pa-admin-api/v3/rules/descriptors/MessageTransform
  [ "$status" -eq 0 ]
  [[ "$output" == *'"className":"io.messagexform.pingaccess.MessageTransformRule"'* ]]
}

@test "profile log contains e2e-profile" {
  run docker exec pa-e2e-test grep -qF "Loaded profile: e2e-profile" /opt/out/instance/log/pingaccess.log
  [ "$status" -eq 0 ]
}
```

---

### 4.8 Testkube (Kubernetes)

| Attribute | Value |
|-----------|-------|
| License | MIT |
| Runtime model | Kubernetes operator/workflows |
| Repo | <https://github.com/kubeshop/testkube> |

**Strengths**: enterprise-grade test orchestration.
**Blocking gap**: mandatory Kubernetes footprint for local PA E2E replacement.

Representative scenario snippet (workflow-style):

```yaml
apiVersion: testworkflows.testkube.io/v1
kind: TestWorkflow
metadata:
  name: pa-plugin-discovery
spec:
  content:
    git:
      uri: https://example.invalid/repo.git
      paths: [tests]
  steps:
    - name: run-http-check
      run:
        image: curlimages/curl:latest
        shell: |
          curl -sk -u administrator:2Access \
            -H 'X-XSRF-Header: PingAccess' \
            https://localhost:19000/pa-admin-api/v3/rules/descriptors/MessageTransform
```

---

## 5. Requirement Coverage Matrix

Legend: `✅` = native support, `⚠️` = possible with custom glue/caveats, `❌` = missing/blocking.

| Requirement | Venom | Runn | Hurl | Karate | Tavern | Step CI | BATS | Testkube |
|-------------|:-----:|:----:|:----:|:------:|:------:|:-------:|:----:|:--------:|
| RQ-01 Unified HTTP + shell in one suite | ✅ | ✅ | ❌ | ✅ | ⚠️ | ❌ | ✅ | ⚠️ |
| RQ-02 Deterministic teardown/finally | ⚠️ | ✅ | ❌ | ⚠️ | ✅ | ❌ | ✅ | ✅ |
| RQ-03 Branching and skip logic | ⚠️ | ✅ | ❌ | ✅ | ✅ | ⚠️ | ✅ | ✅ |
| RQ-04 Retry/loop/timeouts | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ⚠️ | ⚠️ | ✅ |
| RQ-05 Extraction + templating | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ |
| RQ-06 Header/cookie/redirect/TLS control | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RQ-07 Soft-assert/best-effort checks | ⚠️ | ✅ | ⚠️ | ✅ | ✅ | ⚠️ | ⚠️ | ✅ |
| RQ-08 Continue and aggregate summary | ⚠️ | ✅ | ⚠️ | ✅ | ✅ | ⚠️ | ✅ | ✅ |
| RQ-09 Log/JAR command assertions | ✅ | ✅ | ❌ | ✅ | ⚠️ | ❌ | ✅ | ✅ |
| RQ-10 Modular composition/splitting | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ | ⚠️ | ✅ |
| RQ-11 CI machine-readable reports | ✅ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ |
| RQ-12 Local dev without heavy infra | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| RQ-13 Pre/post request transformation hooks | ⚠️ | ✅ | ⚠️ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ |
| RQ-14 Multi-scope variable hierarchy | ⚠️ | ⚠️ | ⚠️ | ✅ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
| RQ-15 Stateful cookie jar/session container | ⚠️ | ⚠️ | ✅ | ✅ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
| RQ-16 Programmatic cookie/header mutation | ⚠️ | ✅ | ⚠️ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ |
| RQ-17 Multi-step redirect w/ per-hop inspection | ⚠️ | ✅ | ⚠️ | ✅ | ⚠️ | ⚠️ | ✅ | ⚠️ |
| RQ-18 Complex JSON processing (filter, iterate, set diff) | ⚠️ | ✅ | ❌ | ✅ | ✅ | ⚠️ | ⚠️ | ⚠️ |
| RQ-19 Host-local artifact inspection | ✅ | ✅ | ❌ | ✅ | ⚠️ | ❌ | ✅ | ⚠️ |
| RQ-20 Numeric/arithmetic comparisons | ⚠️ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ⚠️ | ⚠️ |
| RQ-21 Embedded test infra fixture startup | ⚠️ | ⚠️ | ❌ | ⚠️ | ⚠️ | ❌ | ✅ | ✅ |
| RQ-22 Preflight validation + CLI args | ⚠️ | ⚠️ | ❌ | ⚠️ | ⚠️ | ❌ | ✅ | ⚠️ |

### Blocking conclusions from matrix

- **Hurl**: blocked for full replacement by `RQ-01`, `RQ-02`, `RQ-09`, `RQ-18`, `RQ-19`, `RQ-21`, `RQ-22`.
- **Step CI**: blocked by `RQ-01`, `RQ-09`, `RQ-19`, `RQ-21`, `RQ-22`, plus runtime/license preference mismatch.
- **Testkube**: blocked by `RQ-12` (Kubernetes required).
- **BATS**: not blocked, but only incremental improvement (still shell-heavy).
- **Strict Postman parity note (`RQ-14`)**: exact collection/environment/global variable semantics are not first-class in most native CLI tools; this usually needs project conventions or a thin wrapper layer.
- **Cookie/session parity note (`RQ-15`)**: if automatic cookie persistence and replay is mandatory, require a proof-of-capability spike before tool lock-in (especially for non-Karate options).
- **Complex JSON note (`RQ-18`)**: Runn's `expr` engine handles array filtering and arithmetic natively; Venom would fall back to `exec` + `jq`/`python3` for complex cases. This is a meaningful ergonomic gap.
- **Multi-step redirect note (`RQ-17`)**: The OIDC flow is the hardest single pattern to port. Runn's `if`/`loop` + `bind` + HTTP step chaining can express it declaratively; Venom would need an `exec` step wrapping `curl` with manual redirect control.
- **Infra fixtures (`RQ-21`, `RQ-22`)**: Both are best left in shell or `docker compose`. The tool evaluation should focus on test logic migration, not infra bootstrapping.

---

## 6. Updated Recommendation

### Primary recommendation: Runn

Runn is the best fit against the script-derived requirements because it provides
strong orchestration controls (especially `if`/`loop`/`defer`) while still handling
HTTP + shell execution and variable chaining in one runbook model.

Why it ranks first after deep script analysis:

1. Best match for branch-heavy OAuth/Web Session paths (`RQ-03`)
2. Best native story for deterministic cleanup/finalization (`RQ-02`)
3. Strong fit for chained PA provisioning + diagnostics (`RQ-01`, `RQ-05`, `RQ-09`)
4. Latest release recency is strong (`v1.3.1`, 2026-02-14)
5. Best non-JS fit for request/response transformation logic (`RQ-13`, `RQ-16`)

### Secondary recommendation: Venom

Venom remains a strong option and may be preferred if built-in report formats
(JUnit/TAP/HTML) are prioritized over orchestration ergonomics.

### If strict Postman-style variable scopes are non-negotiable

Karate is the closest out-of-the-box match for strict `RQ-14` + `RQ-15` + `RQ-16`
because it has stronger native scripting and session/cookie handling patterns.
Trade-off: JVM runtime and JS-heavy DSL.

### Not recommended as full replacement

- Hurl alone (excellent HTTP layer, insufficient orchestration)
- Step CI (same orchestration gap plus runtime/license preference mismatch)
- Testkube for local replacement (infra overhead)

---

## 7. Migration Path (Runn-first)

### Phase 0 — Requirement gate (prevent mid-integration surprises)
Create an acceptance checklist from `RQ-01..RQ-16` and require every migration PR
to show explicit pass/fail for each requirement.

### Phase 1 — Keep thin shell for infra only
Retain only Docker network/container lifecycle in shell or `docker compose`.
Move assertions/configuration logic out of shell.

### Phase 2 — Port PA Admin API provisioning into runbooks
Convert chained setup calls (virtual host/site/rules/apps/resources) to runbook steps
with extracted IDs and templating.

### Phase 3 — Port all assertions
Port API assertions, best-effort checks, and diagnostics (`docker exec`, log grep,
`javap`, SPI checks) into runbooks.

### Phase 4 — CI hardening
Add machine-readable output conversion for CI and enforce a deterministic failure summary
matching current pass/fail accounting.

Estimated end state:

- Shell orchestration reduced to thin infra bootstrap/teardown layer
- Main test logic moved to declarative runbooks
- Lower maintenance cost than current 1,701-line Bash script

---

## 8. Open Questions for Decision

1. Should teardown remain in a thin shell wrapper, or move to `docker compose` + runbooks?
2. Should we standardize on Runn now, or run a short spike comparing Runn vs Venom on `RQ-02` and `RQ-03` only?
3. Do we require native JUnit XML output, or accept conversion/post-processing for CI?
4. Should this framework decision be reused for standalone adapter E2E (Feature 004), or stay PingAccess-specific initially?

---

## 9. Sources

- Current script under analysis: `scripts/pa-e2e-test.sh`
- Existing research baseline: `docs/research/e2e-test-framework-research.md` (previous revision)
- Venom repository: <https://github.com/ovh/venom>
- Runn repository: <https://github.com/k1LoW/runn>
- Hurl repository: <https://github.com/Orange-OpenSource/hurl>
- Karate repository: <https://github.com/karatelabs/karate>
- Tavern repository: <https://github.com/taverntesting/tavern>
- Step CI repository: <https://github.com/stepci/stepci>
- BATS repository: <https://github.com/bats-core/bats-core>
- Testkube repository: <https://github.com/kubeshop/testkube>

Release recency checks performed on 2026-02-14:
- Venom `v1.3.0` (2026-01-06)
- Runn `v1.3.1` (2026-02-14)
- Hurl `7.1.0` (2025-11-20)
