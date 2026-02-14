# Feature 009 – Toolchain & Quality Platform

| Field | Value |
|-------|-------|
| Status | Amended — FR-009-16/17 (artifact publishing) added, implementation pending |
| Last updated | 2026-02-09 |
| Owners | Ivan |
| Linked plan | `docs/architecture/features/009/plan.md` |
| Linked tasks | `docs/architecture/features/009/tasks.md` |
| Roadmap entry | #9 – Toolchain & Quality Platform |

> Guardrail: This specification is the single normative source of truth for the feature.
> Track questions in `docs/architecture/open-questions.md`, encode resolved answers
> here, and use ADRs under `docs/decisions/` for architectural clarifications.

## Overview

Feature 009 unifies every build toolchain, code quality, and developer experience
improvement under a single governance feature. It codifies the tools used to build,
test, format, analyse, and ship the message-xform project — capturing **what already
exists** and defining **what should be added** to reach parity with the project's
sister repositories (`openauth-sim`, `journeyforge`).

This feature owns the answers to *"how do we build?"*, *"how do we enforce quality?"*,
and *"how do we keep the toolchain up to date?"* — not *"what do we build?"*. Runtime
behaviour is owned by feature specs; toolchain decisions feed into them.

**Affected modules:** All (`core`, `adapter-standalone`, and any future modules).
Feature 009 artifacts live under `docs/architecture/features/009/`.

## Goals

- G-009-01 – Codify the existing build toolchain (Gradle 9.2 + version catalog,
  Java 21 toolchain, Spotless + Palantir Java Format) as formal requirements so
  they are trackable and upgradable.
- G-009-02 – Introduce **ArchUnit** architectural tests to enforce module boundaries,
  SPI layering rules, and the no-reflection policy in code (not just in prose).
- G-009-03 – Introduce **static analysis** (SpotBugs and/or PMD) — initially
  lightweight, with explicit detector/rule whitelisting, proportional to the
  project's current scope.
- G-009-04 – Maintain CI pipeline and Git hooks as governed quality gates, with
  documented verification commands for every gate stage.
- G-009-05 – Provide a reproducible upgrade path for Gradle, plugins, and
  dependencies, with documented warning-mode sweeps.
- G-009-06 – Add `.editorconfig` for IDE-level formatting consistency across
  contributors and AI agents.

## Non-Goals

- N-009-01 – Changing runtime behaviour or adding new functionality. Feature 009
  improves the *development process*, not the *product*.
- N-009-02 – Introducing test coverage thresholds (JaCoCo). The project already
  has 78.8% test-to-total ratio organically. Coverage gates are future scope —
  they should be added only when the project has enough modules to benefit from
  per-module thresholds.
- N-009-03 – Mutation testing (PIT). Valuable but premature for the current project
  scope. Tracked as a future enhancement.
- N-009-04 – Secrets scanning (gitleaks). Security tooling is important but belongs
  in a dedicated security feature or as a CI pipeline extension, not in the
  toolchain platform.

---

## Baseline — What Already Exists

> These items are captured as formal requirements (✅ = already satisfied) so the
> spec provides a complete picture of the quality platform, not just the delta.

### Build Toolchain

| Aspect | Current State | Requirement ID |
|--------|---------------|----------------|
| **Build tool** | Gradle 9.2.0 via wrapper | FR-009-01 |
| **Dependency management** | Gradle version catalog (`gradle/libs.versions.toml`) | FR-009-01 |
| **Java version** | Java 21 via Gradle toolchain (`languageVersion = 21`) | FR-009-02 |
| **Compiler flags** | `-Xlint:all -Werror` (all warnings are errors) | FR-009-03 |
| **Packaging** | Shadow JAR via `com.gradleup.shadow:9.3.1` | FR-009-04 |

### Code Formatting

| Aspect | Current State | Requirement ID |
|--------|---------------|----------------|
| **Formatter** | Palantir Java Format 2.78.0 via Spotless 8.1.0 | FR-009-05 |
| **Line width** | 120 columns (Palantir default) | FR-009-05 |
| **Supplementary rules** | Remove unused imports, trim trailing whitespace, end with newline | FR-009-05 |

### Quality Gates

| Aspect | Current State | Requirement ID |
|--------|---------------|----------------|
| **Pre-commit hook** | `githooks/pre-commit` runs `spotlessCheck check` | FR-009-06 |
| **Commit-msg hook** | `githooks/commit-msg` enforces conventional commits | FR-009-07 |
| **CI pipeline** | `.github/workflows/ci.yml`: Spotless, build, test, commit lint, Docker build + smoke | FR-009-08 |
| **Quality gate docs** | `docs/operations/quality-gate.md` | FR-009-09 |

### Test Infrastructure

| Aspect | Current State | Requirement ID |
|--------|---------------|----------------|
| **Framework** | JUnit 5.11.4 (`junit-bom`) | FR-009-10 |
| **Assertions** | AssertJ 3.27.6 | FR-009-10 |
| **Mocking** | Mockito 5.15.2 | FR-009-10 |
| **Logging** | Logback 1.5.16 (test runtime) | FR-009-10 |
| **Docker** | Multi-stage Dockerfile with `jlink` custom JRE | FR-009-10 |

### Documentation

| Aspect | Current State | Requirement ID |
|--------|---------------|----------------|
| **LLM guide** | `ReadMe.LLM` | FR-009-09 |
| **Agent rules** | `AGENTS.md` (build commands, formatter policy, hook guard) | FR-009-09 |
| **.gitlint** | Reference config for commit message rules | FR-009-07 |

---

## Functional Requirements

### FR-009-01: Build Toolchain Governance

**Requirement:** The project MUST use Gradle with the wrapper checked into version
control. All dependency versions MUST be managed through the Gradle version catalog
(`gradle/libs.versions.toml`). No dependency versions may appear in `build.gradle.kts`
files directly.

| Aspect | Detail |
|--------|--------|
| Success path | `./gradlew --version` matches `gradle-wrapper.properties` pin |
| Validation path | `./gradlew --warning-mode=all clean check` passes without deprecation warnings |
| Failure path | Version mismatch or stale wrapper → documented upgrade procedure |
| Status | ✅ Satisfied (Gradle 9.2.0, catalog in place) |
| Source | Build stability |

### FR-009-02: Java 21 Toolchain

**Requirement:** All subprojects MUST compile with Java 21 via Gradle's toolchain
resolution (`languageVersion = JavaLanguageVersion.of(21)`). Source encoding MUST
be UTF-8.

| Aspect | Detail |
|--------|--------|
| Success path | `./gradlew compileJava` compiles with Java 21 regardless of local `JAVA_HOME` |
| Validation path | CI uses `actions/setup-java@v4` with `distribution: temurin`, `java-version: 21` |
| Status | ✅ Satisfied |
| Source | Language choice |

### FR-009-03: Compiler Warnings as Errors

**Requirement:** All subprojects MUST compile with `-Xlint:all -Werror`. No
`@SuppressWarnings` escape hatches without documented justification (in code comment
referencing this spec).

| Aspect | Detail |
|--------|--------|
| Success path | All sources compile without warnings |
| Failure path | Warning introduced → build fails → must fix or document `@SuppressWarnings` with spec reference |
| Status | ✅ Satisfied |
| Source | Code quality |

### FR-009-04: Shadow JAR Packaging

**Requirement:** The `adapter-standalone` module MUST produce a shadow JAR (fat JAR)
containing the core engine, adapter, and all runtime dependencies. The shadow JAR
MUST merge `META-INF/services` files (SPI: `ExpressionEngine`, etc.) and set the
`Main-Class` manifest attribute.

| Aspect | Detail |
|--------|--------|
| Success path | `java -jar adapter-standalone-*.jar` starts the proxy |
| Validation path | `./gradlew :adapter-standalone:shadowJar` produces a single JAR |
| Status | ✅ Satisfied (Shadow 9.3.1) |
| Source | Packaging, Feature 004 |

### FR-009-05: Code Formatting (Spotless + Palantir)

**Requirement:** All Java sources (`src/**/*.java`) in every subproject MUST be
formatted with **Palantir Java Format** (120-column line width) via the **Spotless**
Gradle plugin. Supplementary rules: remove unused imports, trim trailing whitespace,
end with newline.

| Aspect | Detail |
|--------|--------|
| Success path | `./gradlew spotlessCheck` passes |
| Fix path | `./gradlew spotlessApply` auto-formats |
| Status | ✅ Satisfied (Palantir 2.78.0, Spotless 8.1.0) |
| Source | Code quality, governance cleanup |

### FR-009-06: Pre-Commit Quality Gate

**Requirement:** A `githooks/pre-commit` hook MUST run `./gradlew --no-daemon
spotlessCheck check` before every commit. The hook MUST source SDKMAN if `JAVA_HOME`
is unset. The hook MUST be activated via `git config core.hooksPath githooks`.

| Aspect | Detail |
|--------|--------|
| Success path | Commit proceeds only if quality gate passes |
| Failure path | `spotlessCheck` or `check` fails → commit rejected |
| Status | ✅ Satisfied |
| Source | Governance cleanup |

### FR-009-07: Conventional Commit Enforcement

**Requirement:** A `githooks/commit-msg` hook MUST enforce conventional commit
format (`<type>(<scope>): <description>`) with:
- Allowed types: `fix`, `feat`, `docs`, `refactor`, `retro`, `session`, `backlog`,
  `terminology`, `test`, `chore`
- Title max length: 72 characters
- Body line max length: 120 characters

The hook MUST be self-contained (shell-based, no external dependencies like gitlint).
A `.gitlint` reference config documents the rules for future tool integration.

| Aspect | Detail |
|--------|--------|
| Success path | Commit message `feat(004): T-004-55 — integration test sweep` → accepted |
| Failure path | `bad message` → rejected with usage examples |
| Status | ✅ Satisfied |
| Source | Governance cleanup |

### FR-009-08: CI Pipeline

**Requirement:** A GitHub Actions CI workflow (`.github/workflows/ci.yml`) MUST run
on pushes to `main` and pull requests. It MUST execute:
1. Spotless format check
2. Build & test (`./gradlew check`)
3. Commit message lint (PR only)
4. Docker image build + size verification (< 150 MB) + health check smoke test

| Aspect | Detail |
|--------|--------|
| Success path | CI passes → green badge |
| Failure path | Any stage fails → CI fails with clear diagnostics |
| Status | ✅ Satisfied |
| Source | Governance cleanup |

### FR-009-09: Quality Gate Documentation

**Requirement:** The project MUST maintain:
- `docs/operations/quality-gate.md` — pipeline explanation with failure
  interpretation and prerequisites
- `AGENTS.md` § Build & Test Commands — complete, runnable Gradle commands
  (not stubs)
- `ReadMe.LLM` — LLM-oriented project overview with module layout, entry points,
  and build commands

| Aspect | Detail |
|--------|--------|
| Success path | All three documents exist and are current |
| Status | ✅ Satisfied |
| Source | Governance cleanup |

### FR-009-10: Test Infrastructure

**Requirement:** All subprojects MUST use JUnit 5 via `junit-bom` with JUnit
Platform Launcher. Standard test dependencies (AssertJ, Mockito, Logback for test
runtime) MUST be shared across subprojects via the root `build.gradle.kts`
`subprojects` block.

| Aspect | Detail |
|--------|--------|
| Success path | `./gradlew test` runs all tests across all modules |
| Status | ✅ Satisfied |
| Source | Test quality |

### FR-009-11: EditorConfig

**Requirement:** The project MUST include an `.editorconfig` file that enforces:
- UTF-8 charset, LF line endings, final newline, trim trailing whitespace (all files)
- 4-space indent for `*.java` and `*.kts` with `max_line_length = 120`
- 2-space indent for `*.yaml` / `*.yml`
- Trailing whitespace preserved for `*.md` (Markdown line breaks)

| Aspect | Detail |
|--------|--------|
| Success path | IDEs that support EditorConfig apply settings automatically |
| Status | ⬜ Not yet implemented |
| Source | IDE consistency |

### FR-009-12: ArchUnit Architectural Tests

**Requirement:** The project MUST include ArchUnit tests that enforce the following
rules at build time. Tests SHOULD live in a dedicated source set or test package
(e.g., `io.messagexform.architecture`) within the `core` module or a dedicated
`architecture-tests` module.

#### Rule 1: Module Boundary Enforcement

`adapter-standalone` classes MUST NOT access `core` internal packages. Only the
following `core` packages are allowed as imports from adapter modules:
- `io.messagexform.core.engine` (public API: `TransformEngine`, `TransformRegistry`)
- `io.messagexform.core.model` (domain objects: `TransformSpec`, `TransformProfile`, `Message`, etc.)
- `io.messagexform.core.spi` (SPI interfaces: `GatewayAdapter`, `ExpressionEngine`, `TelemetryListener`)

Internal packages (`io.messagexform.core.spec`, `io.messagexform.core.schema`,
`io.messagexform.core.jslt`, and any future internal packages) MUST NOT be accessed
from adapter modules.

#### Rule 2: Core Has Zero Gateway Dependencies

Classes in the `core` module MUST NOT import any gateway-specific packages:
- `io.javalin..` (Javalin / Jetty)
- `jakarta.servlet..` (Servlet API)
- Gateway SDKs (`com.pingidentity..`, `org.forgerock..`, etc.)

This ensures the core engine remains gateway-agnostic (G-001-04).

#### Rule 3: No Reflection

No project-owned class may use `java.lang.reflect.*` APIs. This ensures
the codebase remains AOT-friendly and introspectable.

#### Rule 4: SPI Layering

Classes implementing `GatewayAdapter` MUST reside in an `adapter` sub-package
(e.g., `io.messagexform.standalone.adapter`). Classes implementing
`ExpressionEngine` MUST reside in a `spi` or `engine` sub-package of `core`.

| Aspect | Detail |
|--------|--------|
| Success path | `./gradlew :core:test` (or `:architecture-tests:test`) passes with all rules green |
| Failure path | Boundary violation → test failure with descriptive rule name and violating class |
| Status | ⬜ Not yet implemented |
| Source | openauth-sim parity, AGENTS.md reflection policy |

### FR-009-13: Static Analysis (SpotBugs)

**Requirement:** The project SHOULD include SpotBugs configured with a shared
include filter for dead-state detectors (`URF_*`, `UUF_*`, `UWF_*`, `NP_UNWRITTEN_*`).
SpotBugs runs as part of the quality gate (`./gradlew spotbugsMain`). Suppressions
MUST be documented with rationale.

This is a SHOULD (not MUST) for the initial implementation — the project can start
with ArchUnit and add SpotBugs once the module count grows.

| Aspect | Detail |
|--------|--------|
| Success path | `./gradlew spotbugsMain` passes with zero findings |
| Failure path | Finding → fix or document suppression with rationale |
| Status | ⬜ Not yet implemented |
| Source | openauth-sim parity |

### FR-009-14: Gradle Upgrade Procedure

**Requirement:** The project MUST document a reproducible Gradle upgrade procedure:

1. `./gradlew wrapper --gradle-version <new> --distribution-type bin`
2. `./gradlew --warning-mode=all clean check` (verify no deprecation warnings)
3. `./gradlew --configuration-cache help` (validate configuration cache)
4. Update version pins in `gradle/libs.versions.toml` for any plugin bumps
5. Record upgrade in commit message: `chore: upgrade Gradle <old> → <new>`

| Aspect | Detail |
|--------|--------|
| Success path | Upgrade completes, quality gate passes, no regressions |
| Status | ⬜ Documented but not yet formalized as a runbook |
| Source | openauth-sim parity, build reliability |

### FR-009-15: Dependency Version Pinning

**Requirement:** All dependencies MUST be pinned to exact versions in the Gradle
version catalog. No range expressions (e.g., `[2.0,3.0)`) or dynamic versions
(e.g., `+`). Plugin versions MUST also be pinned in the `[plugins]` section.

| Aspect | Detail |
|--------|--------|
| Success path | All versions in `libs.versions.toml` are exact |
| Verification | Manual review during dependency bumps |
| Status | ✅ Satisfied |
| Source | Build reproducibility |

### FR-009-16: Artifact Publishing (Maven Central)

**Requirement:** The project MUST provide a GitHub Actions workflow that publishes
releasable artifacts to **Maven Central** (via Central Portal). Publishing MUST be
**user-driven** — triggered by `workflow_dispatch` (manual) or a GitHub `release`
event — never on automatic push.

Artifact matrix:

| Module | Artifact ID | Packaging | Notes |
|--------|-------------|-----------|-------|
| `core` | `message-xform-core` | JAR | Gateway-agnostic engine — the primary library artifact |
| `adapter-standalone` | `message-xform-proxy` | Shadow JAR | Standalone proxy — fat JAR with all dependencies |
| Future adapters | `message-xform-<gateway>` | JAR | One artifact per gateway adapter (PingAccess, PingGateway, etc.) |

The workflow MUST:
1. Run `spotlessCheck check` before publishing (quality gate).
2. Sign all artifacts with GPG (in-memory key from secrets).
3. Use `org.danilopianini.publish-on-central` or equivalent Gradle plugin.
4. Publish POM with correct dependency metadata (shadow JAR omits bundled deps).
5. Use secrets: `MAVEN_CENTRAL_PORTAL_USERNAME`, `MAVEN_CENTRAL_PORTAL_PASSWORD`,
   `SIGNING_KEY`, `SIGNING_PASSWORD`.

Reference: `openauth-sim/.github/workflows/publish-standalone.yml`.

| Aspect | Detail |
|--------|--------|
| Success path | `workflow_dispatch` → quality gate → sign → publish → artifact visible on Maven Central |
| Failure path | Quality gate fails → publish aborted, no partial upload |
| Status | ⬜ Not yet implemented |
| Source | openauth-sim parity, distribution strategy |

### FR-009-17: Docker Image Publishing (Docker Hub)

**Requirement:** The project MUST provide a GitHub Actions workflow (or a job within
the publish workflow) that builds and pushes the standalone proxy Docker image to
**Docker Hub**. Publishing MUST be **user-driven** — triggered by `workflow_dispatch`
or a GitHub `release` event.

The workflow MUST:
1. Build the Docker image using the existing multi-stage `Dockerfile`.
2. Tag the image with `latest` and the release version (e.g., `1.0.0`).
3. Push to Docker Hub under `ivanstambuk/message-xform-proxy` (or equivalent).
4. Run a health-check smoke test before pushing (same as CI).
5. Use secrets: `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`.

| Aspect | Detail |
|--------|--------|
| Success path | `workflow_dispatch` → build → smoke test → push → image visible on Docker Hub |
| Failure path | Smoke test fails → push aborted |
| Status | ⬜ Not yet implemented |
| Source | Distribution strategy, Feature 004 |

---

## Non-Functional Requirements

| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-009-01 | Quality gate runtime MUST remain < 5 minutes for the full `spotlessCheck check` on a developer machine with ≤ 100 source files. | Developer ergonomics | Record runtimes before/after each toolchain addition. | Gradle, ArchUnit, SpotBugs. | Spec. |
| NFR-009-02 | ArchUnit tests MUST add < 10 seconds to `./gradlew :core:test` runtime. | Fast feedback | Benchmark before/after introduction. | ArchUnit library. | Spec. |
| NFR-009-03 | CI pipeline MUST complete in < 10 minutes end-to-end (build + test + Docker). | CI efficiency | Monitor GitHub Actions job duration. | GitHub Actions runners. | Spec. |
| NFR-009-04 | Toolchain changes MUST NOT break the existing 258 tests or 77 scenarios. | Backward compatibility | Full `./gradlew check` before and after. | All modules. | Spec. |

---

## Branch & Scenario Matrix

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-009-01 | **Quality gate green:** `./gradlew spotlessApply check` passes with all modules clean. |
| S-009-02 | **Pre-commit rejection:** Unformatted Java file → `pre-commit` hook rejects commit. |
| S-009-03 | **Commit-msg rejection:** Non-conventional commit → `commit-msg` hook rejects with examples. |
| S-009-04 | **CI green on push:** Push to `main` → CI builds, tests, Docker smoke → all green. |
| S-009-05 | **ArchUnit boundary violation:** Test class in `adapter-standalone` imports `core.spec` internal → ArchUnit test fails with descriptive message. |
| S-009-06 | **ArchUnit no-reflection:** Class uses `Field.get()` → ArchUnit test fails. |
| S-009-07 | **ArchUnit core isolation:** Class in `core` imports `io.javalin` → ArchUnit test fails. |
| S-009-08 | **Gradle upgrade:** Wrapper updated → `--warning-mode=all clean check` passes. |
| S-009-09 | **EditorConfig applied:** Java file opened in IDE → 4-space indent, 120 column guide visible. |
| S-009-10 | **SpotBugs clean:** `./gradlew spotbugsMain` reports zero findings. |
| S-009-11 | **Maven Central publish:** `workflow_dispatch` on `publish.yml` → quality gate passes → artifacts signed and published to Maven Central Portal. |
| S-009-12 | **Docker Hub publish:** `workflow_dispatch` on `publish.yml` → Docker image built, smoke-tested, tagged, pushed to Docker Hub. |
| S-009-13 | **Publish gate failure:** Quality gate fails during publish workflow → publish aborted, no artifacts uploaded. |

---

## Test Strategy

- **ArchUnit tests:** JUnit 5 tests using `@ArchTest` annotations. Run as part of
  `./gradlew :core:test` (or `:architecture-tests:test`). Test fixture: import
  `com.tngtech.archunit.core.importer.ClassFileImporter` to load project classes
  and apply rules.
- **Hook tests:** Shell-based sanity checks (already verified during governance
  cleanup — bad message rejected, valid message accepted).
- **CI verification:** Push to a branch triggers the workflow; verify all stages pass.
- **SpotBugs:** Run via `./gradlew spotbugsMain spotbugsTest` once configured.
- **EditorConfig:** Manual verification in IDE; optionally `eclint` CLI check.
- **Gradle upgrade:** Follow documented procedure, record output in session log.

---

## Interface & Contract Catalogue

### Build Commands (CLI)

| ID | Command | Behaviour |
|----|---------|-----------|
| CLI-009-01 | `./gradlew --no-daemon spotlessApply check` | Full quality gate: format + compile + test |
| CLI-009-02 | `./gradlew --no-daemon spotlessCheck check` | Dry-run quality gate (CI-safe) |
| CLI-009-03 | `./gradlew --no-daemon :core:test` | Core module tests only |
| CLI-009-04 | `./gradlew --no-daemon :adapter-standalone:test` | Standalone proxy tests only |
| CLI-009-05 | `./gradlew --no-daemon :adapter-standalone:shadowJar` | Build fat JAR |
| CLI-009-06 | `./gradlew --warning-mode=all clean check` | Deprecation sweep (for upgrades) |
| CLI-009-07 | `./gradlew --configuration-cache help` | Configuration cache validation |
| CLI-009-08 | `./gradlew wrapper --gradle-version <ver> --distribution-type bin` | Wrapper upgrade |
| CLI-009-09 | `./gradlew spotbugsMain spotbugsTest` | Static analysis (once configured) |
| CLI-009-10 | `./gradlew :core:publish...` | Publish core JAR to Maven Central (via publish-on-central) |
| CLI-009-11 | `./gradlew :adapter-standalone:publish...` | Publish standalone proxy shadow JAR to Maven Central |
| CLI-009-12 | `docker build -t message-xform-proxy . && docker push` | Build and push Docker image (CI workflow) |

### Configuration Files

| ID | Path | Purpose |
|----|------|---------|
| CFG-009-01 | `gradle/libs.versions.toml` | Central dependency version catalog |
| CFG-009-02 | `gradle/wrapper/gradle-wrapper.properties` | Gradle version pin |
| CFG-009-03 | `build.gradle.kts` (root) | Shared build config: toolchain, Spotless, test deps |
| CFG-009-04 | `githooks/pre-commit` | Pre-commit quality gate |
| CFG-009-05 | `githooks/commit-msg` | Conventional commit enforcement |
| CFG-009-06 | `.github/workflows/ci.yml` | CI pipeline definition |
| CFG-009-07 | `.gitlint` | Commit message rules (reference) |
| CFG-009-08 | `.editorconfig` | IDE formatting rules (to be created) |
| CFG-009-09 | `config/spotbugs/include-filter.xml` | SpotBugs detector config (to be created) |
| CFG-009-10 | `.github/workflows/publish.yml` | Artifact + Docker publish workflow (to be created) |

### Fixtures & Sample Data

| ID | Path | Purpose |
|----|------|---------|
| FX-009-01 | `docs/operations/quality-gate.md` | Quality gate documentation |
| FX-009-02 | `ReadMe.LLM` | LLM-oriented project overview |
| FX-009-03 | `AGENTS.md` § Build & Test Commands | Build command reference |

---

## Appendix

### A. Current Dependency Versions

| Dependency | Version | Category |
|-----------|---------|----------|
| Gradle | 9.2.0 | Build tool |
| Java (Temurin) | 21.0.4+7 | Language |
| Palantir Java Format | 2.78.0 | Formatting |
| Spotless | 8.1.0 | Formatting plugin |
| Shadow | 9.3.1 | Packaging |
| Jackson | 2.18.4 | Core (JSON) |
| JSLT | 0.1.14 | Core (expression engine) |
| Javalin | 6.7.0 | Adapter (HTTP server) |
| JSON Schema Validator | 1.5.7 | Core (schema validation) |
| SLF4J | 2.0.17 | Logging API |
| Logback | 1.5.16 | Logging impl |
| SnakeYAML | 2.4 | Config parsing |
| JUnit 5 | 5.11.4 | Test framework |
| AssertJ | 3.27.6 | Test assertions |
| Mockito | 5.15.2 | Test mocking |

### B. Quality Gate Pipeline Stages

```
                    ┌─────────────────────────────┐
                    │       Developer Commit       │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │   githooks/commit-msg        │
                    │   → conventional format      │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │   githooks/pre-commit        │
                    │   → spotlessCheck            │
                    │   → compileJava (-Werror)    │
                    │   → test (JUnit 5)           │
                    │   → [archunit] (FR-009-12)   │
                    │   → [spotbugsMain] (FR-009-13)│
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │   GitHub Actions CI          │
                    │   → spotlessCheck            │
                    │   → check (compile + test)   │
                    │   → commit-msg lint (PRs)    │
                    │   → Docker build + smoke     │
                    └─────────────────────────────┘

           ┌─────────────────────────────────────────┐
           │  Publish (manual / release event only)   │
           │  → spotlessCheck check (quality gate)    │
           │  → GPG sign + Maven Central publish      │
           │  → Docker build + smoke + Docker Hub push │
           └─────────────────────────────────────────┘
```

### C. ArchUnit Rule Map

```
Rule 1: Module Boundaries
  adapter-standalone → may access → core.engine, core.model, core.spi
  adapter-standalone ✗ may NOT access → core.spec, core.schema, core.jslt

Rule 2: Core Isolation
  core ✗ may NOT import → io.javalin.., jakarta.servlet..

Rule 3: No Reflection
  * ✗ may NOT access → java.lang.reflect.*

Rule 4: SPI Layering
  GatewayAdapter → resides in → *.adapter.*
  ExpressionEngine → resides in → *.spi.* or *.engine.*
```

### D. Related Documents

| Document | Relevance |
|----------|-----------|
| `docs/operations/quality-gate.md` | Pipeline docs (owned by this feature) |
| `AGENTS.md` § Build & Test Commands | Build command reference (owned by this feature) |
| `ReadMe.LLM` | Developer overview (owned by this feature) |
| `PLAN.md` § Governance Parity | Backlog items that triggered this feature |
| `docs/decisions/project-constitution.md` | Non-negotiable SDD principles |
