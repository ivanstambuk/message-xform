package io.messagexform.standalone.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConfigLoader} YAML parsing (T-004-06, FR-004-10).
 * Exercises loading from classpath fixtures and validates that all config
 * keys are mapped correctly, defaults are applied for missing keys, and
 * error paths produce descriptive exceptions.
 */
@DisplayName("T-004-06: YAML config loader")
class ConfigLoaderTest {

    @Nested
    @DisplayName("Minimal config (FX-004-01)")
    class MinimalConfig {

        @Test
        @DisplayName("Load minimal config → backend fields correct + all defaults (S-004-24, S-004-28)")
        void loadMinimalConfig_backenFieldsAndDefaults() throws Exception {
            Path configPath = Path.of(ConfigLoaderTest.class
                    .getClassLoader()
                    .getResource("config/minimal-config.yaml")
                    .toURI());

            ProxyConfig config = ConfigLoader.load(configPath);

            // Explicit values from YAML
            assertThat(config.backendHost()).isEqualTo("backend.local");
            assertThat(config.backendPort()).isEqualTo(8080);

            // Defaults
            assertThat(config.proxyHost()).isEqualTo("0.0.0.0");
            assertThat(config.proxyPort()).isEqualTo(9090);
            assertThat(config.backendScheme()).isEqualTo("http");
            assertThat(config.backendConnectTimeoutMs()).isEqualTo(5000);
            assertThat(config.backendReadTimeoutMs()).isEqualTo(30000);
            assertThat(config.maxBodyBytes()).isEqualTo(10_485_760);
            assertThat(config.specsDir()).isEqualTo("./specs");
            assertThat(config.profilesDir()).isEqualTo("./profiles");
            assertThat(config.profilePath()).isNull();
            assertThat(config.schemaValidation()).isEqualTo("lenient");
            assertThat(config.reloadEnabled()).isTrue();
            assertThat(config.reloadDebounceMs()).isEqualTo(500);
            assertThat(config.healthEnabled()).isTrue();
            assertThat(config.healthPath()).isEqualTo("/health");
            assertThat(config.readyPath()).isEqualTo("/ready");
            assertThat(config.loggingFormat()).isEqualTo("json");
            assertThat(config.loggingLevel()).isEqualTo("INFO");
            assertThat(config.shutdownDrainTimeoutMs()).isEqualTo(30000);
            assertThat(config.forwardedHeadersEnabled()).isTrue();
            assertThat(config.adminReloadPath()).isEqualTo("/admin/reload");

            // Nested defaults
            assertThat(config.proxyTls().enabled()).isFalse();
            assertThat(config.backendTls().verifyHostname()).isTrue();
            assertThat(config.pool().maxConnections()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Full config (FX-004-02)")
    class FullConfig {

        @Test
        @DisplayName("Load full config → all fields populated correctly")
        void loadFullConfig_allFieldsPopulated() throws Exception {
            Path configPath = Path.of(ConfigLoaderTest.class
                    .getClassLoader()
                    .getResource("config/full-config.yaml")
                    .toURI());

            ProxyConfig config = ConfigLoader.load(configPath);

            // Proxy
            assertThat(config.proxyHost()).isEqualTo("127.0.0.1");
            assertThat(config.proxyPort()).isEqualTo(8080);
            assertThat(config.maxBodyBytes()).isEqualTo(5_242_880);
            assertThat(config.shutdownDrainTimeoutMs()).isEqualTo(60000);
            assertThat(config.forwardedHeadersEnabled()).isFalse();

            // Backend
            assertThat(config.backendScheme()).isEqualTo("https");
            assertThat(config.backendHost()).isEqualTo("api.example.com");
            assertThat(config.backendPort()).isEqualTo(8443);
            assertThat(config.backendConnectTimeoutMs()).isEqualTo(3000);
            assertThat(config.backendReadTimeoutMs()).isEqualTo(15000);

            // Engine
            assertThat(config.specsDir()).isEqualTo("/opt/specs");
            assertThat(config.profilesDir()).isEqualTo("/opt/profiles");
            assertThat(config.profilePath()).isEqualTo("/opt/profiles/main.yaml");
            assertThat(config.schemaValidation()).isEqualTo("strict");

            // Reload
            assertThat(config.reloadEnabled()).isFalse();
            assertThat(config.reloadDebounceMs()).isEqualTo(1000);

            // Health
            assertThat(config.healthEnabled()).isTrue();
            assertThat(config.healthPath()).isEqualTo("/healthz");
            assertThat(config.readyPath()).isEqualTo("/readyz");

            // Logging
            assertThat(config.loggingFormat()).isEqualTo("text");
            assertThat(config.loggingLevel()).isEqualTo("DEBUG");

            // Admin
            assertThat(config.adminReloadPath()).isEqualTo("/ops/reload");

            // Proxy TLS
            assertThat(config.proxyTls().enabled()).isTrue();
            assertThat(config.proxyTls().keystore()).isEqualTo("/opt/certs/server.p12");
            assertThat(config.proxyTls().keystorePassword()).isEqualTo("serverpass");
            assertThat(config.proxyTls().keystoreType()).isEqualTo("PKCS12");
            assertThat(config.proxyTls().clientAuth()).isEqualTo("want");
            assertThat(config.proxyTls().truststore()).isEqualTo("/opt/certs/client-ca.p12");
            assertThat(config.proxyTls().truststorePassword()).isEqualTo("trustpass");
            assertThat(config.proxyTls().truststoreType()).isEqualTo("PKCS12");

            // Backend TLS
            assertThat(config.backendTls().truststore()).isEqualTo("/opt/certs/backend-ca.p12");
            assertThat(config.backendTls().truststorePassword()).isEqualTo("backendtrust");
            assertThat(config.backendTls().truststoreType()).isEqualTo("JKS");
            assertThat(config.backendTls().verifyHostname()).isTrue();
            assertThat(config.backendTls().keystore()).isEqualTo("/opt/certs/client.p12");
            assertThat(config.backendTls().keystorePassword()).isEqualTo("clientpass");
            assertThat(config.backendTls().keystoreType()).isEqualTo("PKCS12");

            // Pool
            assertThat(config.pool().maxConnections()).isEqualTo(200);
            assertThat(config.pool().keepAlive()).isFalse();
            assertThat(config.pool().idleTimeoutMs()).isEqualTo(30000);
        }
    }

    @Nested
    @DisplayName("TLS config (FX-004-03)")
    class TlsConfigTest {

        @Test
        @DisplayName("Load TLS config → inbound + outbound TLS fields correct")
        void loadTlsConfig_tlsFieldsCorrect() throws Exception {
            Path configPath = Path.of(ConfigLoaderTest.class
                    .getClassLoader()
                    .getResource("config/tls-config.yaml")
                    .toURI());

            ProxyConfig config = ConfigLoader.load(configPath);

            assertThat(config.proxyTls().enabled()).isTrue();
            assertThat(config.proxyTls().clientAuth()).isEqualTo("need");
            assertThat(config.backendTls().truststoreType()).isEqualTo("JKS");
            assertThat(config.backendTls().verifyHostname()).isTrue();
        }
    }

    @Nested
    @DisplayName("Error paths")
    class ErrorPaths {

        @Test
        @DisplayName("Missing config file → ConfigLoadException with descriptive message (S-004-26)")
        void missingConfigFile_throwsConfigLoadException() {
            Path missingPath = Path.of("/nonexistent/config.yaml");

            assertThatThrownBy(() -> ConfigLoader.load(missingPath))
                    .isInstanceOf(ConfigLoadException.class)
                    .hasMessageContaining("/nonexistent/config.yaml")
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Invalid YAML → ConfigLoadException")
        void invalidYaml_throwsConfigLoadException() throws Exception {
            // Create a temp file with invalid YAML
            Path tempFile = java.nio.file.Files.createTempFile("bad-config-", ".yaml");
            java.nio.file.Files.writeString(tempFile, "{{invalid yaml: [[[");
            tempFile.toFile().deleteOnExit();

            assertThatThrownBy(() -> ConfigLoader.load(tempFile)).isInstanceOf(ConfigLoadException.class);
        }
    }

    @Nested
    @DisplayName("CLI argument parsing")
    class CliArguments {

        @Test
        @DisplayName("--config /path → resolves to specified path")
        void configFlag_resolvesToSpecifiedPath() {
            Path result = ConfigLoader.resolveConfigPath(new String[] {"--config", "/opt/proxy.yaml"});
            assertThat(result).isEqualTo(Path.of("/opt/proxy.yaml"));
        }

        @Test
        @DisplayName("No args → resolves to default path")
        void noArgs_resolvesToDefaultPath() {
            Path result = ConfigLoader.resolveConfigPath(new String[] {});
            assertThat(result).isEqualTo(Path.of("message-xform-proxy.yaml"));
        }

        @Test
        @DisplayName("--config without value → IllegalArgumentException")
        void configFlagWithoutValue_throwsIllegalArgument() {
            assertThatThrownBy(() -> ConfigLoader.resolveConfigPath(new String[] {"--config"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--config");
        }
    }
}
