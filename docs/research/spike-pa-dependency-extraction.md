# Spike B: PingAccess Dependency Version Extraction & Build Alignment

Status: **🔄 In Progress** | Created: 2026-02-11 | Feature: 002

> **Spike A resolved.** Classloader model confirmed (flat classpath, no isolation).
> PA ships Jackson 2.17.0, SLF4J 1.7.36, Log4j2 2.24.3. SnakeYAML not shipped.
> Remaining: script automation, build integration, version guard.

## Tracker

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| B-1 | Create extraction script (`scripts/pa-extract-deps.sh`) | 🔲 | Docker-based, outputs structured list |
| B-2 | Run against PA 9.0.1 Docker image | ✅ Manual | Versions confirmed from Spike A extraction |
| B-3 | Analyze extracted dependencies | ✅ | 146 JARs categorized; see Spike A findings |
| B-4 | Design build integration strategy | 🔲 | Gradle platform, compileOnly, version catalog |
| B-5 | Create Gradle platform module or TOML overlay | 🔲 | `pa-platform-9.0/` or `gradle/pa-provided.toml` |
| B-6 | Prototype adapter build with `compileOnly` Jackson | 🔲 | Validate compilation, test execution |
| B-7 | Implement runtime version guard | 🔲 | `configure()` check, warn on mismatch |
| B-8 | Document version-locked release strategy | 🔲 | Plugin version = PA version contract |
| B-9 | Update spec, SDK guide, and PLAN.md | 🔲 | Reflect new dependency strategy |

---

## Motivation

### The Problem

Feature 002 currently specifies that the adapter shadow JAR **bundles and
relocates Jackson** (`com.fasterxml.jackson.*` →
`io.messagexform.shaded.jackson.*`). This was a defensive decision because
PA's internal Jackson version is undocumented and could theoretically drift
between PA releases.

### Why This Matters

If the adapter uses PA's Jackson as a `compileOnly` dependency instead of
bundling it, we gain:

| Benefit | Detail |
|---------|--------|
| No relocation complexity | Eliminates shaded package names, dual `ObjectMapper`, boundary conversion |
| Smaller JAR | ~2-3 MB smaller shadow JAR (no bundled Jackson) |
| Simpler code | `Identity.getAttributes()` returns the same `JsonNode` class → no serialization round-trip |
| Easier debugging | Stack traces show real class names, not shaded ones |
| PA ecosystem alignment | Follows the same pattern as SDK samples and vendor-built plugins |

But this requires **knowing exactly what PA ships** — we need to pin our
compile-time dependency to the exact version PA provides at runtime.

### The Proposal: Version-Locked Plugin Releases

Adopt a **version-locked release strategy** where:

- Plugin version `9.0.x` is compatible with PA `9.0.*`
- When PA upgrades (e.g., to 9.1), we run the extraction script, update
  dependency versions, and release plugin `9.1.0`
- A runtime version guard warns operators if they deploy a mismatched plugin

This is standard practice in mature plugin ecosystems:

| Ecosystem | Version Contract |
|-----------|-----------------|
| Elasticsearch plugins | Must declare `elasticsearch.version`; rejected at load time if mismatched |
| Gradle plugins | Declare compatible Gradle version range |
| IntelliJ plugins | `since-build` / `until-build` in `plugin.xml` |
| Jenkins plugins | Declare minimum Jenkins version in POM |

### Goal

1. Create a script that extracts **all library versions** from a PA Docker image
2. Design a build integration that pins adapter dependencies to PA's versions
3. Establish a version-locked release contract

---

## Background & Context

### Relevant Files

| File | Purpose |
|------|---------|
| `docs/architecture/features/002/spec.md` | FR-002-09 (Deployment Packaging), FR-002-10 (Gradle Module) |
| `docs/architecture/features/002/pingaccess-sdk-guide.md` §9 | Current classloading & shadow JAR docs |
| `docs/research/pingaccess-docker-and-sdk.md` | Docker image details |
| `docs/research/spike-pa-classloader-model.md` | Companion spike — classloader visibility |
| `gradle/libs.versions.toml` | Current version catalog (core project) |
| `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` | SDK JAR |

### PA Docker Image Layout (Verified)

```
/opt/server/
├── bin/                    # PA startup scripts (run.sh)
├── conf/                   # Configuration files
├── deploy/                 # Plugin JARs (our target)
├── lib/                    # Platform libraries — 146 JARs (VERIFIED)
│   ├── pingaccess-sdk-9.0.1.0.jar
│   ├── jackson-databind-2.17.0.jar     ✅ CONFIRMED
│   ├── jackson-core-2.17.0.jar         ✅ CONFIRMED
│   ├── jackson-annotations-2.17.0.jar  ✅ CONFIRMED
│   ├── jackson-datatype-jdk8-2.17.0.jar
│   ├── jackson-datatype-jsr310-2.17.0.jar
│   ├── slf4j-api-1.7.36.jar            ✅ CONFIRMED (SLF4J 1.x, not 2.x)
│   ├── log4j-api-2.24.3.jar            ✅ CONFIRMED (Log4j2, NOT Logback)
│   ├── log4j-slf4j-impl-2.24.3.jar     ✅ (SLF4J→Log4j2 bridge)
│   ├── ❌ SnakeYAML NOT shipped
│   ├── ... (146 total JARs)
│   └── pingaccess-engine-9.0.1.0.jar
├── log/                    # Log files (or /opt/out/instance/log/)
└── upgrade/                # Upgrade scripts
```

### PA Dependencies (Verified from Docker Image Extraction)

Confirmed versions from Spike A extraction of `/opt/server/lib/`:

| Library | Version | Status |
|---------|---------|--------|
| `com.fasterxml.jackson.core:jackson-databind` | **2.17.0** | ✅ Confirmed |
| `com.fasterxml.jackson.core:jackson-core` | **2.17.0** | ✅ Confirmed |
| `com.fasterxml.jackson.core:jackson-annotations` | **2.17.0** | ✅ Confirmed |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | **2.17.0** | ✅ Confirmed |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | **2.17.0** | ✅ Confirmed |
| `jakarta.validation:jakarta.validation-api` | **3.1.1** | ✅ Confirmed |
| `jakarta.inject:jakarta.inject-api` | **2.0.1** | ✅ Confirmed |
| `org.slf4j:slf4j-api` | **1.7.36** | ✅ Confirmed (SLF4J 1.x API) |
| `org.apache.logging.log4j:log4j-api` | **2.24.3** | ✅ Confirmed |
| `org.apache.commons:commons-lang3` | **3.14.0** | ✅ Confirmed |
| `com.google.guava:guava` | **33.1.0-jre** | ✅ Confirmed |
| `org.yaml:snakeyaml` | ❌ | **Not shipped** — must bundle |
| `ch.qos.logback:logback-classic` | ❌ | **Not shipped** — PA uses Log4j2 |

---

## Execution Plan

### B-1: Create Extraction Script

Create `scripts/pa-extract-deps.sh` that:

1. Pulls or uses a local PA Docker image
2. Copies `/opt/server/lib/` contents from the container
3. For each JAR, extracts version information from multiple sources
4. Outputs a structured dependency list

```bash
#!/usr/bin/env bash
# scripts/pa-extract-deps.sh
# Extract dependency versions from a PingAccess Docker image.
#
# Usage:
#   ./scripts/pa-extract-deps.sh [image_tag] [output_format]
#
# Arguments:
#   image_tag     — Docker image (default: pingidentity/pingaccess:9.0.1-latest)
#   output_format — "toml" (default), "json", or "csv"
#
# Output:
#   gradle/pa-<version>-provided-dependencies.<format>
#
# The script extracts version information from three sources (in priority order):
#   1. META-INF/maven/*/pom.properties  → groupId, artifactId, version
#   2. META-INF/MANIFEST.MF             → Implementation-Version, Bundle-Version
#   3. JAR filename pattern             → artifact-version.jar (regex extraction)

set -euo pipefail

IMAGE="${1:-pingidentity/pingaccess:9.0.1-latest}"
FORMAT="${2:-toml}"
PA_LIB_DIR="/opt/server/lib"
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

echo "📦 Extracting dependencies from: $IMAGE"
echo "   Target: $PA_LIB_DIR"
echo "   Format: $FORMAT"
echo ""

# Step 1: Create a temporary container (don't start it)
CONTAINER_ID=$(docker create "$IMAGE")
echo "Created container: $CONTAINER_ID"

# Step 2: Copy lib directory
docker cp "$CONTAINER_ID:$PA_LIB_DIR" "$TEMP_DIR/lib"
docker rm "$CONTAINER_ID" > /dev/null

JAR_COUNT=$(find "$TEMP_DIR/lib" -name "*.jar" | wc -l)
echo "Found $JAR_COUNT JAR files"
echo ""

# Step 3: Extract version info from each JAR
# Output: groupId|artifactId|version|source|filename
extract_versions() {
    local jar_file="$1"
    local filename=$(basename "$jar_file")
    local found=false

    # Strategy 1: pom.properties (most reliable)
    local pom_props=$(unzip -p "$jar_file" "META-INF/maven/*/pom.properties" 2>/dev/null || true)
    if [[ -n "$pom_props" ]]; then
        local group=$(echo "$pom_props" | grep "^groupId=" | head -1 | cut -d= -f2 | tr -d '\r')
        local artifact=$(echo "$pom_props" | grep "^artifactId=" | head -1 | cut -d= -f2 | tr -d '\r')
        local version=$(echo "$pom_props" | grep "^version=" | head -1 | cut -d= -f2 | tr -d '\r')
        if [[ -n "$group" && -n "$artifact" && -n "$version" ]]; then
            echo "${group}|${artifact}|${version}|pom.properties|${filename}"
            found=true
        fi
    fi

    # Strategy 2: MANIFEST.MF
    if [[ "$found" == "false" ]]; then
        local manifest=$(unzip -p "$jar_file" "META-INF/MANIFEST.MF" 2>/dev/null || true)
        local impl_version=$(echo "$manifest" | grep "^Implementation-Version:" | head -1 | awk '{print $2}' | tr -d '\r')
        local impl_title=$(echo "$manifest" | grep "^Implementation-Title:" | head -1 | cut -d: -f2- | xargs | tr -d '\r')
        local bundle_name=$(echo "$manifest" | grep "^Bundle-SymbolicName:" | head -1 | awk '{print $2}' | tr -d '\r')
        local bundle_version=$(echo "$manifest" | grep "^Bundle-Version:" | head -1 | awk '{print $2}' | tr -d '\r')

        local name="${impl_title:-${bundle_name:-unknown}}"
        local ver="${impl_version:-${bundle_version:-unknown}}"

        if [[ "$ver" != "unknown" ]]; then
            echo "unknown|${name}|${ver}|MANIFEST.MF|${filename}"
            found=true
        fi
    fi

    # Strategy 3: Filename regex
    if [[ "$found" == "false" ]]; then
        # Match: artifact-1.2.3.jar or artifact-1.2.3-qualifier.jar
        if [[ "$filename" =~ ^(.+)-([0-9]+\.[0-9]+.*)\.(jar)$ ]]; then
            local artifact="${BASH_REMATCH[1]}"
            local version="${BASH_REMATCH[2]}"
            echo "unknown|${artifact}|${version}|filename|${filename}"
        else
            echo "unknown|${filename%.jar}|unknown|none|${filename}"
        fi
    fi
}

echo "Extracting versions..."
echo ""

# Process all JARs
RESULTS_FILE="$TEMP_DIR/results.txt"
for jar in "$TEMP_DIR/lib"/*.jar; do
    extract_versions "$jar" >> "$RESULTS_FILE"
done

# Sort by groupId, then artifactId
sort -t'|' -k1,1 -k2,2 "$RESULTS_FILE" > "$TEMP_DIR/sorted.txt"

# Step 4: Output in requested format
# ... (format-specific output logic)
# For TOML: generate a Gradle-compatible version catalog overlay
# For JSON: machine-readable full dump
# For CSV: spreadsheet-importable

echo "✅ Extraction complete. $JAR_COUNT JARs processed."
```

#### Key Libraries to Highlight (Updated with Confirmed Status)

The script should flag these **plugin-relevant** libraries with special markers:

| Category | Libraries | PA 9.0.1 Status | Build Scope |
|----------|----------|-----------------|-------------|
| **Jackson** | `jackson-databind`, `jackson-core`, `jackson-annotations` (2.17.0) | ✅ Shipped | `compileOnly` |
| **Jackson extras** | `jackson-datatype-jdk8`, `jackson-datatype-jsr310` (2.17.0) | ✅ Shipped | `compileOnly` |
| **Jackson YAML** | `jackson-dataformat-yaml` | ❌ Not shipped | Bundle if needed |
| **Logging API** | `slf4j-api` (1.7.36) | ✅ Shipped | `compileOnly` |
| **Logging Impl** | `log4j-api`, `log4j-core`, `log4j-slf4j-impl` (2.24.3) | ✅ Shipped | `compileOnly` |
| **Validation** | `jakarta.validation-api` (3.1.1), `hibernate-validator` (7.0.5) | ✅ Shipped | `compileOnly` |
| **DI** | `jakarta.inject-api` (2.0.1) | ✅ Shipped | `compileOnly` |
| **YAML** | `snakeyaml` | ❌ **Not shipped** | `implementation` (bundle) |
| **HTTP** | `netty-*` (4.1.127.Final) | ✅ Shipped (not Jetty) | N/A |
| **Commons** | `commons-lang3` (3.14.0), `guava` (33.1.0-jre) | ✅ Shipped | `compileOnly` |
| **PA Internal** | `pingaccess-*` (9.0.1.0) | ✅ Shipped | `compileOnly` |

### B-2: Run Against PA 9.0.1

```bash
./scripts/pa-extract-deps.sh pingidentity/pingaccess:9.0.1-latest toml
```

Expected output: `gradle/pa-9.0-provided-dependencies.toml`

Example TOML format (now using **confirmed** versions):
```toml
# PingAccess 9.0.1 — Provided Dependencies
# Generated: 2026-02-11 by Spike A reverse engineering + scripts/pa-extract-deps.sh
# Source image: pingidentity/pingaccess:latest (9.0.1.0)
#
# These libraries are available on the PA runtime classpath.
# Adapter modules should declare them as compileOnly with these exact versions.

[metadata]
pa-version = "9.0.1"
image = "pingidentity/pingaccess:latest"
extracted = "2026-02-11"
jar-count = 146

[versions]
# Jackson — CONFIRMED from Docker extraction
jackson = "2.17.0"

# Logging — CONFIRMED (Log4j2, NOT Logback)
slf4j-api = "1.7.36"       # SLF4J 1.x API
log4j = "2.24.3"             # Log4j2 (with SLF4J bridge)

# Validation — CONFIRMED
jakarta-validation-api = "3.1.1"
hibernate-validator = "7.0.5.Final"

# DI — CONFIRMED
jakarta-inject-api = "2.0.1"

# SnakeYAML — NOT SHIPPED (must bundle)
# snakeyaml = NOT AVAILABLE

# Commons — CONFIRMED
commons-lang3 = "3.14.0"
guava = "33.1.0-jre"

[libraries]
jackson-databind = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version.ref = "jackson" }
jackson-core = { group = "com.fasterxml.jackson.core", name = "jackson-core", version.ref = "jackson" }
jackson-annotations = { group = "com.fasterxml.jackson.core", name = "jackson-annotations", version.ref = "jackson" }
jackson-datatype-jdk8 = { group = "com.fasterxml.jackson.datatype", name = "jackson-datatype-jdk8", version.ref = "jackson" }
jackson-datatype-jsr310 = { group = "com.fasterxml.jackson.datatype", name = "jackson-datatype-jsr310", version.ref = "jackson" }
slf4j-api = { group = "org.slf4j", name = "slf4j-api", version.ref = "slf4j-api" }
jakarta-validation = { group = "jakarta.validation", name = "jakarta.validation-api", version.ref = "jakarta-validation-api" }
jakarta-inject = { group = "jakarta.inject", name = "jakarta.inject-api", version.ref = "jakarta-inject-api" }
```

### B-3: Analyze Extracted Dependencies

After extraction, categorize every library:

| Category | Rule | Build Scope |
|----------|------|-------------|
| **PA SDK** | SDK API classes | `compileOnly` — always |
| **PA-provided, plugin-usable** | Visible to class identity tests (Spike A) | `compileOnly` — don't bundle |
| **PA-provided, plugin-invisible** | Not visible to class identity tests (Spike A) | Must bundle if needed |
| **Not in PA** | Only in our code | `implementation` — bundle in shadow JAR |

Key question this step answers: **Which PA libraries are safe to declare
`compileOnly` and which must be bundled?**

This depends on Spike A results. If PA uses parent-first delegation (likely),
then all PA libs are visible. If child-first (unlikely), only explicitly
exported libs are visible.

### B-4: Design Build Integration Strategy

Three options (recommend A):

#### Option A: Gradle Version Catalog Overlay

Extend the existing `gradle/libs.versions.toml` with a `[pa-provided]` section:

```toml
# In gradle/libs.versions.toml

# --- PA-provided versions (from scripts/pa-extract-deps.sh) ---
[versions]
pa-jackson = "2.17.0"              # PA 9.0.1 — CONFIRMED
pa-slf4j = "1.7.36"                 # PA 9.0.1 — CONFIRMED (SLF4J 1.x)
pa-jakarta-validation = "3.1.1"     # PA 9.0.1 — CONFIRMED
pa-jakarta-inject = "2.0.1"         # PA 9.0.1 — CONFIRMED
# NOTE: SnakeYAML NOT in PA — must be implementation, not compileOnly
# NOTE: PA uses Log4j2 2.24.3, NOT Logback

[libraries]
pa-jackson-databind = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version.ref = "pa-jackson" }
pa-jackson-core = { group = "com.fasterxml.jackson.core", name = "jackson-core", version.ref = "pa-jackson" }
pa-jackson-annotations = { group = "com.fasterxml.jackson.core", name = "jackson-annotations", version.ref = "pa-jackson" }
pa-slf4j-api = { group = "org.slf4j", name = "slf4j-api", version.ref = "pa-slf4j" }
pa-jakarta-validation = { group = "jakarta.validation", name = "jakarta.validation-api", version.ref = "pa-jakarta-validation" }
pa-jakarta-inject = { group = "jakarta.inject", name = "jakarta.inject-api", version.ref = "pa-jakarta-inject" }
```

```kotlin
// adapter-pingaccess/build.gradle.kts
dependencies {
    implementation(project(":core"))

    // PA-provided — compile against PA's exact versions, don't bundle
    compileOnly(libs.pa.jackson.databind)
    compileOnly(libs.pa.jackson.core)
    compileOnly(libs.pa.slf4j.api)
    compileOnly(libs.pa.jakarta.validation)
    compileOnly(libs.pa.jakarta.inject)
    compileOnly(libs.pa.pingaccess.sdk)
}

// Shadow JAR — exclude all compileOnly dependencies
tasks.shadowJar {
    // Jackson, SLF4J, Jakarta — all excluded (PA-provided)
    // Only bundles: core engine, JSLT, JSON Schema Validator, SnakeYAML (if not PA-provided)
}
```

**Pros:** Simple, uses existing Gradle infrastructure, version catalog is
already the project standard.

**Cons:** PA-provided versions are mixed with project-own versions in the
same file. Manageable with comments.

#### Option B: Separate Gradle Platform Module

Create a dedicated `pa-platform-9.0/` module:

```
pa-platform-9.0/
└── build.gradle.kts   # Gradle platform (BOM)
```

```kotlin
// pa-platform-9.0/build.gradle.kts
plugins { `java-platform` }
dependencies {
    constraints {
        api("com.fasterxml.jackson.core:jackson-databind:2.17.0")
        api("com.fasterxml.jackson.core:jackson-core:2.17.0")
        // ...
    }
}
```

```kotlin
// adapter-pingaccess/build.gradle.kts
dependencies {
    compileOnly(platform(project(":pa-platform-9.0")))
    compileOnly("com.fasterxml.jackson.core:jackson-databind")  // version from platform
}
```

**Pros:** Clean separation. Platform module is self-documenting.

**Cons:** Extra module. Overkill for a single PA version target.

#### Option C: Generated TOML File (Automated)

The extraction script directly generates a TOML file that is imported into
the version catalog:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    versionCatalogs {
        create("pa") {
            from(files("gradle/pa-9.0-provided-dependencies.toml"))
        }
    }
}
```

```kotlin
// adapter-pingaccess/build.gradle.kts
dependencies {
    compileOnly(pa.jackson.databind)  // from PA catalog
}
```

**Pros:** Fully automated — run script, commit TOML, build picks it up.
Separate namespace (`pa.` vs `libs.`).

**Cons:** Multiple version catalogs can be confusing. Requires Gradle 7.4.1+
for `from(files(...))` (we have Gradle 8.x — fine).

**Recommendation:** Option A for simplicity now; Option C if we ever support
multiple PA versions.

### B-5: Create Build Integration

Based on the chosen strategy (likely Option A), update:

1. `gradle/libs.versions.toml` — add PA-provided dependency versions
2. `adapter-pingaccess/build.gradle.kts` — declare `compileOnly` dependencies
3. Shadow JAR config — exclude PA-provided deps

### B-6: Prototype Adapter Build

Validate that the adapter module compiles and tests pass with `compileOnly`
Jackson:

```bash
./gradlew :adapter-pingaccess:compileJava
./gradlew :adapter-pingaccess:test
./gradlew :adapter-pingaccess:shadowJar
```

Check that the shadow JAR does **not** contain Jackson classes:
```bash
jar tf adapter-pingaccess/build/libs/*-shadow.jar | grep -c "com/fasterxml/jackson"
# Expected: 0
```

Check JAR size:
```bash
ls -lh adapter-pingaccess/build/libs/*-shadow.jar
# Expected: < 5 MB (no Jackson, no SLF4J)
```

### B-7: Implement Runtime Version Guard

Add a cheap version check in `configure()`:

```java
private void verifyPaProvidedVersions() {
    Logger log = LoggerFactory.getLogger(MessageTransformRule.class);

    // Jackson — compiled against this version (injected by Gradle at build time)
    String compiledJackson = "${paJacksonVersion}"; // or from a properties file
    String runtimeJackson = ObjectMapper.class.getPackage().getImplementationVersion();

    if (runtimeJackson != null && !runtimeJackson.equals(compiledJackson)) {
        log.warn("⚠️ Jackson version mismatch: plugin compiled against {} "
               + "but PA runtime provides {}. Behavior may be unpredictable. "
               + "Deploy plugin version matching your PA version.",
                 compiledJackson, runtimeJackson);
    } else {
        log.info("Jackson version OK: {}", runtimeJackson);
    }
}
```

**Build-time version injection:** The compiled-against version can be:
- A constant in a generated Java file (Gradle `processResources` filter)
- A `version.properties` resource on the classpath
- A compile-time constant via annotation processing

Simplest approach:
```kotlin
// adapter-pingaccess/build.gradle.kts
tasks.processResources {
    filesMatching("pa-build-info.properties") {
        expand("jacksonVersion" to libs.versions.pa.jackson.get())
    }
}
```

```properties
# src/main/resources/pa-build-info.properties
jackson.version=${jacksonVersion}
pa.target.version=9.0.1
plugin.version=${project.version}
```

### B-8: Document Version-Locked Release Strategy

Add to AGENTS.md or a new `docs/operations/pa-version-alignment.md`:

```markdown
## PA Version Alignment

The adapter-pingaccess module is version-locked to a specific PingAccess
major.minor version. Each adapter release targets exactly one PA version.

| Adapter Version | PA Version | Jackson | Notes |
|----------------|------------|---------|-------|
| 9.0.0          | 9.0.1      | 2.17.0  | Initial release |
| 9.1.0          | 9.1.x      | TBD     | Run extraction script |

### Upgrade Procedure

When a new PA version is released:

1. Pull the new Docker image: `docker pull pingidentity/pingaccess:<new-version>`
2. Run the extraction script: `./scripts/pa-extract-deps.sh pingidentity/pingaccess:<new-version>`
3. Compare output with current `gradle/libs.versions.toml` PA-provided versions
4. Update versions in `libs.versions.toml`
5. Run full build + tests: `./gradlew spotlessApply check`
6. Fix any compilation errors from API changes
7. Update `pa-build-info.properties` with new PA version
8. Release new adapter version
```

### B-9: Update Spec and SDK Guide

Same impact list as Spike A, Step A-9. The follow-up spec amendments are
conditional on both spikes confirming the approach.

---

## Scope of Dependencies to Analyze

### Must Resolve (Critical Path)

These determine whether the adapter can drop Jackson relocation:

| Library | Version | Status |
|---------|---------|--------|
| `jackson-databind` | **2.17.0** | ✅ Confirmed |
| `jackson-core` | **2.17.0** | ✅ Confirmed |
| `jackson-annotations` | **2.17.0** | ✅ Confirmed |
| `jackson-dataformat-yaml` | ❌ | **Not shipped** |

### Should Resolve (Build Optimization) — ✅ Resolved

| Library | Version | Status |
|---------|---------|--------|
| `snakeyaml` | ❌ | **Not shipped** — must bundle |
| `slf4j-api` | **1.7.36** | ✅ Confirmed (SLF4J 1.x) |
| `jakarta.validation-api` | **3.1.1** | ✅ Confirmed |
| `jakarta.inject-api` | **2.0.1** | ✅ Confirmed |
| `logback-classic` / `logback-core` | ❌ | **Not shipped** — PA uses Log4j2 2.24.3 |

### Nice to Know (Future Adapters) — ✅ Resolved

| Library | Version | Status |
|---------|---------|--------|
| `guava` | **33.1.0-jre** | ✅ Confirmed |
| `commons-lang3` | **3.14.0** | ✅ Confirmed |
| `netty-*` (not Jetty) | **4.1.127.Final** | ✅ Confirmed (PA uses Netty, not Jetty) |
| `spring-*` | **6.2.11** | ✅ Confirmed (spring-context, -beans, -core, etc.) |
| `bouncy-castle` | via `resource/bc/` dir | ✅ Confirmed (separate from lib/) |

---

## Impact on Core Engine (`message-xform-core`)

### The JSLT Question

The core engine uses Jackson internally (via JSLT, `SpecParser`, `TransformEngine`).
If the adapter declares Jackson as `compileOnly`, the **core module still
needs Jackson as `implementation`** — core is a library that must be
self-contained.

However, when core is bundled in the shadow JAR and Jackson is NOT relocated:
- Core's Jackson classes resolve to PA's classloader (parent-first delegation)
- Core and the adapter use the same `JsonNode` class
- No boundary conversion needed anywhere

**Key constraint:** The core module's `implementation` dependency on Jackson
must match PA's version exactly. This is enforced by:
1. The version catalog (single source of truth for Jackson version)
2. The shadow JAR `exclude` rule (Jackson from core is excluded because PA
   provides it)

### Shadow JAR New Configuration

```kotlin
// adapter-pingaccess/build.gradle.kts
tasks.shadowJar {
    // Merge service files (SPI)
    mergeServiceFiles()

    // Exclude PA-provided dependencies
    // Jackson — provided by PA runtime
    exclude("com/fasterxml/jackson/**")
    // SLF4J — provided by PA runtime
    exclude("org/slf4j/**")
    // Jakarta — provided by PA runtime
    exclude("jakarta/**")

    // Keep: JSLT, SnakeYAML (unless PA-provided — check in B-3),
    //       JSON Schema Validator, core engine classes
}
```

**Important:** Even though core has `implementation("jackson-databind")`, the
shadow JAR's `exclude` directive strips Jackson classes from the final JAR.
At runtime, these classes resolve from PA's classloader instead.

---

## Risks & Mitigations

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| PA ships a Jackson version with breaking API changes vs. what JSLT expects | Very low — Jackson is extremely stable across minor versions | Pin JSLT version to one that supports PA's Jackson range; runtime version guard warns on mismatch |
| PA patches (9.0.1 → 9.0.2) change Jackson version | Low — patches rarely change dependencies | Runtime version guard warns; extraction script can re-run to verify |
| Core engine is tested against different Jackson version than PA provides | Medium | CI matrix: test core against PA's Jackson version specifically |
| SnakeYAML is NOT in PA — must still bundle | Medium | Spike will determine this; shadow JAR includes it if needed |
| Future PA version drops a library we depend on | Low | Runtime version guard catches missing classes immediately; extraction script run on upgrade |
| JSLT has Jackson version floor requirements | Low — JSLT 0.1.14 targets Jackson 2.10+ | Verify JSLT's Jackson compatibility range |

---

## Success Criteria

- [ ] Extraction script runs against PA 9.0.1 Docker image and produces
      structured output
- [ ] Jackson version in PA 9.0.1 is known and documented
- [ ] Full list of PA-provided libraries is generated and categorized
- [ ] Build integration strategy is chosen and documented
- [ ] Adapter compiles with `compileOnly` Jackson (prototype validated)
- [ ] Shadow JAR contains zero Jackson classes
- [ ] Shadow JAR size is < 5 MB
- [ ] Runtime version guard is designed and implementation plan exists
- [ ] Version-locked release strategy is documented
- [ ] JSLT compatibility with PA's Jackson version is verified

---

## Dependencies

- **Spike A** (Classloader Model) — ✅ **Resolved.** Confirmed flat classpath
  with no classloader isolation. `compileOnly` will work. All PA libraries
  are visible to plugins.
- **Docker** — must be available to pull/run PA image.
- **PA Docker image** — `pingidentity/pingaccess:latest` (currently 9.0.1.0).

---

## Combined Outcome (Spike A + Spike B)

When both spikes are complete, we will have:

| Deliverable | Location |
|-------------|----------|
| Verified classloader model | SDK guide §9 (updated) |
| PA library visibility matrix | SDK guide §9 (new subsection) |
| PA 9.0.1 dependency version list | `gradle/pa-9.0-provided-dependencies.toml` or `libs.versions.toml` PA section |
| Dependency extraction script | `scripts/pa-extract-deps.sh` |
| ADR on dependency strategy | `docs/decisions/ADR-0031-*.md` |
| Version-locked release strategy | `docs/operations/pa-version-alignment.md` or AGENTS.md |
| Updated FR-002-09 | `docs/architecture/features/002/spec.md` |
| Updated SDK guide §6, §9 | `docs/architecture/features/002/pingaccess-sdk-guide.md` |

### If Both Spikes Confirm the Approach (Expected)

1. **Jackson relocation is removed** — `compileOnly`, no shading
2. **Boundary conversion is removed** — `Identity.getAttributes()` returns same
   `JsonNode` class
3. **Dual ObjectMapper is removed** — single `ObjectMapper` instance
4. **Shadow JAR shrinks** from ~15-20 MB to ~3-5 MB
5. **`buildSessionContext()` simplifies** — direct `session.set(key, paNode)` works
6. **Constraint 8 is removed** — no cross-classloader conversion needed
7. **Spec amendments** cascade through FR-002-06, FR-002-09, NFR-002-02

### If Either Spike Rejects the Approach (Unlikely)

1. **Current relocation design stands** — no spec changes needed
2. **Document why** in an ADR — future contributors don't re-explore
3. **Classloader model is still documented** — valuable regardless

---

*Created: 2026-02-11 | Owner: Ivan | Feature: 002 — PingAccess Adapter*
