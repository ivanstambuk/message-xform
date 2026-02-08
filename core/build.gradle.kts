plugins {
    `java-library`
}

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Core runtime
    api(catalog.findLibrary("jackson-databind").get())
    implementation(catalog.findLibrary("jackson-dataformat-yaml").get())
    implementation(catalog.findLibrary("jslt").get())
    implementation(catalog.findLibrary("json-schema-validator").get())
    implementation(catalog.findLibrary("slf4j-api").get())

    // Test
    testImplementation(catalog.findLibrary("mockito-core").get())
    testImplementation(catalog.findLibrary("mockito-junit-jupiter").get())
    testRuntimeOnly(catalog.findLibrary("logback-classic").get())
}
