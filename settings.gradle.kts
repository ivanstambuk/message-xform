enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "message-xform"

// Dual version catalog: PA-provided dependency versions (ADR-0031)
dependencyResolutionManagement {
    versionCatalogs {
        create("paProvided") {
            from(files("gradle/pa-provided.versions.toml"))
        }
    }
}

include("core")
include("adapter-standalone")
include("adapter-pingaccess")

// E2E modules: only include when the directory exists.
// The standalone proxy Dockerfile copies settings.gradle.kts but not
// e2e-pingaccess/, causing Gradle 9.x to fail at configuration time.
if (file("e2e-pingaccess").isDirectory) {
    include("e2e-pingaccess")
}

