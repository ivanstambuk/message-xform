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
- `changed` — derive scope from local diffs (+ staged + untracked). If the
  working tree is clean, fall back to diff vs `origin/main` merge-base.
- `<module>` — one concrete module directory name.

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
2. `origin/main` exists locally for merge-base fallback.
3. Workspace is readable; report path `audit-reports/` is writable.

If a tool is missing:
- continue with available checks,
- mark the skipped checks explicitly in the report's execution matrix,
- do not infer findings for skipped checks.

### Phase 1 — Resolve Audit Scope

// turbo
1. Determine target:
   - `all` -> all active modules
   - `changed` -> files from:
     - `git diff --name-only`
     - `git diff --cached --name-only`
     - `git ls-files --others --exclude-standard`
     - if all three are empty: `git diff --name-only $(git merge-base HEAD origin/main)...HEAD`
   - `<module>` -> that module only
2. Validate module target against `settings.gradle.kts` includes. Unknown module -> stop with clear error.
3. For `changed`, map files to module scopes and ignore non-code paths with explicit report note.
4. Build file sets:
   - Production: `<module>/src/main/java/**`
   - Tests: `<module>/src/test/java/**`
   - Build files: `<module>/build.gradle.kts`, root `build.gradle.kts`, version catalog
5. Exclude non-signal paths: `.gradle/`, `build/`, `out/`, `.idea/`, `.agent/session/`,
   `.sdk-decompile/`, `binaries/`, `docs/`, `audit-reports/`.
6. Normalize and deduplicate file lists (stable sorted order).
7. If resolved scope is empty, write a report with "No auditable code in scope" and stop.
8. Record exact scope in report metadata.

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

### Phase 2.5 — Finding Quality Bar

Before recording a finding, verify all of the following:
1. **Evidence present:** command output or concrete symbol/location.
2. **Reproducible:** include one command that reproduces the signal.
3. **Actionable:** recommendation is specific enough for a follow-up task.
4. **Confidence:** mark as `HIGH` or `MEDIUM`.
5. **False-positive note:** include when heuristic signals are uncertain.

### Phase 2.6 — Root-Cause Grouping

Before finalizing findings:
1. Group symptom-level signals under one root-cause finding when they share the same source.
2. Keep one canonical finding with multiple affected locations.
3. Avoid emitting N nearly identical findings for the same issue class.

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

### Phase 5 — Dependency Hygiene Signals

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

### Phase 6 — Test Quality Signals

Evaluate whether tests are strong enough to protect behavior.

| Check ID | What to inspect | Severity guidance |
|----------|-----------------|-------------------|
| TQ-01 | Missing tests for changed production code paths | High |
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

### Phase 7 — Lightweight Performance Signals

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

### Phase 8 — Guardrail Recommendation Synthesis

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
- Excluded paths: <explicit list>

## Findings by Severity

Finding ID format:
- Use `<CATEGORY>-<NNN>` (e.g., `SEC-001`, `TQ-004`), not a global counter.

### Critical
#### SEC-001 — <title>
- Category: CQ | SEC | DEP | TQ | PERF
- Check ID: <e.g., SEC-03>
- Location: `<path>:<line>`
- Evidence: `<symbol/signature/command output excerpt>`
- Repro command: `<exact command>`
- Risk: <impact in practical terms>
- Recommendation: <concrete remediation path>
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
| CQ-01 | Executed / Skipped / N/A | <reason or short result> |

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
- `/code-audit` = generated code quality/security/performance/dependency/test signals.
- They are separate by design and can be run independently.
