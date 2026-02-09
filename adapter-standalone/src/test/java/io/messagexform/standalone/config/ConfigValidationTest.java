package io.messagexform.standalone.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for configuration validation (T-004-08, FR-004-12).
 *
 * <p>
 * Validates that the proxy rejects invalid configurations at startup with
 * descriptive error messages, and that valid edge cases (e.g., port
 * auto-derivation)
 * work correctly.
 */
@DisplayName("T-004-08: Config validation")
class ConfigValidationTest {

    @TempDir
    Path tempDir;

    /** Writes YAML content to a temp file and returns the path. */
    private Path writeConfig(String yaml) throws Exception {
        Path configFile = tempDir.resolve("test-config.yaml");
        Files.writeString(configFile, yaml);
        return configFile;
    }

    // -----------------------------------------------------------------------
    // Missing required fields
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Missing required fields")
    class MissingRequiredFields {

        @Test
        @DisplayName("Missing backend.host → ConfigLoadException with descriptive message (S-004-27)")
        void missingBackendHost_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          proxy:
            port: 9090
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("backend.host")
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("Empty backend.host → ConfigLoadException")
        void emptyBackendHost_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: ""
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("backend.host")
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("Whitespace-only backend.host → ConfigLoadException")
        void whitespaceOnlyBackendHost_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: "   "
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("backend.host")
                    .hasMessageContaining("required");
        }
    }

    // -----------------------------------------------------------------------
    // Invalid enum values
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Invalid enum values")
    class InvalidEnumValues {

        @Test
        @DisplayName("Invalid backend.scheme → ConfigLoadException")
        void invalidBackendScheme_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
            scheme: ftp
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("backend.scheme")
                    .hasMessageContaining("ftp");
        }

        @Test
        @DisplayName("Invalid proxy.tls.client-auth → ConfigLoadException")
        void invalidClientAuth_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
          proxy:
            tls:
              enabled: true
              keystore: /path/to/keystore
              client-auth: always
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("proxy.tls.client-auth")
                    .hasMessageContaining("always");
        }

        @Test
        @DisplayName("Invalid logging.level → ConfigLoadException")
        void invalidLoggingLevel_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
          logging:
            level: VERBOSE
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("logging.level")
                    .hasMessageContaining("VERBOSE");
        }

        @Test
        @DisplayName("Invalid engine.schema-validation → ConfigLoadException")
        void invalidSchemaValidation_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
          engine:
            schema-validation: paranoid
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("engine.schema-validation")
                    .hasMessageContaining("paranoid");
        }

        @Test
        @DisplayName("Invalid proxy.tls.keystore-type → ConfigLoadException")
        void invalidKeystoreType_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
          proxy:
            tls:
              enabled: true
              keystore: /path/to/keystore
              keystore-type: PEM
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("proxy.tls.keystore-type")
                    .hasMessageContaining("PEM");
        }

        @Test
        @DisplayName("Invalid logging.format → ConfigLoadException")
        void invalidLoggingFormat_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
          logging:
            format: xml
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("logging.format")
                    .hasMessageContaining("xml");
        }
    }

    // -----------------------------------------------------------------------
    // Valid enum values (case-insensitive check)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Valid enum values accepted")
    class ValidEnumValues {

        @Test
        @DisplayName("backend.scheme: http → accepted")
        void httpScheme_accepted() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
            scheme: http
          """);

            ProxyConfig result = ConfigLoader.load(config);
            assertThat(result.backendScheme()).isEqualTo("http");
        }

        @Test
        @DisplayName("backend.scheme: https → accepted")
        void httpsScheme_accepted() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
            scheme: https
          """);

            ProxyConfig result = ConfigLoader.load(config);
            assertThat(result.backendScheme()).isEqualTo("https");
        }

        @Test
        @DisplayName("logging.level: TRACE/DEBUG/INFO/WARN/ERROR → all accepted")
        void allLogLevels_accepted() throws Exception {
            for (String level : new String[] {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"}) {
                Path config = writeConfig("""
            backend:
              host: api.example.com
            logging:
              level: %s
            """.formatted(level));

                ProxyConfig result = ConfigLoader.load(config);
                assertThat(result.loggingLevel()).isEqualTo(level);
            }
        }

        @Test
        @DisplayName("proxy.tls.client-auth: none/want/need → all accepted")
        void allClientAuthModes_accepted() throws Exception {
            for (String mode : new String[] {"none", "want", "need"}) {
                Path config = writeConfig("""
            backend:
              host: api.example.com
            proxy:
              tls:
                client-auth: %s
            """.formatted(mode));

                ProxyConfig result = ConfigLoader.load(config);
                assertThat(result.proxyTls().clientAuth()).isEqualTo(mode);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Port auto-derivation (already in builder, regression test)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Port auto-derivation")
    class PortAutoDerivation {

        @Test
        @DisplayName("No backend.port + http → port 80")
        void httpScheme_port80() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
            scheme: http
          """);

            ProxyConfig result = ConfigLoader.load(config);
            assertThat(result.backendPort()).isEqualTo(80);
        }

        @Test
        @DisplayName("No backend.port + https → port 443")
        void httpsScheme_port443() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
            scheme: https
          """);

            ProxyConfig result = ConfigLoader.load(config);
            assertThat(result.backendPort()).isEqualTo(443);
        }

        @Test
        @DisplayName("Explicit backend.port → overrides auto-derivation")
        void explicitPort_overridesDerivation() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
            scheme: https
            port: 8443
          """);

            ProxyConfig result = ConfigLoader.load(config);
            assertThat(result.backendPort()).isEqualTo(8443);
        }
    }

    // -----------------------------------------------------------------------
    // Negative integer values
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Negative integer validation")
    class NegativeIntegers {

        @Test
        @DisplayName("Negative proxy.port → ConfigLoadException")
        void negativeProxyPort_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
          proxy:
            port: -1
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("proxy.port")
                    .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("Zero proxy.port → accepted (ephemeral port)")
        void zeroProxyPort_accepted() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
          proxy:
            port: 0
          """);

            ProxyConfig result = ConfigLoader.load(config);
            assertThat(result.proxyPort()).isEqualTo(0);
        }

        @Test
        @DisplayName("Negative backend.connect-timeout-ms → ConfigLoadException")
        void negativeConnectTimeout_throwsConfigLoadException() throws Exception {
            Path config = writeConfig("""
          backend:
            host: api.example.com
            connect-timeout-ms: -100
          """);

            assertThatThrownBy(() -> ConfigLoader.load(config))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("backend.connect-timeout-ms")
                    .hasMessageContaining("positive");
        }
    }
}
