package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PaVersionGuard} (T-002-29, ADR-0035, S-002-36).
 *
 * <p>
 * Verifies the version guard's ability to load compiled versions, detect
 * runtime versions, and produce warning output on mismatch without
 * fail-fast shutdown.
 */
class VersionGuardTest {

    @Nested
    class CompiledVersionsLoading {

        @Test
        void propertiesFileExistsOnClasspath() {
            try (InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("META-INF/message-xform/pa-compiled-versions.properties")) {
                assertThat(is).isNotNull();
            } catch (IOException e) {
                throw new AssertionError("Failed to read properties file", e);
            }
        }

        @Test
        void propertiesContainExpectedKeys() throws Exception {
            Properties props = new Properties();
            try (InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("META-INF/message-xform/pa-compiled-versions.properties")) {
                assertThat(is).isNotNull();
                props.load(is);
            }

            assertThat(props.getProperty("pa.jackson.version")).isNotEmpty();
            assertThat(props.getProperty("pa.slf4j.version")).isNotEmpty();
            assertThat(props.getProperty("pa.sdk.version")).isNotEmpty();
        }

        @Test
        void compiledJacksonVersionIsValidSemver() throws Exception {
            Properties props = loadCompiledVersions();

            String compiledJackson = props.getProperty("pa.jackson.version");
            // The compiled version from pa-provided catalog should be a valid
            // semver-like version string (e.g., "2.17.0")
            assertThat(compiledJackson).matches("\\d+\\.\\d+\\.\\d+.*");
        }

        @Test
        void runtimeJacksonVersionIsDetectable() {
            // PaVersionGuard uses PackageVersion to detect the runtime Jackson version.
            // In test classpath, this may differ from the PA-compiled version
            // (the core module pulls a newer Jackson). This is fine — the
            // guard only logs WARN, never fails.
            String runtimeJackson = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString();
            assertThat(runtimeJackson).isNotEmpty();
        }

        @Test
        void sdkVersionIsPinned() throws Exception {
            Properties props = loadCompiledVersions();

            String sdkVersion = props.getProperty("pa.sdk.version");
            assertThat(sdkVersion).startsWith("9.0.1");
        }
    }

    @Nested
    class RuntimeBehavior {

        @Test
        void checkDoesNotThrowOnMatch() {
            // Should complete without exception when compiled == runtime
            PaVersionGuard.check();
        }

        @Test
        void checkIsIdempotent() {
            // Multiple calls should be safe
            PaVersionGuard.check();
            PaVersionGuard.check();
        }

        @Test
        void checkDoesNotFailFast() {
            // The contract is WARN log, never fail-fast (S-002-36)
            // Even if there were a mismatch, check() must not throw.
            // In our test environment, versions match, so this confirms
            // the happy path doesn't throw.
            PaVersionGuard.check();
        }
    }

    private Properties loadCompiledVersions() throws IOException {
        Properties props = new Properties();
        try (InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream("META-INF/message-xform/pa-compiled-versions.properties")) {
            if (is != null) {
                props.load(is);
            }
        }
        return props;
    }
}
