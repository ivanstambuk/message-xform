#!/usr/bin/env bash
# =============================================================================
# PA Dependency Extraction Script
# =============================================================================
#
# Extracts exact dependency versions from a PingAccess Docker image.
# Produces a Gradle version catalog (TOML) and summary report (Markdown).
#
# Usage:
#   ./scripts/pa-extract-deps.sh [image_tag]
#
# Arguments:
#   image_tag — Docker image (default: pingidentity/pingaccess:latest)
#
# Output:
#   gradle/pa-provided.versions.toml   — Gradle-compatible version catalog
#   docs/reference/pa-provided-deps.md — Human-readable summary
#
# Re-run this script when PingAccess upgrades to update the adapter's
# compileOnly dependency versions. See ADR-0031.
# =============================================================================
set -euo pipefail

# --- Configuration ---
IMAGE="${1:-pingidentity/pingaccess:latest}"
PA_LIB_DIR="/opt/server/lib"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TOML_OUTPUT="$PROJECT_ROOT/gradle/pa-provided.versions.toml"
MD_OUTPUT="$PROJECT_ROOT/docs/reference/pa-provided-deps.md"

TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# --- ANSI Colors ---
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "${BOLD}📦 PA Dependency Extraction${NC}"
echo -e "   Image:  ${CYAN}$IMAGE${NC}"
echo -e "   Target: ${CYAN}$PA_LIB_DIR${NC}"
echo ""

# --- Step 1: Extract JARs from Docker image ---
echo -e "${BOLD}Step 1:${NC} Creating temporary container..."
CONTAINER_ID=$(docker create "$IMAGE" 2>/dev/null)
if [[ -z "$CONTAINER_ID" ]]; then
    echo -e "${RED}❌ Failed to create container from image: $IMAGE${NC}"
    echo "   Is Docker running? Has the image been pulled?"
    exit 1
fi
echo "   Container: $CONTAINER_ID"

echo -e "${BOLD}Step 2:${NC} Copying $PA_LIB_DIR..."
docker cp "$CONTAINER_ID:$PA_LIB_DIR" "$TEMP_DIR/lib" 2>/dev/null
docker rm "$CONTAINER_ID" > /dev/null 2>&1

# Also extract run.sh for PA version detection
docker create "$IMAGE" > /dev/null 2>&1 && {
    CONTAINER_ID2=$(docker create "$IMAGE" 2>/dev/null)
    docker cp "$CONTAINER_ID2:/opt/out/instance/bin/run.sh" "$TEMP_DIR/run.sh" 2>/dev/null || true
    docker rm "$CONTAINER_ID2" > /dev/null 2>&1
}

JAR_COUNT=$(find "$TEMP_DIR/lib" -name "*.jar" | wc -l)
echo -e "   Found ${GREEN}$JAR_COUNT${NC} JAR files"
echo ""

# --- Step 2: Detect PA version ---
PA_VERSION="unknown"
# Try to get version from SDK JAR filename
SDK_JAR=$(find "$TEMP_DIR/lib" -name "pingaccess-sdk-*.jar" -print -quit)
if [[ -n "$SDK_JAR" ]]; then
    SDK_FILENAME=$(basename "$SDK_JAR")
    if [[ "$SDK_FILENAME" =~ pingaccess-sdk-([0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?).jar ]]; then
        PA_VERSION="${BASH_REMATCH[1]}"
    fi
fi
echo -e "${BOLD}Step 3:${NC} Detected PA version: ${GREEN}$PA_VERSION${NC}"
echo ""

# --- Step 3: Extract version info from each JAR ---
echo -e "${BOLD}Step 4:${NC} Extracting dependency versions..."

# Output format: groupId|artifactId|version|source|filename
RESULTS_FILE="$TEMP_DIR/results.tsv"

extract_version() {
    local jar_file="$1"
    local filename
    filename=$(basename "$jar_file")
    local found=false

    # Strategy 1: pom.properties (most reliable — Maven coordinates)
    local pom_files
    pom_files=$(unzip -l "$jar_file" 2>/dev/null | grep "pom.properties" | awk '{print $4}') || true
    if [[ -n "$pom_files" ]]; then
        for pom_path in $pom_files; do
            local pom_props
            pom_props=$(unzip -p "$jar_file" "$pom_path" 2>/dev/null) || true
            if [[ -n "$pom_props" ]]; then
                local group artifact version
                group=$(echo "$pom_props" | grep "^groupId=" | head -1 | cut -d= -f2 | tr -d '\r\n') || true
                artifact=$(echo "$pom_props" | grep "^artifactId=" | head -1 | cut -d= -f2 | tr -d '\r\n') || true
                version=$(echo "$pom_props" | grep "^version=" | head -1 | cut -d= -f2 | tr -d '\r\n') || true
                if [[ -n "$group" && -n "$artifact" && -n "$version" ]]; then
                    echo "${group}|${artifact}|${version}|pom.properties|${filename}"
                    found=true
                    break
                fi
            fi
        done
    fi

    # Strategy 2: MANIFEST.MF
    if [[ "$found" == "false" ]]; then
        local manifest
        manifest=$(unzip -p "$jar_file" "META-INF/MANIFEST.MF" 2>/dev/null) || true
        if [[ -n "$manifest" ]]; then
            local impl_version bundle_version impl_title bundle_name
            impl_version=$(echo "$manifest" | grep "^Implementation-Version:" | head -1 | awk '{print $2}' | tr -d '\r\n') || true
            impl_title=$(echo "$manifest" | grep "^Implementation-Title:" | head -1 | cut -d: -f2- | xargs 2>/dev/null | tr -d '\r\n') || true
            bundle_name=$(echo "$manifest" | grep "^Bundle-SymbolicName:" | head -1 | awk '{print $2}' | tr -d '\r\n') || true
            bundle_version=$(echo "$manifest" | grep "^Bundle-Version:" | head -1 | awk '{print $2}' | tr -d '\r\n') || true

            local name="${impl_title:-${bundle_name:-unknown}}"
            local ver="${impl_version:-${bundle_version:-unknown}}"

            if [[ "$ver" != "unknown" && -n "$ver" ]]; then
                echo "unknown|${name}|${ver}|MANIFEST.MF|${filename}"
                found=true
            fi
        fi
    fi

    # Strategy 3: Filename regex
    if [[ "$found" == "false" ]]; then
        if [[ "$filename" =~ ^(.+)-([0-9]+\.[0-9]+.*)\.jar$ ]]; then
            echo "unknown|${BASH_REMATCH[1]}|${BASH_REMATCH[2]}|filename|${filename}"
        else
            echo "unknown|${filename%.jar}|unknown|none|${filename}"
        fi
    fi
}

for jar in "$TEMP_DIR/lib"/*.jar; do
    extract_version "$jar" >> "$RESULTS_FILE"
done

# Sort by groupId, then artifactId
sort -t'|' -k1,1 -k2,2 "$RESULTS_FILE" > "$TEMP_DIR/sorted.tsv"

EXTRACTED=$(wc -l < "$TEMP_DIR/sorted.tsv")
echo -e "   Extracted ${GREEN}$EXTRACTED${NC} entries"
echo ""

# --- Step 4: Generate TOML output ---
echo -e "${BOLD}Step 5:${NC} Generating Gradle version catalog..."

# Define plugin-relevant libraries (the ones the adapter cares about)
# These get promoted to named entries in the TOML; everything else goes to the
# full inventory section as comments.

generate_toml() {
    local date_stamp
    date_stamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    cat << TOML_HEADER
# =============================================================================
# PingAccess $PA_VERSION — PA-Provided Dependencies
# =============================================================================
# Generated: $date_stamp
# Source image: $IMAGE
# Script: scripts/pa-extract-deps.sh
# JAR count: $JAR_COUNT
#
# These libraries ship in /opt/server/lib/ and are available to plugins on
# the flat classpath (ADR-0031). Adapter modules MUST declare them as
# compileOnly with these exact versions — do NOT bundle them.
#
# To regenerate after a PA upgrade:
#   ./scripts/pa-extract-deps.sh pingidentity/pingaccess:<new-version>
# =============================================================================

[metadata]
pa-version = "$PA_VERSION"
image = "$IMAGE"
generated = "$date_stamp"
jar-count = $JAR_COUNT

# -----------------------------------------------------------------------------
# Plugin-relevant versions (adapter compileOnly dependencies)
# -----------------------------------------------------------------------------
[versions]
TOML_HEADER

    # Extract specific versions from the results
    local jackson_version slf4j_version log4j_version
    local jakarta_validation_version jakarta_inject_version
    local hibernate_validator_version commons_lang3_version guava_version
    local jackson_datatype_jdk8_version jackson_datatype_jsr310_version

    jackson_version=$(grep "jackson-databind" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3) || true
    slf4j_version=$(grep "|slf4j-api|" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3) || true
    log4j_version=$(grep "|log4j-api|" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3) || true
    jakarta_validation_version=$(grep "jakarta.validation-api" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3) || true
    jakarta_inject_version=$(grep "jakarta.inject-api" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3) || true
    hibernate_validator_version=$(grep "hibernate-validator" "$TEMP_DIR/sorted.tsv" | grep -v "cdi\|osgi\|internal" | head -1 | cut -d'|' -f3) || true
    commons_lang3_version=$(grep "commons-lang3" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3) || true
    guava_version=$(grep "|guava|" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3) || true

    echo "# Jackson (core dependency — SDK API returns JsonNode)"
    echo "pa-jackson = \"${jackson_version:-UNKNOWN}\""
    echo ""
    echo "# Logging"
    echo "pa-slf4j = \"${slf4j_version:-UNKNOWN}\"       # SLF4J API version"
    echo "pa-log4j = \"${log4j_version:-UNKNOWN}\"       # Log4j2 (PA's SLF4J backend)"
    echo ""
    echo "# Validation & DI"
    echo "pa-jakarta-validation = \"${jakarta_validation_version:-UNKNOWN}\""
    echo "pa-jakarta-inject = \"${jakarta_inject_version:-UNKNOWN}\""
    [[ -n "$hibernate_validator_version" ]] && echo "pa-hibernate-validator = \"$hibernate_validator_version\""
    echo ""
    echo "# Utilities"
    [[ -n "$commons_lang3_version" ]] && echo "pa-commons-lang3 = \"$commons_lang3_version\""
    [[ -n "$guava_version" ]] && echo "pa-guava = \"$guava_version\""
    echo ""

    cat << 'TOML_LIBRARIES'
# PA SDK
pa-sdk = "FILL_FROM_SDK_JAR"

# -----------------------------------------------------------------------------
# Library aliases (for use in build.gradle.kts as compileOnly dependencies)
# -----------------------------------------------------------------------------
[libraries]
pa-jackson-databind = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version.ref = "pa-jackson" }
pa-jackson-core = { group = "com.fasterxml.jackson.core", name = "jackson-core", version.ref = "pa-jackson" }
pa-jackson-annotations = { group = "com.fasterxml.jackson.core", name = "jackson-annotations", version.ref = "pa-jackson" }
pa-jackson-datatype-jdk8 = { group = "com.fasterxml.jackson.datatype", name = "jackson-datatype-jdk8", version.ref = "pa-jackson" }
pa-jackson-datatype-jsr310 = { group = "com.fasterxml.jackson.datatype", name = "jackson-datatype-jsr310", version.ref = "pa-jackson" }
pa-slf4j-api = { group = "org.slf4j", name = "slf4j-api", version.ref = "pa-slf4j" }
pa-jakarta-validation = { group = "jakarta.validation", name = "jakarta.validation-api", version.ref = "pa-jakarta-validation" }
pa-jakarta-inject = { group = "jakarta.inject", name = "jakarta.inject-api", version.ref = "pa-jakarta-inject" }
TOML_LIBRARIES

    # Fill in SDK version from extracted JARs
    if [[ -n "$SDK_JAR" ]]; then
        local sdk_version
        sdk_version=$(grep "pingaccess-sdk" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3)
        if [[ -n "$sdk_version" ]]; then
            # Can't easily sed the TOML_LIBRARIES block, so append as comment
            echo ""
            echo "# PA SDK — version extracted: $sdk_version"
            echo "# pa-sdk = { group = \"com.pingidentity.pingaccess\", name = \"pingaccess-sdk\", version.ref = \"pa-sdk\" }"
        fi
    fi

    echo ""
    echo "# ============================================================================="
    echo "# Full Inventory (all $JAR_COUNT JARs in /opt/server/lib/)"
    echo "# ============================================================================="
    echo "# Format: groupId : artifactId : version  (source: detection method)"
    echo "#"
    while IFS='|' read -r group artifact version source filename; do
        printf "# %-45s %-35s %-20s (%s)\n" "$group" "$artifact" "$version" "$source"
    done < "$TEMP_DIR/sorted.tsv"
}

generate_toml > "$TOML_OUTPUT"

# Fix the SDK version placeholder in the generated file
if [[ -n "$SDK_JAR" ]]; then
    sdk_ver=$(grep "pingaccess-sdk" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3)
    if [[ -n "$sdk_ver" ]]; then
        sed -i "s/pa-sdk = \"FILL_FROM_SDK_JAR\"/pa-sdk = \"$sdk_ver\"/" "$TOML_OUTPUT"
    fi
fi

echo -e "   Written: ${GREEN}$TOML_OUTPUT${NC}"

# --- Step 5: Generate Markdown summary ---
echo -e "${BOLD}Step 6:${NC} Generating Markdown summary..."

generate_md() {
    local date_stamp
    date_stamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    cat << MD_HEADER
# PingAccess $PA_VERSION — Provided Dependencies

> Generated: $date_stamp
> Source image: \`$IMAGE\`
> Script: \`scripts/pa-extract-deps.sh\`
> Total JARs: $JAR_COUNT

This document lists all libraries shipped in \`/opt/server/lib/\` of the
PingAccess Docker image. Libraries on this list are available to plugins
on the flat classpath (ADR-0031) and MUST be declared as \`compileOnly\`
in the adapter's build configuration — they MUST NOT be bundled.

## Plugin-Relevant Dependencies

These are the libraries the adapter directly uses or must be aware of:

| Library | Group | Version | Build Scope |
|---------|-------|---------|-------------|
MD_HEADER

    # Extract key deps
    while IFS='|' read -r group artifact version source filename; do
        case "$artifact" in
            jackson-databind|jackson-core|jackson-annotations)
                echo "| \`$artifact\` | \`$group\` | **$version** | \`compileOnly\` |"
                ;;
            jackson-datatype-jdk8|jackson-datatype-jsr310)
                echo "| \`$artifact\` | \`$group\` | $version | \`compileOnly\` |"
                ;;
            slf4j-api)
                echo "| \`$artifact\` | \`$group\` | **$version** | \`compileOnly\` |"
                ;;
            log4j-api|log4j-core|log4j-slf4j-impl|log4j-slf4j2-impl)
                echo "| \`$artifact\` | \`$group\` | $version | PA internal (logging backend) |"
                ;;
            jakarta.validation-api|jakarta.inject-api)
                echo "| \`$artifact\` | \`$group\` | $version | \`compileOnly\` |"
                ;;
            hibernate-validator)
                echo "| \`$artifact\` | \`$group\` | $version | \`compileOnly\` |"
                ;;
            commons-lang3)
                echo "| \`$artifact\` | \`$group\` | $version | Available (optional) |"
                ;;
            guava)
                echo "| \`$artifact\` | \`$group\` | $version | Available (optional) |"
                ;;
            pingaccess-sdk*)
                echo "| \`$artifact\` | \`$group\` | **$version** | \`compileOnly\` |"
                ;;
        esac
    done < "$TEMP_DIR/sorted.tsv"

    cat << 'MD_NOT_SHIPPED'

### Not Shipped (MUST bundle)

These libraries are **not** in PA's `/opt/server/lib/`:

| Library | Reason |
|---------|--------|
| `snakeyaml` | Core engine needs it for YAML config parsing |
| `jslt` | Core engine's expression engine |
| `json-schema-validator` | Core engine's schema validation |
| `logback-classic` | PA uses Log4j2, not Logback |
| `jackson-dataformat-yaml` | Not in PA's Jackson bundle |

MD_NOT_SHIPPED

    echo "## Full Inventory"
    echo ""
    echo "All $JAR_COUNT JARs in \`/opt/server/lib/\`:"
    echo ""
    echo "| # | Group | Artifact | Version | Source |"
    echo "|---|-------|----------|---------|--------|"

    local i=0
    while IFS='|' read -r group artifact version source filename; do
        i=$((i + 1))
        echo "| $i | \`$group\` | \`$artifact\` | $version | $source |"
    done < "$TEMP_DIR/sorted.tsv"

    echo ""
    echo "---"
    echo ""
    echo "*Generated by \`scripts/pa-extract-deps.sh\` — re-run after PA upgrade.*"
}

generate_md > "$MD_OUTPUT"
echo -e "   Written: ${GREEN}$MD_OUTPUT${NC}"

# --- Step 6: Version alignment check ---
echo ""
echo -e "${BOLD}Step 7:${NC} Version alignment check..."

# Read current libs.versions.toml
CURRENT_TOML="$PROJECT_ROOT/gradle/libs.versions.toml"
if [[ -f "$CURRENT_TOML" ]]; then
    current_jackson=$(grep '^jackson = ' "$CURRENT_TOML" | cut -d'"' -f2)
    current_slf4j=$(grep '^slf4j = ' "$CURRENT_TOML" | cut -d'"' -f2)

    pa_jackson=$(grep "jackson-databind" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3)
    pa_slf4j=$(grep "|slf4j-api|" "$TEMP_DIR/sorted.tsv" | head -1 | cut -d'|' -f3)

    echo ""
    echo "   Library alignment with libs.versions.toml:"
    echo "   ┌─────────────────┬──────────────┬──────────────┬────────┐"
    echo "   │ Library         │ Current      │ PA Provides  │ Status │"
    echo "   ├─────────────────┼──────────────┼──────────────┼────────┤"

    if [[ "$current_jackson" == "$pa_jackson" ]]; then
        echo -e "   │ Jackson         │ $current_jackson      │ $pa_jackson      │ ${GREEN}  ✅  ${NC} │"
    else
        echo -e "   │ Jackson         │ $current_jackson      │ $pa_jackson      │ ${RED}  ⚠️  ${NC} │"
    fi

    if [[ "$current_slf4j" == "$pa_slf4j" ]]; then
        echo -e "   │ SLF4J           │ $current_slf4j     │ $pa_slf4j     │ ${GREEN}  ✅  ${NC} │"
    else
        echo -e "   │ SLF4J           │ $current_slf4j     │ $pa_slf4j     │ ${YELLOW}  ℹ️  ${NC} │"
    fi

    echo "   └─────────────────┴──────────────┴──────────────┴────────┘"

    if [[ "$current_jackson" != "$pa_jackson" ]]; then
        echo ""
        echo -e "   ${YELLOW}⚠️  Jackson version mismatch!${NC}"
        echo "   Core compiles against $current_jackson but PA provides $pa_jackson."
        echo "   Update libs.versions.toml: jackson = \"$pa_jackson\""
    fi

    if [[ "$current_slf4j" != "$pa_slf4j" ]]; then
        echo ""
        echo -e "   ${YELLOW}ℹ️  SLF4J version difference (expected — Option B)${NC}"
        echo "   Core uses SLF4J $current_slf4j (2.x). PA provides $pa_slf4j (1.x)."
        echo "   This is accepted per ADR-0031 Option B. Standalone adapter uses 2.x."
        echo "   PA adapter gets 1.x at runtime — Logger API is binary compatible."
    fi
fi

echo ""
echo -e "${GREEN}${BOLD}✅ Extraction complete.${NC}"
echo "   TOML:     $TOML_OUTPUT"
echo "   Summary:  $MD_OUTPUT"
echo ""
echo "   Next steps:"
echo "   1. Review the generated TOML and Markdown files"
echo "   2. If Jackson version differs from libs.versions.toml, update it"
echo "   3. Run: ./gradlew spotlessApply check"
echo "   4. Commit: git add gradle/pa-provided.versions.toml docs/reference/pa-provided-deps.md"
