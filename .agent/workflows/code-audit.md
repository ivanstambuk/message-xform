---
description: Read-only generated-code audit for architecture, concurrency, lifecycle, resilience, operability, security, dependency/supply-chain hygiene, test effectiveness, observability, data integrity, and performance signals
---

# /code-audit — Generated Code Audit

## Invocation

```
/code-audit all
/code-audit <module>
```

**Examples:**
- `/code-audit all` — audit all production modules.
- `/code-audit adapter-pingaccess` — audit a single module.

**Parameters:**
- `all` — all active Gradle modules that contain Java source (`src/main/java`).
- `<module>` — one concrete module directory name.
- Resolve `all` dynamically from `settings.gradle.kts` includes (do not hardcode module names).

---

## Operating Constraints

1. **Read-only workflow:** do not modify code/docs/specs/tasks as part of audit.
2. **Informational only:** rank findings by severity; do not block progress.
3. **One report only:** produce a detailed report file (no mandatory chat scorecard).
4. **Centralized output:** all audit reports live under `audit-reports/`.
5. **Deterministic evidence:** every finding must include reproducible evidence.
6. **Root-cause first:** do not emit duplicate findings for the same underlying issue.
7. **Signal provenance:** classify findings as `new`, `existing`, or `unknown`.
8. **No speculative criticals:** `Critical` requires concrete, reproducible evidence.
9. **Bounded execution:** timebox long-running checks; record timed-out checks in "Cannot Validate".
10. **Scoped diagnostics:** when unrelated module failures block scoped checks, rerun minimal scoped commands and classify blockers separately.
11. **Reproducible context:** capture environment snapshot (commit/toolchain) in report metadata.

---

## Audit Process

### Phase 0 — Load Context

// turbo
Read:
- `AGENTS.md`
- `docs/operations/quality-gate.md`
- `docs/architecture/features/009/spec.md` (for toolchain guardrail alignment)
- `build.gradle.kts`
- `gradle/libs.versions.toml`

Goal: align findings with current project guardrails and toolchain policy.

### Phase 0a — Preflight & Tool Availability

// turbo
Before running audit checks, verify:
1. `git`, `rg`, and `./gradlew` are available.
2. Workspace is readable; report path `audit-reports/` is writable.

If a tool is missing:
- continue with available checks,
- mark the skipped checks explicitly in the report's execution matrix,
- do not infer findings for skipped checks.

### Phase 0b — Execution Budget & Timeouts

Default command budgets:
- `rg`/text scans: 30s per command
- Gradle single-task checks: 5m per command
- Gradle test tasks: 10m per command

Rules:
1. If a check times out, mark as `Timed out` and move the gap to "Cannot Validate".
2. Do not silently retry more than once.
3. Prefer deterministic, narrow reruns over broad reruns.

### Phase 0c — Environment Snapshot

Capture once per audit run:
1. Git commit SHA (`git rev-parse HEAD`)
2. `git status --short` summary
3. Java runtime (`java -version`)
4. Gradle runtime (`./gradlew --version`)
5. OS/runtime context (`uname -a`)

If any snapshot command fails, record the gap in "Cannot Validate (Gaps)".

### Phase 1 — Resolve Audit Scope

// turbo
1. Determine target:
   - `all` -> all active modules discovered from `settings.gradle.kts` includes,
     filtered to modules with `src/main/java`
   - `<module>` -> that module only
2. Validate module target against `settings.gradle.kts` includes. Unknown module -> stop with clear error.
3. Build file sets:
   - Production: `<module>/src/main/java/**`
   - Tests: `<module>/src/test/java/**`
   - Build files: `<module>/build.gradle.kts`, root `build.gradle.kts`, version catalog
4. Exclude non-signal paths: `.gradle/`, `build/`, `out/`, `.idea/`, `.agent/session/`,
   `.sdk-decompile/`, `binaries/`, `docs/`, `audit-reports/`.
5. Normalize and deduplicate file lists (stable sorted order).
6. If resolved scope is empty, write a report with "No auditable code in scope" and stop.
7. Record exact scope in report metadata.

### Phase 2 — Baseline Build Signals (Non-Mutating)

// turbo
Run only read-only checks; never use mutating tasks like `spotlessApply`.

Recommended commands:

```bash
# Whole-repo baseline
./gradlew --no-daemon spotlessCheck

# Per module baseline (repeat for each module in scope)
./gradlew --no-daemon :<module>:compileJava :<module>:compileTestJava
./gradlew --no-daemon :<module>:test
./gradlew --no-daemon :<module>:dependencies --configuration compileClasspath
```

Scoped fallback (when unrelated failures block signal collection):

```bash
./gradlew --no-daemon --continue :<module>:spotlessCheck :<module>:compileJava :<module>:compileTestJava :<module>:test
```

Capture failures/warnings as findings when relevant.

Classification guidance for baseline failures:
- Failure fully inside audited scope -> finding (`new` or `existing` based on blame/age).
- Failure clearly outside scope -> record as contextual note, not a scoped finding.
- Unable to determine provenance -> finding with provenance `unknown`.
- Unrelated module failure that blocks scoped checks -> add to "Cannot Validate (Gaps)" with blocker path and rerun evidence.

Provenance method (deterministic):
- `new`: evidence location is in uncommitted/staged/untracked changes, or last touched by `HEAD`.
- `existing`: evidence location was last touched before `HEAD`.
- `unknown`: provenance cannot be mapped reliably to a line/symbol.

### Phase 2a — Finding Quality Bar

Before recording a finding, verify all of the following:
1. **Evidence present:** command output or concrete symbol/location.
2. **Reproducible:** include one command that reproduces the signal.
3. **Actionable:** recommendation is specific enough for a follow-up task.
4. **Confidence:** mark as `HIGH` or `MEDIUM`.
5. **False-positive note:** include when heuristic signals are uncertain.
6. **Not style-noise:** if `spotlessCheck` passes, avoid emitting pure formatting findings.
7. **Assignable:** include likely owner and rough fix cost (`S/M/L`).

### Phase 2b — Root-Cause Grouping

Before finalizing findings:
1. Group symptom-level signals under one root-cause finding when they share the same source.
2. Keep one canonical finding with multiple affected locations.
3. Avoid emitting N nearly identical findings for the same issue class.
4. If one root cause spans multiple categories, assign:
   - one **primary category** (owning/root cause),
   - optional related categories list (`related: [ARCH, RES, ...]`).

### Phase 2c — Noise Control

To keep reports actionable:
1. Merge repetitive low-severity signals into one grouped finding per check ID.
2. Cap low-severity listing to the top 10 most actionable entries; summarize the rest.
3. Prefer high-confidence findings over broad speculative scans.

### Phase 2d — Prioritization Model

Compute a priority score for each finding to drive the **Critical Path (Top 3)**.

Scoring:
- `SeverityWeight`: Critical=4, High=3, Medium=2, Low=1
- `BlastRadius`: 1 (localized), 2 (module-wide), 3 (cross-module/runtime-wide)
- `Exploitability/TriggerLikelihood`: 1 (rare/hard), 2 (plausible), 3 (easy/common)
- `ConfidenceFactor`: HIGH=1.0, MEDIUM=0.7

Formula:
- `PriorityScore = (SeverityWeight * BlastRadius * Exploitability) * ConfidenceFactor`

Rules:
1. Sort findings by `PriorityScore` descending.
2. If scores tie, prefer newer findings (`provenance: new`) over existing.
3. Critical Path must contain at most 3 findings and at least 1 when any High/Critical exists.
4. Avoid choosing two Critical Path items with identical root cause unless no alternative exists.
5. If no High/Critical findings exist, set Critical Path to `None` explicitly.

### Phase 2e — Execution Matrix Completeness

Before finalizing report:
1. Every defined check ID (`CQ-*`, `ARCH-*`, ..., `OPR-*`) must appear in the execution matrix.
2. Allowed statuses: `Executed`, `Skipped`, `N/A`, `Timed out`, `Not Run`, `Blocked`.
3. `Skipped`, `Not Run`, `Blocked`, and `Timed out` must include a reason.

### Phase 2f — Cross-Category Classification Boundaries

When a finding appears to match multiple categories, choose **one primary category**
using this precedence and record others in `Related categories`.

Precedence (highest first):
1. `ARCH` — structural boundary/layering/dependency-direction violations
2. `API` — externally visible contract/compatibility/versioning violations
3. `RES` — runtime failure-path behavior and degradation semantics
4. `OPR` — operator-facing readiness/rollback/remediation operability
5. `SEC` — exploitability/security exposure root cause
6. `DATA` — data correctness/integrity root cause
7. `CONC` / `LIFE` — threading/lifecycle resource correctness root cause
8. `CQ` — local implementation hygiene/maintainability
9. `DEP` / `SUP` — dependency and supply-chain governance
10. `TQ` / `TE` — test reliability/effectiveness
11. `PERF` — performance-specific root cause

Pair-specific rules:
1. `ARCH` vs `API`:
   - Choose `ARCH` when contract issues are caused by architectural boundary leakage or layering errors.
   - Choose `API` when the issue is a direct public contract break independent of architecture shape.
2. `RES` vs `OPR`:
   - Choose `RES` for runtime behavior under failure (timeouts/retries/fallback semantics).
   - Choose `OPR` for operator control/visibility/readiness/rollback deficiencies.
3. `CQ` vs `ARCH`:
   - Choose `ARCH` for cross-module/layer concerns.
   - Choose `CQ` for single-component code quality concerns.
4. `TQ` vs `TE`:
   - Use the dedicated boundary in Phase 15 (do not duplicate unless risk classes are distinct).

Duplication rule:
- Do not create two findings for the same location unless each finding has a
  distinct risk outcome. If duplicated intentionally, include a "distinct-risk"
  justification in both findings.

### Phase 3 — Code Quality & Design Signals

Evaluate maintainability and engineering quality in audited code.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| CQ-01 | Reflection usage (`Class.forName`, `setAccessible`, `getDeclared*`) | Critical |
| CQ-02 | Error swallowing / broad catch without actionable logging | High |
| CQ-03 | Large/complex methods, high branching, duplicated logic | Medium |
| CQ-04 | API contract drift risks (null handling, inconsistent invariants) | High |
| CQ-05 | TODO/FIXME/HACK markers in production paths | Low |
| CQ-06 | Inconsistent naming/layering vs module boundaries | Medium |
| CQ-07 | Mutable static/shared state in request-processing paths | High |
| CQ-08 | Copy-paste duplication across adapters/tests without shared helper | Medium |

Evidence commands (examples):

```bash
rg -n "Class\\.forName|setAccessible\\(|getDeclared(Field|Method|Constructor)" <scope>
rg -n "catch \\(Exception|catch \\(Throwable" <scope>
rg -n "TODO|FIXME|HACK" <scope>/src/main/java
rg -n "static\\s+(?!final)" <scope>/src/main/java
```

### Phase 4 — Architecture Signals

Evaluate architectural quality explicitly (beyond code style/quality).

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| ARCH-01 | Boundary leaks: core depending on adapter or gateway SDK types | Critical |
| ARCH-02 | Direction violations: adapter/core dependency flow breaks intended layering | High |
| ARCH-03 | Cyclic package/module dependencies | High |
| ARCH-04 | Over-coupled classes (high fan-in/fan-out, god classes) | Medium |
| ARCH-05 | Internal package misuse from external modules (`*.internal.*`) | High |
| ARCH-06 | Contract bypass: direct low-level wiring where stable ports/SPI exist | Medium |
| ARCH-07 | Cross-module duplication that should be extracted/shared | Medium |
| ARCH-08 | Architecture tests missing for newly introduced boundaries | Medium |

Evidence commands (examples):

```bash
rg -n "import com\\.pingidentity\\.|import jakarta\\.servlet|import io\\.javalin" core/src/main/java
rg -n "import io\\.messagexform\\.(adapter|standalone|pingaccess)" core/src/main/java
rg -n "import io\\.messagexform\\.core\\..*internal" adapter-*/src/main/java
rg -n "class .*Adapter|class .*Rule|class .*Engine" <scope>/src/main/java
./gradlew --no-daemon test --tests "*ArchUnit*" --tests "*Architecture*"
```

Architecture recommendation rule:
- Every `High`/`Critical` architecture finding must include:
  1. current state (evidence),
  2. target architecture shape,
  3. minimal migration path (incremental, low-risk steps).

ARCH vs API boundary (mandatory):
1. Use `ARCH-*` for structural layering/module-boundary and dependency-direction issues.
2. Use `API-*` for externally observable contract compatibility issues.
3. If a public API break is caused by an architecture leak, classify as `ARCH` and add `API` to `Related categories`.

### Phase 5 — Concurrency Signals

Evaluate thread-safety and concurrent execution risks.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| CONC-01 | Shared mutable state on request/response paths | High |
| CONC-02 | Non-thread-safe collections used across threads | High |
| CONC-03 | Unsafe lazy init/publication without synchronization | High |
| CONC-04 | Locking risks (deadlock-prone ordering, oversized synchronized scopes) | Medium |
| CONC-05 | Atomicity gaps (check-then-act race windows) | Medium |

Evidence commands (examples):

```bash
rg -n "static\\s+(?!final)|volatile\\s|synchronized\\s*\\(" <scope>/src/main/java
rg -n "HashMap|ArrayList|LinkedList" <scope>/src/main/java
rg -n "CompletableFuture|ExecutorService|Thread\\(" <scope>/src/main/java
```

### Phase 6 — Resource Lifecycle Signals

Evaluate lifecycle and cleanup correctness.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| LIFE-01 | Unclosed streams/files/sockets/clients | High |
| LIFE-02 | Scheduler/executor lifecycle leaks | High |
| LIFE-03 | Missing shutdown hooks / `@PreDestroy` gaps | Medium |
| LIFE-04 | Resource ownership ambiguity (who opens/closes) | Medium |

Evidence commands (examples):

```bash
rg -n "new FileInputStream|new FileOutputStream|Files\\.newInputStream|Files\\.newOutputStream" <scope>/src/main/java
rg -n "Executors\\.|ScheduledExecutorService|HttpClient\\.|Closeable|AutoCloseable" <scope>/src/main/java
rg -n "@PreDestroy|close\\(|shutdown\\(|shutdownNow\\(" <scope>/src/main/java
```

### Phase 7 — Resilience Signals

Evaluate failure handling and degradation behavior.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| RES-01 | Missing timeout controls on I/O and remote calls | High |
| RES-02 | Retry behavior without bounds/backoff | High |
| RES-03 | Failure isolation gaps (one failure cascades system-wide) | High |
| RES-04 | Fallback behavior mismatched with contract | Medium |

Evidence commands (examples):

```bash
rg -n "timeout|Duration|connectTimeout|readTimeout" <scope>/src/main/java
rg -n "retry|backoff|attempt" <scope>/src/main/java
rg -n "catch \\(.*\\) \\{|throw new|return" <scope>/src/main/java
```

RES vs OPR boundary (mandatory):
1. Use `RES-*` for runtime failure behavior correctness.
2. Use `OPR-*` for operator-facing readiness/remediation/rollback controls.
3. If both apply, pick the primary root cause per Phase 2f precedence.

### Phase 8 — API / Contract Evolution Signals

Evaluate compatibility and contract discipline.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| API-01 | Breaking signature changes without migration path | High |
| API-02 | Inconsistent nullability/validation contracts | High |
| API-03 | Public API drift from documented spec/terminology | Medium |
| API-04 | Versioning discipline gaps (silent behavior change) | Medium |

Evidence commands (examples):

```bash
rg -n "public class|public interface|public record|public .*\\(" <scope>/src/main/java
rg -n "@Nullable|@NotNull|requireNonNull|Objects\\.requireNonNull" <scope>/src/main/java
rg -n "deprecated|@Deprecated" <scope>/src/main/java
```

### Phase 9 — Observability Signals

Evaluate logs/metrics/tracing quality and operational usefulness.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| OBS-01 | Missing error context in logs (status, path, reason, correlation) | Medium |
| OBS-02 | Sensitive data leakage risk in logs | High |
| OBS-03 | Metrics cardinality explosion risk | Medium |
| OBS-04 | Missing telemetry on failure paths | Medium |

Evidence commands (examples):

```bash
rg -n "LOG\\.(info|warn|error|debug|trace)" <scope>/src/main/java
rg -n "metric|counter|timer|histogram|LongAdder" <scope>/src/main/java
rg -n "exception|error|failed|failure" <scope>/src/main/java
```

### Phase 10 — Data Integrity Signals

Evaluate correctness of data handling and transformation boundaries.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| DATA-01 | Missing input validation at trust boundaries | High |
| DATA-02 | Silent truncation/normalization without explicit contract | Medium |
| DATA-03 | Lossy type conversion risks | Medium |
| DATA-04 | Schema/version mismatch handling gaps | High |

Evidence commands (examples):

```bash
rg -n "validate|validator|schema|parse|deserialize|serialize" <scope>/src/main/java
rg -n "toString\\(|asText\\(|intValue\\(|longValue\\(|doubleValue\\(" <scope>/src/main/java
rg -n "catch \\(.*\\) \\{\\s*return|catch \\(.*\\) \\{\\s*continue" <scope>/src/main/java
```

### Phase 11 — Security Signals

Evaluate common secure-coding risks.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| SEC-01 | Hardcoded secrets/tokens/passwords/keys | Critical |
| SEC-02 | Command execution/process invocation from variable input | High |
| SEC-03 | Path traversal / unsafe file path composition | High |
| SEC-04 | Unsafe deserialization / dynamic code loading | High |
| SEC-05 | TLS/hostname verification weakening, insecure crypto defaults | Medium |
| SEC-06 | Sensitive data over-logging risk | Medium |
| SEC-07 | SSRF risk (outbound URL/host built from untrusted input) | High |

Evidence commands (examples):

```bash
rg -n "(password|secret|token|apikey|api_key|private_key)\\s*[:=]" <scope>
rg -n "Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder\\(" <scope>
rg -n "ObjectInputStream|readObject\\(|URLClassLoader|ScriptEngineManager" <scope>
rg -n "disableHostnameVerification|TrustAll|X509TrustManager|setHostnameVerifier" <scope>
rg -n "HttpClient|HttpURLConnection|URI\\.create|new URI\\(" <scope>/src/main/java
```

Secret-scan false-positive control:
- Treat obvious placeholders in tests/docs (e.g., `example`, `dummy`, `test`, `changeme`) as non-findings.
- If uncertain, keep as `Low` with confidence `MEDIUM`, not `Critical`.

### Phase 12 — Dependency Hygiene Signals

Evaluate dependency consistency and maintenance risk.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| DEP-01 | Version drift between build files and version catalog | Medium |
| DEP-02 | Duplicate/conflicting dependency paths in classpath graph | Medium |
| DEP-03 | Unused or unjustified dependencies in module scope | Low |
| DEP-04 | Shadow packaging/excludes consistency with module intent | Medium |
| DEP-05 | Dependency declared outside version catalog without explicit rationale | Medium |

Use:

```bash
./gradlew --no-daemon :<module>:dependencies --configuration compileClasspath
./gradlew --no-daemon :<module>:dependencies --configuration testCompileClasspath
```

### Phase 13 — Supply-Chain & Compliance Signals

Evaluate supply-chain and policy/compliance risks.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| SUP-01 | Known vulnerable dependencies (CVE/advisory evidence) | High |
| SUP-02 | License compliance risk (unknown/incompatible licenses) | Medium |
| SUP-03 | Banned/unapproved dependency usage | High |
| SUP-04 | Unpinned tooling/runtime versions in critical paths | Medium |

Evidence commands (examples):

```bash
./gradlew --no-daemon :<module>:dependencies --configuration runtimeClasspath
rg -n "implementation\\(|api\\(|compileOnly\\(" <module>/build.gradle.kts build.gradle.kts
rg -n "version|toolchain|java\\s*\\{" build.gradle.kts gradle/libs.versions.toml
```

### Phase 14 — Test Quality Signals

Evaluate test-suite reliability, stability, and maintainability.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| TQ-01 | Missing tests for audited production code paths | High |
| TQ-02 | Reliability anti-patterns (time sleeps, shared mutable fixtures, order coupling) | Medium |
| TQ-03 | Disabled/ignored tests without rationale | Medium |
| TQ-04 | Test harness/config assumptions that make tests environment-fragile | Medium |
| TQ-05 | Missing failure-branch tests for error handling | High |
| TQ-06 | Low-diagnostic assertions (assertions too weak to localize failures) | Medium |

Evidence commands (examples):

```bash
rg -n "@Disabled|@Ignore|Thread\\.sleep\\(" <scope>/src/test/java
rg -n "static\\s+(?!final)|new Random\\(|Math\\.random\\(|Instant\\.now\\(|LocalDateTime\\.now\\(" <scope>/src/test/java
rg -n "assertThat\\(.*\\)\\.isNotNull\\(\\)|assertTrue\\(|assertFalse\\(" <scope>/src/test/java
```

### Phase 15 — Test Effectiveness Signals

Evaluate whether tests are behavior-revealing and resistant to regressions.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| TE-01 | Contract/integration gaps around externally visible behavior | High |
| TE-02 | Interaction-only tests that do not verify externally observable outcome | Medium |
| TE-03 | Missing negative/edge cases for critical logic | High |
| TE-04 | Test intent/assertion mismatch (test claims behavior it does not actually verify) | Medium |

Evidence commands (examples):

```bash
rg -n "@Nested|@ParameterizedTest|@MethodSource|@CsvSource" <scope>/src/test/java
rg -n "WireMock|MockWebServer|Testcontainers|integration|contract" <scope>/src/test/java
rg -n "verify\\(|times\\(" <scope>/src/test/java
rg -n "assertThat\\(|assertThrows\\(" <scope>/src/test/java
```

TQ vs TE classification boundary (mandatory):
1. Use `TQ-*` for test reliability/execution quality/maintainability risks.
2. Use `TE-*` for behavioral defect-detection power and contract coverage risks.
3. If one issue fits both, pick the primary root cause category and reference the other in `Related categories`.
4. Do not emit both `TQ-*` and `TE-*` findings for the same code location unless risks are genuinely distinct.

### Phase 16 — Lightweight Performance Signals

Focus on pragmatic heuristics (not full benchmarking).

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| PERF-01 | Blocking I/O on hot request/response paths | High |
| PERF-02 | Repeated heavyweight object creation in loops/hot paths | Medium |
| PERF-03 | Unbounded in-memory collections/caches | Medium |
| PERF-04 | Excessive payload copy/serialization churn | Medium |
| PERF-05 | Regex compilation or expensive parsing repeated in hot paths | Medium |

Notes:
- Keep this phase heuristic and evidence-based.
- Do not claim latency regressions without measured evidence.

### Phase 17 — Operability Signals

Evaluate production operability/readiness traits.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| OPR-01 | Startup fail-fast validation for critical config | High |
| OPR-02 | Health/readiness semantics cover degraded dependencies | Medium |
| OPR-03 | Error messages/logs include operator-actionable remediation hints | Medium |
| OPR-04 | Safe rollback/feature-toggle path exists for risky behavior | Medium |

Evidence commands (examples):

```bash
rg -n "health|readiness|liveness|startup|bootstrap|validate" <scope>/src/main/java
rg -n "feature flag|toggle|rollback|fallback|degrade" <scope>/src/main/java
rg -n "remediation|action|retry|check configuration" <scope>/src/main/java
```

### Phase 18 — Guardrail Recommendation Synthesis

Convert findings into preventive controls.

For each High/Critical finding, recommend one or more:
1. `AGENTS.md` rule update
2. Workflow check update (`/audit`, `/init`, `/retro`, `/code-audit`)
3. Spec/plan/tasks template guardrail
4. CI non-blocking signal (reporting-only)
5. Test-template strengthening for failure-path coverage

Recommendations must be concrete and directly tied to findings.
Each recommendation must include:
1. target artifact path,
2. linked finding/check IDs,
3. measurable acceptance signal.
At least one recommendation must exist for each Critical Path finding.

---

## Output — Detailed Report File Only

Write the report to:

```
audit-reports/code-audit-<target>-<YYYYMMDD-HHMMSS>.md
```

Examples:
- `audit-reports/code-audit-all-20260215-103000.md`
- `audit-reports/code-audit-adapter-pingaccess-20260215-105901.md`

`audit-reports/` is gitignored and shared with `/audit` findings.

Owner assignment heuristics (default):

| Path pattern | Owner |
|--------------|-------|
| `core/**` | `core` |
| `adapter-standalone/**` | `adapter-standalone` |
| `adapter-pingaccess/**` | `adapter-pingaccess` |
| `adapter-*/**` | `adapter-<name>` |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle/**` | `build/tooling` |
| `AGENTS.md`, `.agent/workflows/**`, `docs/**` | `docs/process` |

If ambiguous, keep owner as `unknown` and note in "Cannot Validate (Gaps)".

### Required report template

````markdown
# Code Audit — <target>

> ⚠️ Ephemeral artifact (gitignored). Informational only.

## Metadata
- Date: <ISO timestamp>
- Target: <all|module>
- Commit: <git sha>
- Workspace state: <clean|dirty + short summary>
- Java: <version string>
- Gradle: <version string>
- Host: <uname summary>
- Resolved modules: <explicit list>
- Scope paths: <explicit list>
- Commands executed: <explicit list>
- Excluded paths: <explicit list>

## Delta vs Previous Code Audit (Optional)
- Previous report: `<path or none>`
- New findings: <count>
- Regressed findings (severity increased): <count>
- Resolved findings (present previously, absent now): <count>
- Selection rule: choose latest report with matching target (`all` or same module) when available.

## Findings by Severity

Finding ID format:
- Use `<CATEGORY>-<NNN>` (e.g., `SEC-001`, `ARCH-002`, `CONC-003`), not a global counter.

## Critical Path (Top 3)
1. <finding id> — <why this is top priority now>
2. <finding id> — <why this is top priority now>
3. <finding id> — <why this is top priority now>
If none: `None (no High/Critical findings)`.

### Critical
#### SEC-001 — <title>
- Category: ARCH | CQ | CONC | LIFE | RES | API | OBS | DATA | SEC | DEP | SUP | TQ | TE | PERF | OPR
- Related categories: [optional list]
- Primary classification rationale: <one sentence why this category is primary>
- Distinct-risk justification: <required only if same location appears in another finding>
- Check ID: <e.g., SEC-03>
- Location: `<path>:<line>`
- Evidence: `<symbol/signature/command output excerpt>`
- Repro command: `<exact command>`
- Risk: <impact in practical terms>
- Recommendation: <concrete remediation path>
- Owner: <core | adapter-standalone | adapter-pingaccess | adapter-<name> | build/tooling | docs/process | unknown>
- Fix Cost: S | M | L
- Blast Radius: 1 | 2 | 3
- Exploitability/TriggerLikelihood: 1 | 2 | 3
- Priority Score: <numeric score>
- Confidence: HIGH | MEDIUM
- Provenance: new | existing | unknown
- False-positive note: <optional>

### High
...

### Medium
...

### Low
...

## Check Execution Matrix
| Check ID | Status | Notes |
|----------|--------|-------|
| CQ-01 | Executed / Skipped / N/A / Timed out / Not Run / Blocked | <reason or short result> |

## Cannot Validate (Gaps)
- <check or claim that could not be validated>
- Reason: <missing tool/data/access/time>
- Impact: <what this uncertainty means for confidence>
- Next step to validate: <specific command or prerequisite>

## Suppressed / Collapsed Signals
- <optional list of intentionally collapsed low-value signals with reason>
- Format: `<check-id> — <suppressed count> — <reason>`

## Passed Checks
- <check id> — <short note>

## Guardrail Recommendations
1. <guardrail proposal tied to finding IDs>
2. <guardrail proposal tied to finding IDs>

## Suggested Follow-up Work Items
1. <spec/plan/task/implementation follow-up item>
2. <optional additional item>
````

---

## Severity Calibration

| Severity | Meaning |
|----------|---------|
| **Critical** | Immediate security or correctness risk; high confidence, concrete evidence. |
| **High** | Strong risk to reliability, security, or maintainability; should be prioritized soon. |
| **Medium** | Important quality gap; manageable short-term risk. |
| **Low** | Hygiene or polish issue with limited immediate impact. |

Notes:
- Use `Critical` sparingly; require concrete exploitability/correctness evidence.
- Heuristic-only signals without hard evidence should not exceed `High`.
- Use `High` instead of `Critical` when exploitability is plausible but unproven.

---

## Post-Audit Rules

1. Do not auto-fix findings in this workflow.
2. Do not update specs/plans/tasks as part of this workflow.
3. Present report path and top findings to the user.
4. Wait for explicit follow-up direction before making changes elsewhere.
5. Do not commit the generated report (`audit-reports/` is ephemeral and gitignored).
6. **Auto-cleanup:** Once **all** action items from the active code-audit report are
   resolved and verified, **immediately delete the report file** from
   `audit-reports/` (do not ask for confirmation). Keep the file only while work
   remains open.

---

## Relationship to /audit

- `/audit` = documentation/spec conformance.
- `/code-audit` = generated code architecture/quality/concurrency/lifecycle/resilience/operability/security/dependency/supply-chain/observability/data/test/performance signals.
- They are separate by design and can be run independently.
