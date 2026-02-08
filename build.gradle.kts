plugins {
    java
    alias(libs.plugins.spotless)
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

allprojects {
    group = "io.messagexform"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    val rootCatalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    spotless {
        java {
            target("src/**/*.java")
            palantirJavaFormat(rootCatalog.findVersion("palantirJavaFormat").get().toString())
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    dependencies {
        // Test dependencies shared across all modules
        "testImplementation"(platform(rootCatalog.findLibrary("junit-bom").get()))
        "testImplementation"(rootCatalog.findLibrary("junit-jupiter").get())
        "testImplementation"(rootCatalog.findLibrary("junit-jupiter-params").get())
        "testImplementation"(rootCatalog.findLibrary("assertj-core").get())
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}

// Root project has no sources
tasks.named<Jar>("jar") { enabled = false }
