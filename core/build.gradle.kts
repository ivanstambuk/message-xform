import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Core runtime — all implementation (internal, relocated by Shadow)
    implementation(catalog.findLibrary("jackson-databind").get())
    implementation(catalog.findLibrary("jackson-dataformat-yaml").get())
    implementation(catalog.findLibrary("jslt").get())
    implementation(catalog.findLibrary("json-schema-validator").get())

    // SLF4J — compile-only (provided by adapter / gateway at runtime)
    compileOnly(catalog.findLibrary("slf4j-api").get())

    // Test
    testImplementation(catalog.findLibrary("mockito-core").get())
    testImplementation(catalog.findLibrary("mockito-junit-jupiter").get())
    testImplementation(catalog.findLibrary("logback-classic").get())
    testImplementation(catalog.findLibrary("slf4j-api").get())
}

// --- Shadow JAR configuration (ADR-0032, ADR-0033) ---
// Bundles and relocates Jackson, JSLT, JSON Schema Validator, and SnakeYAML
// inside core's JAR so adapters are free from Jackson version coupling.

tasks.withType<ShadowJar>().configureEach {
    archiveClassifier.set("")          // shadow JAR = primary artifact
    mergeServiceFiles()                // merge META-INF/services (SPI)

    // Relocate all internal dependencies under io.messagexform.internal.*
    relocate("com.fasterxml.jackson", "io.messagexform.internal.jackson")
    relocate("com.schibsted.spt.data.jslt", "io.messagexform.internal.jslt")
    relocate("com.networknt", "io.messagexform.internal.networknt")
    relocate("org.yaml.snakeyaml", "io.messagexform.internal.snakeyaml")
    relocate("com.ethlo.time", "io.messagexform.internal.ethlo")

    // Do NOT relocate SLF4J — it is gateway-provided
    // Do NOT relocate io.messagexform.core — that's us

    // Exclude SLF4J from the shadow JAR (it's compileOnly)
    exclude("org/slf4j/**")
}

// Wire the shadow JAR as the primary artifact for inter-project consumption.
// This ensures `:adapter-standalone` gets the relocated JAR, not the thin one.
val shadowJarTask = tasks.named<ShadowJar>("shadowJar")

configurations {
    apiElements {
        outgoing {
            artifacts.clear()
            artifact(shadowJarTask)
        }
    }
    runtimeElements {
        outgoing {
            artifacts.clear()
            artifact(shadowJarTask)
        }
    }
}

// --- SLF4J 1.x compatibility guard (ADR-0032) ---
// Core compiles against SLF4J 2.x but must only use 1.x-compatible APIs,
// because PingAccess provides SLF4J 1.7.x at runtime. This task scans
// main sources and fails the build if any 2.x-only fluent API is used.

tasks.register("checkSlf4jCompat") {
    description = "Ensures core main sources do not use SLF4J 2.x-only fluent API"
    group = "verification"

    val mainSources = fileTree("src/main/java") { include("**/*.java") }
    inputs.files(mainSources)

    doLast {
        val forbidden = listOf(
            ".atInfo(", ".atWarn(", ".atDebug(", ".atError(", ".atTrace(",
            ".makeLoggingEventBuilder(",
            "import org.slf4j.event.KeyValuePair",
            "import org.slf4j.spi.LoggingEventBuilder",
        )
        val violations = mutableListOf<String>()

        mainSources.forEach { file ->
            file.readLines().forEachIndexed { idx, line ->
                // Skip comments
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed

                forbidden.forEach { pattern ->
                    if (line.contains(pattern)) {
                        violations.add("  ${file.name}:${idx + 1}: $pattern")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "SLF4J 2.x-only API usage detected in core main sources.\n" +
                "PingAccess provides SLF4J 1.7.x — only 1.x-compatible methods are allowed.\n" +
                "Violations:\n${violations.joinToString("\n")}"
            )
        }
        logger.lifecycle("✓ SLF4J compatibility check passed (${mainSources.files.size} files scanned)")
    }
}

// Run the check as part of every build
tasks.named("classes") { finalizedBy("checkSlf4jCompat") }
