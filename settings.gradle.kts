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
