package io.messagexform.standalone.proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.messagexform.standalone.config.ConfigLoadException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for startup failure handling (T-004-47, S-004-45).
 *
 * <p>
 * Verifies that invalid/missing configurations, broken specs, and other
 * startup errors are caught and produce descriptive error messages instead
 * of cryptic stack traces.
 */
class StartupFailureTest {

    private ProxyApp proxyApp;

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        if (proxyApp != null) {
            proxyApp.stop();
        }
    }

    @Test
    @DisplayName("S-004-45: missing config file → ConfigLoadException with descriptive message")
    void missingConfigFile_throwsConfigLoadException() {
        ConfigLoadException ex = assertThrows(
                ConfigLoadException.class, () -> ProxyApp.start(new String[] {"--config", "/nonexistent/config.yaml"}));

        assertTrue(
                ex.getMessage().contains("Configuration file not found"),
                "Error message should mention missing file: " + ex.getMessage());
    }

    @Test
    @DisplayName("S-004-45: malformed YAML config → ConfigLoadException")
    void malformedYaml_throwsConfigLoadException() throws Exception {
        Path configFile = tempDir.resolve("bad-config.yaml");
        Files.writeString(configFile, "this is: not: valid: yaml: {{{}}}");

        assertThrows(ConfigLoadException.class, () -> ProxyApp.start(new String[] {"--config", configFile.toString()}));
    }

    @Test
    @DisplayName("S-004-45: missing required field (backend.host) → ConfigLoadException")
    void missingRequiredField_throwsConfigLoadException() throws Exception {
        Path configFile = tempDir.resolve("no-host-config.yaml");
        Files.writeString(configFile, """
                        proxy:
                          port: 9090
                        backend:
                          port: 8080
                        """);

        ConfigLoadException ex = assertThrows(
                ConfigLoadException.class, () -> ProxyApp.start(new String[] {"--config", configFile.toString()}));

        assertTrue(
                ex.getMessage().contains("backend.host"),
                "Error message should mention missing 'backend.host': " + ex.getMessage());
    }

    @Test
    @DisplayName("S-004-45: invalid logging level → ConfigLoadException")
    void invalidLoggingLevel_throwsConfigLoadException() throws Exception {
        Path configFile = tempDir.resolve("bad-level-config.yaml");
        Files.writeString(configFile, """
                        backend:
                          host: "127.0.0.1"
                          port: 8080
                        logging:
                          level: INVALID
                        """);

        ConfigLoadException ex = assertThrows(
                ConfigLoadException.class, () -> ProxyApp.start(new String[] {"--config", configFile.toString()}));

        assertTrue(
                ex.getMessage().contains("logging.level"),
                "Error message should mention 'logging.level': " + ex.getMessage());
    }

    @Test
    @DisplayName("S-004-45: broken spec YAML → SpecParseException at startup")
    void brokenSpec_throwsAtStartup() throws Exception {
        Path specsDir = tempDir.resolve("specs");
        Files.createDirectories(specsDir);

        // Write a broken spec
        Files.writeString(specsDir.resolve("broken.yaml"), """
                        id: broken-spec
                        version: "1.0.0"
                        """);
        // Missing required input/output/transform → should fail

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                        proxy:
                          host: "127.0.0.1"
                          port: 0
                        backend:
                          host: "127.0.0.1"
                          port: 8080
                        engine:
                          specs-dir: "%s"
                        reload:
                          enabled: false
                        """.formatted(specsDir.toString().replace("\\", "/")));

        assertThrows(
                Exception.class,
                () -> ProxyApp.start(new String[] {"--config", configFile.toString()}),
                "Broken spec should cause startup to fail");
    }

    @Test
    @DisplayName("S-004-45: --config without path → IllegalArgumentException")
    void configFlagWithoutPath_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> ProxyApp.start(new String[] {"--config"}));
    }

    @Test
    @DisplayName("S-004-45: negative port → ConfigLoadException")
    void negativePort_throwsConfigLoadException() throws Exception {
        Path configFile = tempDir.resolve("neg-port-config.yaml");
        Files.writeString(configFile, """
                        proxy:
                          port: -1
                        backend:
                          host: "127.0.0.1"
                          port: 8080
                        """);

        ConfigLoadException ex = assertThrows(
                ConfigLoadException.class, () -> ProxyApp.start(new String[] {"--config", configFile.toString()}));

        assertTrue(
                ex.getMessage().contains("proxy.port"),
                "Error message should mention 'proxy.port': " + ex.getMessage());
    }

    @Test
    @DisplayName("S-004-45: valid config with no specs → starts successfully (passthrough mode)")
    void validConfigNoSpecs_startsSuccessfully() throws Exception {
        Path specsDir = tempDir.resolve("specs");
        Files.createDirectories(specsDir);

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                        proxy:
                          host: "127.0.0.1"
                          port: 0
                        backend:
                          host: "127.0.0.1"
                          port: 8080
                        engine:
                          specs-dir: "%s"
                        reload:
                          enabled: false
                        """.formatted(specsDir.toString().replace("\\", "/")));

        proxyApp = ProxyApp.start(new String[] {"--config", configFile.toString()});
        assertTrue(proxyApp.port() > 0, "Proxy should start in passthrough mode");
    }
}
