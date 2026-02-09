# Quality Gate — message-xform

## Overview

The quality gate is a single Gradle command that validates code formatting,
compilation, and all tests across every module. It MUST pass before every commit.

## Running the Quality Gate

```bash
# Full gate (format + compile + test):
./gradlew --no-daemon spotlessApply check

# Dry-run format check (no modifications, CI-safe):
./gradlew --no-daemon spotlessCheck check
```

The `pre-commit` Git hook (see `githooks/pre-commit`) runs the dry-run variant
automatically before every commit.

## What It Runs

The command `./gradlew check` executes the following Gradle tasks across all
subprojects (`:core`, `:adapter-standalone`, and any future modules):

| Task | What It Does |
|------|--------------|
| `compileJava` | Compile production sources with `-Xlint:all -Werror` (all warnings are errors) |
| `processResources` | Copy resource files (YAML fixtures, Logback configs, etc.) |
| `compileTestJava` | Compile test sources |
| `test` | Run JUnit 5 tests via `useJUnitPlatform()` |
| `spotlessCheck` | Verify code matches Palantir Java Format 2.78.0 |

### Spotless Configuration

- **Formatter:** Palantir Java Format (`palantirJavaFormat`)
- **Version:** 2.78.0 (pinned in `gradle/libs.versions.toml`)
- **Scope:** `src/**/*.java` in every subproject
- **Additional rules:** Remove unused imports, trim trailing whitespace, end with newline

### Compiler Settings

- **Java 21** via Gradle toolchain (`languageVersion = JavaLanguageVersion.of(21)`)
- **Encoding:** UTF-8
- **Flags:** `-Xlint:all -Werror` — all warnings promoted to errors

### Test Settings

- **Framework:** JUnit 5 (`useJUnitPlatform()`)
- **Assertions:** AssertJ 3.27.6
- **Mocking:** Mockito 5.15.2
- **Logging:** `passed`, `skipped`, `failed` events reported; stdout suppressed

## Focused Module Testing

```bash
# Core engine only:
./gradlew --no-daemon :core:test

# Standalone proxy only:
./gradlew --no-daemon :adapter-standalone:test

# Single test class:
./gradlew --no-daemon :core:test --tests "io.messagexform.core.engine.TransformEngineTest"
```

## Interpreting Failures

### `spotlessCheck` failure

```
> Task :core:spotlessJavaCheck FAILED
```

**Fix:** Run `./gradlew spotlessApply` to auto-format, then re-run the gate.

### Compilation failure (`-Werror`)

```
error: [deprecation] someMethod() in SomeClass has been deprecated
```

**Fix:** Address the warning. Common causes: unused imports (Spotless handles these),
unchecked casts, deprecation warnings. All warnings are errors — no `@SuppressWarnings`
escape hatches without documented justification.

### Test failure

```
> Task :core:test FAILED
  SomeTest > someMethod() FAILED
    org.opentest4j.AssertionFailedError: ...
```

**Fix:** Read the assertion message, fix the code or the test. Run in isolation for
faster iteration: `./gradlew :core:test --tests "io.messagexform.core.SomeTest"`.

## Prerequisites

- **Java 21 JDK** — via SDKMAN (`sdk use java 21.0.4-tem`) or `JAVA_HOME` env var.
  Verify: `java -version` should show `21.x`.
- **Gradle wrapper** — Included in repo (`./gradlew`). No global Gradle install needed.
- **Git hooks** — Run `git config core.hooksPath githooks` once per clone. The
  `pre-commit` hook runs the quality gate automatically.
