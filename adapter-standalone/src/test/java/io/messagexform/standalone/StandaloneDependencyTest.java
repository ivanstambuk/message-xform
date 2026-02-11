package io.messagexform.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the adapter-standalone module has no gateway-specific SDK
 * dependencies
 * (T-004-03). The module MUST depend only on {@code core}, Javalin, Jetty,
 * Jackson,
 * SnakeYAML, SLF4J, and Logback. Any vendor SDK (PingAccess, PingGateway, Kong,
 * etc.)
 * would indicate a dependency hygiene violation.
 */
@DisplayName("T-004-03: Zero gateway-specific dependencies")
class StandaloneDependencyTest {

    /** Gateway vendor artifacts that MUST NOT appear in the dependency tree. */
    private static final Set<String> FORBIDDEN_ARTIFACTS = Set.of(
            "ping",
            "pingaccess",
            "pingateway",
            "forgerock",
            "kong",
            "apigee",
            "apisix",
            "envoy",
            "tyk",
            "wso2",
            "zuul",
            "spring-cloud-gateway");

    /** Expected top-level dependency groups in the compile classpath. */
    private static final Set<String> ALLOWED_GROUPS = Set.of(
            "io.messagexform", // :core project
            "io.javalin", // Javalin 6
            "org.eclipse.jetty", // Jetty (Javalin transitive)
            "org.eclipse.jetty.toolchain",
            "org.eclipse.jetty.websocket",
            "com.fasterxml.jackson", // Jackson
            "com.fasterxml.jackson.core",
            "com.fasterxml.jackson.dataformat",
            "org.yaml", // SnakeYAML
            "org.slf4j", // SLF4J
            "com.schibsted.spt.data", // JSLT (core transitive)
            "com.networknt", // JSON Schema Validator (core transitive)
            "org.jetbrains.kotlin", // Kotlin stdlib (Javalin transitive)
            "org.jetbrains", // JetBrains annotations (Kotlin transitive)
            "ch.qos.logback", // Logback (runtime)
            "net.bytebuddy" // Byte Buddy (Jackson 2.17 transitive)
            );

    @Test
    @DisplayName("Compile classpath contains no forbidden gateway vendor artifacts")
    void noForbiddenGatewayDependencies() throws Exception {
        List<String> depLines = resolveCompileClasspath();

        List<String> violations = depLines.stream()
                .filter(line -> FORBIDDEN_ARTIFACTS.stream()
                        .anyMatch(forbidden -> line.toLowerCase().contains(forbidden)))
                .collect(Collectors.toList());

        assertThat(violations)
                .as("Dependency tree must not contain gateway vendor artifacts")
                .isEmpty();
    }

    @Test
    @DisplayName("All compile classpath dependencies belong to allowed groups")
    void onlyAllowedDependencyGroups() throws Exception {
        List<String> depLines = resolveCompileClasspath();

        // Extract artifact coordinates (group:name:version) from dependency tree lines
        List<String> artifacts = depLines.stream()
                .map(String::trim)
                .filter(line -> line.contains(":") && !line.startsWith("("))
                .map(line -> {
                    // Strip tree decoration characters: +--- , | , \--- , etc.
                    String cleaned = line.replaceAll("^[+|\\\\\\s-]+", "");
                    // Remove constraint markers like (*) or (c) and version selectors like -> x.y.z
                    int arrowIdx = cleaned.indexOf(" ->");
                    if (arrowIdx > 0) cleaned = cleaned.substring(0, arrowIdx);
                    cleaned = cleaned.replaceAll("\\s*\\(\\*\\)\\s*$", "")
                            .replaceAll("\\s*\\(c\\)\\s*$", "")
                            .trim();
                    return cleaned;
                })
                // Skip project dependency lines (e.g. "project :core")
                .filter(s -> s.contains(":") && !s.toLowerCase().startsWith("project"))
                .collect(Collectors.toList());

        for (String artifact : artifacts) {
            String group = artifact.substring(0, artifact.indexOf(":"));
            assertThat(ALLOWED_GROUPS)
                    .as("Unexpected dependency group '%s' (artifact: %s)", group, artifact)
                    .anyMatch(group::startsWith);
        }
    }

    // --- Helper ---

    /**
     * Runs
     * {@code ./gradlew :adapter-standalone:dependencies --configuration compileClasspath}
     * and returns the output lines. Falls back to classpath inspection if Gradle is
     * not available.
     */
    private List<String> resolveCompileClasspath() throws Exception {
        // Locate project root by walking up from test classes
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path gradlew = projectRoot.resolve("gradlew");
        if (!gradlew.toFile().exists()) {
            // Fallback: search parent directories
            projectRoot = Path.of("").toAbsolutePath();
            while (projectRoot != null
                    && !projectRoot.resolve("gradlew").toFile().exists()) {
                projectRoot = projectRoot.getParent();
            }
            if (projectRoot == null) {
                throw new IllegalStateException("Cannot locate gradlew — run tests from project root");
            }
            gradlew = projectRoot.resolve("gradlew");
        }

        ProcessBuilder pb = new ProcessBuilder(
                gradlew.toString(), ":adapter-standalone:dependencies", "--configuration", "compileClasspath", "-q");
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        List<String> lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            lines = reader.lines().collect(Collectors.toList());
        }
        int exitCode = process.waitFor();
        assertThat(exitCode).as("Gradle dependency resolution must succeed").isZero();

        return lines;
    }
}
