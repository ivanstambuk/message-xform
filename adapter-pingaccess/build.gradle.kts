import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

// ---------------------------------------------------------------------------
// PA-provided version catalog (registered as "paProvided" in settings.gradle.kts)
// ---------------------------------------------------------------------------
val paProvided = extensions.getByType<VersionCatalogsExtension>().named("paProvided")
val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

// ---------------------------------------------------------------------------
// Repositories — PA SDK is a local JAR (not on Maven Central)
// ---------------------------------------------------------------------------
repositories {
    flatDir {
        dirs(rootProject.file("libs/pingaccess-sdk"))
    }
}

// ---------------------------------------------------------------------------
// Dependencies (ADR-0031, FR-002-09)
// ---------------------------------------------------------------------------
dependencies {
    // Core engine (project dependency)
    implementation(project(":core"))

    // PA SDK — local JAR, compileOnly (provided by PA at runtime)
    compileOnly(files(rootProject.file("libs/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar")))

    // PA-provided dependencies — compileOnly, NOT bundled (ADR-0031)
    compileOnly(paProvided.findLibrary("pa-jackson-databind").get())
    compileOnly(paProvided.findLibrary("pa-jackson-core").get())
    compileOnly(paProvided.findLibrary("pa-jackson-annotations").get())
    compileOnly(paProvided.findLibrary("pa-slf4j-api").get())
    compileOnly(paProvided.findLibrary("pa-jakarta-validation").get())
    compileOnly(paProvided.findLibrary("pa-jakarta-inject").get())
    compileOnly(paProvided.findLibrary("pa-jakarta-annotation").get())

    // Bundled — NOT shipped by PA
    implementation(catalog.findLibrary("snakeyaml").get())

    // Test
    testImplementation(catalog.findLibrary("mockito-core").get())
    testImplementation(catalog.findLibrary("mockito-junit-jupiter").get())
    testImplementation(catalog.findLibrary("logback-classic").get())
    testImplementation(catalog.findLibrary("archunit-junit5").get())
    testImplementation(paProvided.findLibrary("pa-hibernate-validator").get())
    testImplementation(paProvided.findLibrary("pa-jakarta-el").get())
    // Test needs Jackson and SDK on classpath
    testImplementation(paProvided.findLibrary("pa-jackson-databind").get())
    testImplementation(files(rootProject.file("libs/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar")))
}

// ---------------------------------------------------------------------------
// Shadow JAR configuration (FR-002-09, ADR-0031)
// ---------------------------------------------------------------------------
tasks.withType<ShadowJar>().configureEach {
    archiveClassifier.set("")          // shadow JAR = primary artifact
    mergeServiceFiles()                // merge META-INF/services (SPI)

    // Exclude PA-provided classes — belt-and-suspenders safety net
    // These are compileOnly, but transitive pulls could sneak them in
    exclude("com/fasterxml/jackson/**")
    exclude("META-INF/versions/**/com/fasterxml/jackson/**")  // MR-JAR leakage
    exclude("META-INF/services/com.fasterxml.jackson.*")      // dangling service files
    exclude("org/slf4j/**")
    exclude("jakarta/validation/**")
    exclude("jakarta/inject/**")
    exclude("com/pingidentity/**")

    manifest {
        attributes(
            "Implementation-Title" to "message-xform-pingaccess",
            "Implementation-Version" to project.version,
            "PA-Compiled-Version" to paProvided.findVersion("pa-jackson").get().toString(),
        )
    }
}

// Disable the default thin JAR so only the shadow JAR is produced
tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

// ---------------------------------------------------------------------------
// Bake PA compiled-against versions into a properties file (ADR-0035)
// ---------------------------------------------------------------------------
tasks.named<ProcessResources>("processResources") {
    val paJackson = paProvided.findVersion("pa-jackson").get().toString()
    val paSlf4j = paProvided.findVersion("pa-slf4j").get().toString()
    val paSdk = paProvided.findVersion("pa-sdk").get().toString()
    inputs.property("paJackson", paJackson)
    inputs.property("paSlf4j", paSlf4j)
    inputs.property("paSdk", paSdk)
    filesMatching("META-INF/message-xform/pa-compiled-versions.properties") {
        expand(
            "paJackson" to paJackson,
            "paSlf4j" to paSlf4j,
            "paSdk" to paSdk,
        )
    }
}
