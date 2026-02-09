import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Core engine (project dependency)
    implementation(project(":core"))

    // HTTP server — Javalin 6 (Jetty 12 transitive) (ADR-0029)
    implementation(catalog.findLibrary("javalin").get())

    // Configuration parsing
    implementation(catalog.findLibrary("jackson-databind").get())
    implementation(catalog.findLibrary("jackson-dataformat-yaml").get())
    implementation(catalog.findLibrary("snakeyaml").get())

    // Logging — SLF4J binding for production
    implementation(catalog.findLibrary("slf4j-api").get())
    implementation(catalog.findLibrary("logback-classic").get())

    // Test
    testImplementation(catalog.findLibrary("mockito-core").get())
    testImplementation(catalog.findLibrary("mockito-junit-jupiter").get())
    testImplementation(catalog.findLibrary("logback-classic").get())
}

// --- Shadow JAR configuration (FR-004-30, T-004-51) ---

tasks.withType<ShadowJar>().configureEach {
    archiveClassifier.set("")          // shadow JAR = primary artifact (no -all suffix)
    mergeServiceFiles()                // merge META-INF/services (SPI: ExpressionEngine, etc.)
    manifest {
        attributes(
            "Main-Class" to "io.messagexform.standalone.StandaloneMain",
            "Implementation-Title" to "message-xform-proxy",
            "Implementation-Version" to project.version,
        )
    }
}

// Disable the default thin JAR so only the shadow JAR is produced
tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")      // keep for reference but not the default
}
