---
description: Read-only generated-code audit for quality, security, dependency hygiene, test quality, and lightweight performance signals
---

# /code-audit — Generated Code Audit

## Invocation

```
/code-audit all
/code-audit changed
/code-audit <module>
```

**Examples:**
- `/code-audit all` — audit all production modules.
- `/code-audit changed` — audit only files changed vs current branch state.
- `/code-audit adapter-pingaccess` — audit a single module.

**Parameters:**
- `all` — `core`, `adapter-standalone`, `adapter-pingaccess` (if present).
- `changed` — derive scope from `git diff --name-only` (+ staged + untracked).
- `<module>` — one concrete module directory name.

---

## Operating Constraints

1. **Read-only workflow:** do not modify code/docs/specs/tasks as part of audit.
2. **Informational only:** rank findings by severity; do not block progress.
3. **One report only:** produce a detailed report file (no mandatory chat scorecard).
4. **Centralized output:** all audit reports live under `audit-reports/`.

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

### Phase 1 — Resolve Audit Scope

// turbo
1. Determine target:
   - `all` -> all active modules
   - `changed` -> files from:
     - `git diff --name-only`
     - `git diff --cached --name-only`
     - `git ls-files --others --exclude-standard`
   - `<module>` -> that module only
2. Build file sets:
   - Production: `<module>/src/main/java/**`
   - Tests: `<module>/src/test/java/**`
   - Build files: `<module>/build.gradle.kts`, root `build.gradle.kts`, version catalog
3. Record exact scope in report metadata.

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

Evidence commands (examples):

```bash
rg -n "Class\\.forName|setAccessible\\(|getDeclared(Field|Method|Constructor)" <scope>
rg -n "catch \\(Exception|catch \\(Throwable" <scope>
rg -n "TODO|FIXME|HACK" <scope>/src/main/java
```

### Phase 4 — Security Signals

Evaluate common secure-coding risks.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| SEC-01 | Hardcoded secrets/tokens/passwords/keys | Critical |
| SEC-02 | Command execution/process invocation from variable input | High |
| SEC-03 | Path traversal / unsafe file path composition | High |
| SEC-04 | Unsafe deserialization / dynamic code loading | High |
| SEC-05 | TLS/hostname verification weakening, insecure crypto defaults | Medium |
| SEC-06 | Sensitive data over-logging risk | Medium |

Evidence commands (examples):

```bash
rg -n "(password|secret|token|apikey|api_key|private_key)\\s*[:=]" <scope>
rg -n "Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder\\(" <scope>
rg -n "ObjectInputStream|readObject\\(|URLClassLoader|ScriptEngineManager" <scope>
rg -n "disableHostnameVerification|TrustAll|X509TrustManager|setHostnameVerifier" <scope>
```

### Phase 5 — Dependency Hygiene Signals

Evaluate dependency consistency and maintenance risk.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| DEP-01 | Version drift between build files and version catalog | Medium |
| DEP-02 | Duplicate/conflicting dependency paths in classpath graph | Medium |
| DEP-03 | Unused or unjustified dependencies in module scope | Low |
| DEP-04 | Shadow packaging/excludes consistency with module intent | Medium |

Use:

```bash
./gradlew --no-daemon :<module>:dependencies --configuration compileClasspath
./gradlew --no-daemon :<module>:dependencies --configuration testCompileClasspath
```

### Phase 6 — Test Quality Signals

Evaluate whether tests are strong enough to protect behavior.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| TQ-01 | Missing tests for changed production code paths | High |
| TQ-02 | Weak assertions (asserting only non-null / no behavior) | Medium |
| TQ-03 | Flakiness signals (`Thread.sleep`, timing races, order dependence) | Medium |
| TQ-04 | Disabled/ignored tests without rationale | Medium |
| TQ-05 | Missing failure-branch tests for error handling | High |

Evidence commands (examples):

```bash
rg -n "@Disabled|@Ignore|Thread\\.sleep\\(" <scope>/src/test/java
rg -n "assertThat\\(.*\\)\\.isNotNull\\(\\)" <scope>/src/test/java
```

### Phase 7 — Lightweight Performance Signals

Focus on pragmatic heuristics (not full benchmarking).

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| PERF-01 | Blocking I/O on hot request/response paths | High |
| PERF-02 | Repeated heavyweight object creation in loops/hot paths | Medium |
| PERF-03 | Unbounded in-memory collections/caches | Medium |
| PERF-04 | Excessive payload copy/serialization churn | Medium |

Notes:
- Keep this phase heuristic and evidence-based.
- Do not claim latency regressions without measured evidence.

### Phase 8 — Guardrail Recommendation Synthesis

Convert findings into preventive controls.

For each High/Critical finding, recommend one or more:
1. `AGENTS.md` rule update
2. Workflow check update (`/audit`, `/init`, `/retro`, `/code-audit`)
3. Spec/plan/tasks template guardrail
4. CI non-blocking signal (reporting-only)

Recommendations must be concrete and directly tied to findings.

---

## Output — Detailed Report File Only

Write the report to:

```
audit-reports/code-audit-<target>-<YYYYMMDD-HHMMSS>.md
```

Examples:
- `audit-reports/code-audit-all-20260215-103000.md`
- `audit-reports/code-audit-changed-20260215-104512.md`
- `audit-reports/code-audit-adapter-pingaccess-20260215-105901.md`

`audit-reports/` is gitignored and shared with `/audit` findings.

### Required report template

````markdown
# Code Audit — <target>

> ⚠️ Ephemeral artifact (gitignored). Informational only.

## Metadata
- Date: <ISO timestamp>
- Target: <all|changed|module>
- Scope paths: <explicit list>
- Commands executed: <explicit list>

## Findings by Severity

### Critical
#### CA-001 — <title>
- Category: CQ | SEC | DEP | TQ | PERF
- Location: `<path>:<line>`
- Evidence: `<symbol/signature/command output excerpt>`
- Risk: <impact in practical terms>
- Recommendation: <concrete remediation path>

### High
...

### Medium
...

### Low
...

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

---

## Post-Audit Rules

1. Do not auto-fix findings in this workflow.
2. Do not update specs/plans/tasks as part of this workflow.
3. Present report path and top findings to the user.
4. Wait for explicit follow-up direction before making changes elsewhere.

---

## Relationship to /audit

- `/audit` = documentation/spec conformance.
- `/code-audit` = generated code quality/security/performance/dependency/test signals.
- They are separate by design and can be run independently.
