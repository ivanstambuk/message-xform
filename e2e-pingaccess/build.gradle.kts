// ---------------------------------------------------------------------------
// E2E PingAccess Tests — Karate DSL
// ---------------------------------------------------------------------------
// This subproject contains Karate-based E2E tests that exercise the adapter
// against a real PingAccess Docker container.  It is NOT part of the normal
// build/check lifecycle — run via the bootstrap script or explicitly:
//   ./gradlew :e2e-pingaccess:test
//
// Spotless is intentionally excluded: .feature files are Gherkin, not Java.
// ---------------------------------------------------------------------------

plugins {
    java
}

// Opt out of Spotless — Karate files are Gherkin/JS, not Java
pluginManager.withPlugin("com.diffplug.spotless") {
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
        }
    }
}

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation("com.intuit.karate:karate-junit5:1.4.1")
    testImplementation(platform(catalog.findLibrary("junit-bom").get()))
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Karate tests need more time (PA startup, OIDC flows)
    systemProperty("karate.options", System.getProperty("karate.options", ""))
    // Pass environment overrides through
    systemProperty("karate.env", System.getProperty("karate.env", "docker"))
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    // E2E tests require Docker infrastructure — exclude from `check` lifecycle.
    // Run explicitly: ./gradlew :e2e-pingaccess:test
    enabled = gradle.startParameter.taskNames.any { it.contains("e2e-pingaccess") }
}

// Karate feature files live in src/test/java (Karate convention)
sourceSets {
    test {
        resources {
            srcDir("src/test/java")
            exclude("**/*.java")
        }
    }
}
