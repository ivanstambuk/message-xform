---
description: Read-only generated-code audit for architecture, concurrency, lifecycle, resilience, security, dependency/supply-chain hygiene, test effectiveness, observability, data integrity, and performance signals
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

### Phase 0.5 — Preflight & Tool Availability

// turbo
Before running audit checks, verify:
1. `git`, `rg`, and `./gradlew` are available.
2. Workspace is readable; report path `audit-reports/` is writable.

If a tool is missing:
- continue with available checks,
- mark the skipped checks explicitly in the report's execution matrix,
- do not infer findings for skipped checks.

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

Capture failures/warnings as findings when relevant.

Classification guidance for baseline failures:
- Failure fully inside audited scope -> finding (`new` or `existing` based on blame/age).
- Failure clearly outside scope -> record as contextual note, not a scoped finding.
- Unable to determine provenance -> finding with provenance `unknown`.

Provenance method (deterministic):
- `new`: evidence location is in uncommitted/staged/untracked changes, or last touched by `HEAD`.
- `existing`: evidence location was last touched before `HEAD`.
- `unknown`: provenance cannot be mapped reliably to a line/symbol.

### Phase 2.5 — Finding Quality Bar

Before recording a finding, verify all of the following:
1. **Evidence present:** command output or concrete symbol/location.
2. **Reproducible:** include one command that reproduces the signal.
3. **Actionable:** recommendation is specific enough for a follow-up task.
4. **Confidence:** mark as `HIGH` or `MEDIUM`.
5. **False-positive note:** include when heuristic signals are uncertain.
6. **Not style-noise:** if `spotlessCheck` passes, avoid emitting pure formatting findings.
7. **Assignable:** include likely owner and rough fix cost (`S/M/L`).

### Phase 2.6 — Root-Cause Grouping

Before finalizing findings:
1. Group symptom-level signals under one root-cause finding when they share the same source.
2. Keep one canonical finding with multiple affected locations.
3. Avoid emitting N nearly identical findings for the same issue class.

### Phase 2.7 — Noise Control

To keep reports actionable:
1. Merge repetitive low-severity signals into one grouped finding per check ID.
2. Cap low-severity listing to the top 10 most actionable entries; summarize the rest.
3. Prefer high-confidence findings over broad speculative scans.

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

Evaluate whether tests are strong enough to protect behavior.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| TQ-01 | Missing tests for audited production code paths | High |
| TQ-02 | Weak assertions (asserting only non-null / no behavior) | Medium |
| TQ-03 | Flakiness signals (`Thread.sleep`, timing races, order dependence) | Medium |
| TQ-04 | Disabled/ignored tests without rationale | Medium |
| TQ-05 | Missing failure-branch tests for error handling | High |
| TQ-06 | Over-mocked tests that assert interactions but not outcomes | Medium |

Evidence commands (examples):

```bash
rg -n "@Disabled|@Ignore|Thread\\.sleep\\(" <scope>/src/test/java
rg -n "assertThat\\(.*\\)\\.isNotNull\\(\\)" <scope>/src/test/java
rg -n "verify\\(|times\\(" <scope>/src/test/java
```

### Phase 15 — Test Effectiveness Signals

Evaluate whether tests are behavior-revealing and resistant to regressions.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| TE-01 | Contract/integration gaps around externally visible behavior | High |
| TE-02 | Assertions that do not check business outcome | Medium |
| TE-03 | Missing negative/edge cases for critical logic | High |
| TE-04 | Excessive fixture complexity hiding intent | Low |

Evidence commands (examples):

```bash
rg -n "@Nested|@ParameterizedTest|@MethodSource|@CsvSource" <scope>/src/test/java
rg -n "assertThat\\(|assertThrows\\(|verify\\(" <scope>/src/test/java
```

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

### Phase 17 — Guardrail Recommendation Synthesis

Convert findings into preventive controls.

For each High/Critical finding, recommend one or more:
1. `AGENTS.md` rule update
2. Workflow check update (`/audit`, `/init`, `/retro`, `/code-audit`)
3. Spec/plan/tasks template guardrail
4. CI non-blocking signal (reporting-only)
5. Test-template strengthening for failure-path coverage

Recommendations must be concrete and directly tied to findings.

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

### Required report template

````markdown
# Code Audit — <target>

> ⚠️ Ephemeral artifact (gitignored). Informational only.

## Metadata
- Date: <ISO timestamp>
- Target: <all|module>
- Resolved modules: <explicit list>
- Scope paths: <explicit list>
- Commands executed: <explicit list>
- Excluded paths: <explicit list>

## Findings by Severity

Finding ID format:
- Use `<CATEGORY>-<NNN>` (e.g., `SEC-001`, `ARCH-002`, `CONC-003`), not a global counter.

## Critical Path (Top 3)
1. <finding id> — <why this is top priority now>
2. <finding id> — <why this is top priority now>
3. <finding id> — <why this is top priority now>

### Critical
#### SEC-001 — <title>
- Category: ARCH | CQ | CONC | LIFE | RES | API | OBS | DATA | SEC | DEP | SUP | TQ | TE | PERF
- Check ID: <e.g., SEC-03>
- Location: `<path>:<line>`
- Evidence: `<symbol/signature/command output excerpt>`
- Repro command: `<exact command>`
- Risk: <impact in practical terms>
- Recommendation: <concrete remediation path>
- Owner: <core | adapter-standalone | adapter-pingaccess | build/tooling | docs/process>
- Fix Cost: S | M | L
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
| CQ-01 | Executed / Skipped / N/A / Timed out | <reason or short result> |

## Cannot Validate (Gaps)
- <check or claim that could not be validated>
- Reason: <missing tool/data/access/time>
- Impact: <what this uncertainty means for confidence>

## Suppressed / Collapsed Signals
- <optional list of intentionally collapsed low-value signals with reason>

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

---

## Relationship to /audit

- `/audit` = documentation/spec conformance.
- `/code-audit` = generated code architecture/quality/concurrency/lifecycle/resilience/security/dependency/supply-chain/observability/data/test/performance signals.
- They are separate by design and can be run independently.
