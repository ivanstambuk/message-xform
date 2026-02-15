// ---------------------------------------------------------------------------
// E2E PingAccess Tests — Karate DSL
// ---------------------------------------------------------------------------
// This subproject contains Karate-based E2E tests that exercise the adapter
// against a real PingAccess Docker container.  It is NOT part of the normal
// build/check lifecycle — run via the bootstrap script or explicitly:
//   ./gradlew :e2e-pingaccess:test
//
// Docker lifecycle is managed automatically:
//   - dockerUp  (dependsOn :adapter-pingaccess:shadowJar)  → idempotent start
//   - dockerDown (finalizedBy)                              → teardown on exit
//
// The Karate Runner extension (click "Run" on a scenario) triggers:
//   ./gradlew clean test --tests e2e.PingAccessE2ETest -Dkarate.options="..."
// which flows through dockerUp → test → dockerDown automatically.
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

// ---------------------------------------------------------------------------
// E2E guard — only enable Docker + test tasks for explicit E2E invocations.
// Prevents `./gradlew check` from starting Docker infrastructure.
// ---------------------------------------------------------------------------
val isE2EExplicit = gradle.startParameter.taskNames.any {
    it.contains("e2e-pingaccess") || it == "test"
}

// ---------------------------------------------------------------------------
// Docker lifecycle tasks
// ---------------------------------------------------------------------------
val dockerUp by tasks.registering(Exec::class) {
    group = "e2e"
    description = "Start E2E Docker infrastructure (idempotent — skips if already running)"
    enabled = isE2EExplicit
    dependsOn(":adapter-pingaccess:shadowJar")
    commandLine("bash", "${rootDir}/scripts/pa-e2e-infra-up.sh")
}

val dockerDown by tasks.registering(Exec::class) {
    group = "e2e"
    description = "Tear down E2E Docker infrastructure"
    enabled = isE2EExplicit
    commandLine("bash", "${rootDir}/scripts/pa-e2e-infra-down.sh")
}

// ---------------------------------------------------------------------------
// Test task configuration
// ---------------------------------------------------------------------------
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
    // Also enabled when Karate Runner extension runs bare `test`.
    enabled = isE2EExplicit

    // Wire Docker lifecycle: start before tests, tear down after
    dependsOn(dockerUp)
    finalizedBy(dockerDown)
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
