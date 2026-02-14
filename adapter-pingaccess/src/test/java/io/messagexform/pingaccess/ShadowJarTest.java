package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Shadow JAR correctness verification (T-002-30, NFR-002-02, S-002-24).
 *
 * <p>
 * Verifies that the shadow JAR contains only the expected classes and excludes
 * PA-provided dependencies (Jackson, SLF4J, Jakarta, PA SDK).
 *
 * <p>
 * These tests are DISABLED if the shadow JAR hasn't been built yet (CI gate
 * builds it before tests). Use the condition {@code shadowJarExists()} to
 * skip gracefully.
 */
@EnabledIf("shadowJarExists")
class ShadowJarTest {

    private static Path shadowJar;
    private static List<String> entries;

    private static Path findBuildLibsDir() {
        // Gradle may run tests from the project root or the module directory.
        // Try both locations.
        Path fromRoot = Path.of("adapter-pingaccess/build/libs");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        Path fromModule = Path.of("build/libs");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        return null;
    }

    @BeforeAll
    static void findShadowJar() throws IOException {
        Path buildDir = findBuildLibsDir();
        if (buildDir == null) {
            return;
        }
        shadowJar = Files.list(buildDir)
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .filter(p -> !p.getFileName().toString().contains("thin"))
                .findFirst()
                .orElse(null);
        if (shadowJar != null) {
            try (JarFile jar = new JarFile(shadowJar.toFile())) {
                entries = jar.stream().map(e -> e.getName()).collect(Collectors.toList());
            }
        }
    }

    static boolean shadowJarExists() {
        Path buildDir = findBuildLibsDir();
        if (buildDir == null) {
            return false;
        }
        try {
            return Files.list(buildDir)
                    .anyMatch(p -> p.getFileName().toString().endsWith(".jar")
                            && !p.getFileName().toString().contains("thin"));
        } catch (IOException e) {
            return false;
        }
    }

    @Nested
    class IncludedClasses {

        @Test
        void containsAdapterClasses() {
            assertThat(entries.stream().anyMatch(e -> e.startsWith("io/messagexform/pingaccess/")))
                    .as("Shadow JAR should contain adapter classes")
                    .isTrue();
        }

        @Test
        void containsCoreClasses() {
            assertThat(entries.stream().anyMatch(e -> e.startsWith("io/messagexform/core/")))
                    .as("Shadow JAR should contain core engine classes")
                    .isTrue();
        }

        @Test
        void containsSpiServiceFile() {
            assertThat(entries)
                    .as("Shadow JAR should contain SPI service file")
                    .anyMatch(e -> e.equals("META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor"));
        }

        @Test
        void containsCompiledVersionsProperties() {
            assertThat(entries)
                    .as("Shadow JAR should contain PA compiled versions properties")
                    .anyMatch(e -> e.equals("META-INF/message-xform/pa-compiled-versions.properties"));
        }
    }

    @Nested
    class ExcludedClasses {

        @Test
        void excludesPaSdkClasses() {
            long count = entries.stream()
                    .filter(e -> e.startsWith("com/pingidentity/pa/sdk/"))
                    .count();
            assertThat(count).as("Shadow JAR must NOT contain PA SDK classes").isZero();
        }

        @Test
        void excludesJacksonClasses() {
            long count = entries.stream()
                    .filter(e -> e.startsWith("com/fasterxml/jackson/"))
                    .count();
            assertThat(count).as("Shadow JAR must NOT contain Jackson classes").isZero();
        }

        @Test
        void excludesJacksonMrJarClasses() {
            long count = entries.stream()
                    .filter(e -> e.contains("META-INF/versions/") && e.contains("com/fasterxml/jackson/"))
                    .count();
            assertThat(count)
                    .as("Shadow JAR must NOT contain Jackson MR-JAR classes")
                    .isZero();
        }

        @Test
        void excludesJacksonServiceFiles() {
            long count = entries.stream()
                    .filter(e -> e.startsWith("META-INF/services/com.fasterxml.jackson"))
                    .count();
            assertThat(count)
                    .as("Shadow JAR must NOT contain Jackson SPI service files")
                    .isZero();
        }

        @Test
        void excludesSlf4jClasses() {
            long count =
                    entries.stream().filter(e -> e.startsWith("org/slf4j/")).count();
            assertThat(count).as("Shadow JAR must NOT contain SLF4J classes").isZero();
        }

        @Test
        void excludesJakartaValidationClasses() {
            long count = entries.stream()
                    .filter(e -> e.startsWith("jakarta/validation/"))
                    .count();
            assertThat(count)
                    .as("Shadow JAR must NOT contain Jakarta Validation classes")
                    .isZero();
        }

        @Test
        void excludesJakartaInjectClasses() {
            long count = entries.stream()
                    .filter(e -> e.startsWith("jakarta/inject/"))
                    .count();
            assertThat(count)
                    .as("Shadow JAR must NOT contain Jakarta Inject classes")
                    .isZero();
        }
    }

    @Nested
    class JarSize {

        @Test
        void shadowJarUnder5Mb() throws IOException {
            long sizeBytes = Files.size(shadowJar);
            long sizeMb = sizeBytes / (1024 * 1024);
            assertThat(sizeMb)
                    .as("Shadow JAR must be under 5 MB (NFR-002-02), actual: %d bytes", sizeBytes)
                    .isLessThan(5);
        }
    }
}
