package io.messagexform.standalone.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link ProxyConfig} record hierarchy (T-004-04,
 * DO-004-01/02/03/04, CFG-004-01..41). Verifies defaults, full construction,
 * and immutability.
 */
@DisplayName("T-004-04: ProxyConfig record hierarchy")
class ProxyConfigTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("Minimal config: only backend.host → all defaults applied")
        void minimalConfig_allDefaultsApplied() {
            ProxyConfig config = ProxyConfig.builder().backendHost("localhost").build();

            // Proxy defaults (CFG-004-01, CFG-004-02)
            assertThat(config.proxyHost()).isEqualTo("0.0.0.0");
            assertThat(config.proxyPort()).isEqualTo(9090);

            // Backend defaults (CFG-004-11, CFG-004-13, CFG-004-14, CFG-004-15)
            assertThat(config.backendScheme()).isEqualTo("http");
            assertThat(config.backendPort()).isEqualTo(80);
            assertThat(config.backendConnectTimeoutMs()).isEqualTo(5000);
            assertThat(config.backendReadTimeoutMs()).isEqualTo(30000);

            // Body size (CFG-004-16)
            assertThat(config.maxBodyBytes()).isEqualTo(10_485_760);

            // Engine defaults (CFG-004-27, CFG-004-28, CFG-004-30)
            assertThat(config.specsDir()).isEqualTo("./specs");
            assertThat(config.profilesDir()).isEqualTo("./profiles");
            assertThat(config.schemaValidation()).isEqualTo("lenient");

            // Reload defaults (CFG-004-31, CFG-004-33)
            assertThat(config.reloadEnabled()).isTrue();
            assertThat(config.reloadDebounceMs()).isEqualTo(500);

            // Health defaults (CFG-004-34, CFG-004-35, CFG-004-36)
            assertThat(config.healthEnabled()).isTrue();
            assertThat(config.healthPath()).isEqualTo("/health");
            assertThat(config.readyPath()).isEqualTo("/ready");

            // Logging defaults (CFG-004-37, CFG-004-38)
            assertThat(config.loggingFormat()).isEqualTo("json");
            assertThat(config.loggingLevel()).isEqualTo("INFO");

            // Shutdown (CFG-004-39)
            assertThat(config.shutdownDrainTimeoutMs()).isEqualTo(30000);

            // Forwarded headers (CFG-004-40)
            assertThat(config.forwardedHeadersEnabled()).isTrue();

            // Admin (CFG-004-41)
            assertThat(config.adminReloadPath()).isEqualTo("/admin/reload");

            // TLS defaults (CFG-004-03)
            assertThat(config.proxyTls()).isNotNull();
            assertThat(config.proxyTls().enabled()).isFalse();

            // Pool defaults (CFG-004-17, CFG-004-18, CFG-004-19)
            assertThat(config.pool()).isNotNull();
            assertThat(config.pool().maxConnections()).isEqualTo(100);
            assertThat(config.pool().keepAlive()).isTrue();
            assertThat(config.pool().idleTimeoutMs()).isEqualTo(60000);
        }

        @Test
        @DisplayName("Backend port auto-derived: http → 80, https → 443")
        void backendPort_autoDerivedFromScheme() {
            ProxyConfig httpConfig = ProxyConfig.builder()
                    .backendHost("backend.local")
                    .backendScheme("http")
                    .build();
            assertThat(httpConfig.backendPort()).isEqualTo(80);

            ProxyConfig httpsConfig = ProxyConfig.builder()
                    .backendHost("backend.local")
                    .backendScheme("https")
                    .build();
            assertThat(httpsConfig.backendPort()).isEqualTo(443);
        }

        @Test
        @DisplayName("Explicit backend port overrides scheme-derived default")
        void backendPort_explicitOverridesDefault() {
            ProxyConfig config = ProxyConfig.builder()
                    .backendHost("backend.local")
                    .backendScheme("https")
                    .backendPort(8443)
                    .build();
            assertThat(config.backendPort()).isEqualTo(8443);
        }
    }

    @Nested
    @DisplayName("Full construction")
    class FullConstruction {

        @Test
        @DisplayName("All fields populated → all accessible")
        void allFieldsPopulated() {
            ProxyConfig config = ProxyConfig.builder()
                    .proxyHost("127.0.0.1")
                    .proxyPort(8080)
                    .backendScheme("https")
                    .backendHost("api.example.com")
                    .backendPort(8443)
                    .backendConnectTimeoutMs(3000)
                    .backendReadTimeoutMs(15000)
                    .maxBodyBytes(5_000_000)
                    .specsDir("/opt/specs")
                    .profilesDir("/opt/profiles")
                    .profilePath("/opt/profiles/main.yaml")
                    .schemaValidation("strict")
                    .reloadEnabled(false)
                    .reloadDebounceMs(1000)
                    .healthEnabled(false)
                    .healthPath("/healthz")
                    .readyPath("/readyz")
                    .loggingFormat("text")
                    .loggingLevel("DEBUG")
                    .shutdownDrainTimeoutMs(60000)
                    .forwardedHeadersEnabled(false)
                    .adminReloadPath("/ops/reload")
                    .proxyTls(new TlsConfig(
                            true,
                            "/path/to/keystore.p12",
                            "changeit",
                            "PKCS12",
                            "need",
                            "/path/to/truststore.p12",
                            "trustpass",
                            "PKCS12"))
                    .backendTls(new BackendTlsConfig(
                            "/path/to/ca.p12", "capass", "PKCS12", true, "/path/to/client.p12", "clientpass", "PKCS12"))
                    .pool(new PoolConfig(200, false, 30000))
                    .build();

            assertThat(config.proxyHost()).isEqualTo("127.0.0.1");
            assertThat(config.proxyPort()).isEqualTo(8080);
            assertThat(config.backendScheme()).isEqualTo("https");
            assertThat(config.backendHost()).isEqualTo("api.example.com");
            assertThat(config.backendPort()).isEqualTo(8443);
            assertThat(config.backendConnectTimeoutMs()).isEqualTo(3000);
            assertThat(config.backendReadTimeoutMs()).isEqualTo(15000);
            assertThat(config.maxBodyBytes()).isEqualTo(5_000_000);
            assertThat(config.specsDir()).isEqualTo("/opt/specs");
            assertThat(config.profilesDir()).isEqualTo("/opt/profiles");
            assertThat(config.profilePath()).isEqualTo("/opt/profiles/main.yaml");
            assertThat(config.schemaValidation()).isEqualTo("strict");
            assertThat(config.reloadEnabled()).isFalse();
            assertThat(config.reloadDebounceMs()).isEqualTo(1000);
            assertThat(config.healthEnabled()).isFalse();
            assertThat(config.healthPath()).isEqualTo("/healthz");
            assertThat(config.readyPath()).isEqualTo("/readyz");
            assertThat(config.loggingFormat()).isEqualTo("text");
            assertThat(config.loggingLevel()).isEqualTo("DEBUG");
            assertThat(config.shutdownDrainTimeoutMs()).isEqualTo(60000);
            assertThat(config.forwardedHeadersEnabled()).isFalse();
            assertThat(config.adminReloadPath()).isEqualTo("/ops/reload");

            // TLS
            assertThat(config.proxyTls().enabled()).isTrue();
            assertThat(config.proxyTls().keystore()).isEqualTo("/path/to/keystore.p12");
            assertThat(config.proxyTls().clientAuth()).isEqualTo("need");

            // Backend TLS
            assertThat(config.backendTls().truststore()).isEqualTo("/path/to/ca.p12");
            assertThat(config.backendTls().verifyHostname()).isTrue();

            // Pool
            assertThat(config.pool().maxConnections()).isEqualTo(200);
            assertThat(config.pool().keepAlive()).isFalse();
            assertThat(config.pool().idleTimeoutMs()).isEqualTo(30000);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("ProxyConfig is a Java record — immutable by design")
        void proxyConfigIsRecord() {
            assertThat(ProxyConfig.class).isRecord();
        }

        @Test
        @DisplayName("TlsConfig is a Java record")
        void tlsConfigIsRecord() {
            assertThat(TlsConfig.class).isRecord();
        }

        @Test
        @DisplayName("BackendTlsConfig is a Java record")
        void backendTlsConfigIsRecord() {
            assertThat(BackendTlsConfig.class).isRecord();
        }

        @Test
        @DisplayName("PoolConfig is a Java record")
        void poolConfigIsRecord() {
            assertThat(PoolConfig.class).isRecord();
        }
    }
}
