package io.messagexform.standalone.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for environment variable overlay on {@link ConfigLoader} (T-004-07,
 * FR-004-11).
 *
 * <p>
 * Every configuration key MUST be overridable via an environment variable.
 * Env vars take precedence over YAML values. An env var is considered "set"
 * if and only if it is defined AND its trimmed value is non-empty; empty or
 * whitespace-only values are treated as "unset" (YAML value used).
 *
 * <p>
 * Uses a custom {@code Function<String, String>} env provider for testability
 * (avoids dependency on real OS environment variables).
 */
@DisplayName("T-004-07: Environment variable overlay")
class EnvVarOverlayTest {

    /** Env var map that the test populates; passed as lookup function. */
    private final Map<String, String> envVars = new HashMap<>();

    /** Builds an env lookup function from the test map. */
    private Function<String, String> envLookup() {
        return envVars::get;
    }

    /** Minimal config fixture (backend.host + backend.port only). */
    private Path minimalConfigPath;

    /** Full config fixture (all fields populated). */
    private Path fullConfigPath;

    @BeforeEach
    void setUp() throws Exception {
        minimalConfigPath = Path.of(EnvVarOverlayTest.class
                .getClassLoader()
                .getResource("config/minimal-config.yaml")
                .toURI());
        fullConfigPath = Path.of(EnvVarOverlayTest.class
                .getClassLoader()
                .getResource("config/full-config.yaml")
                .toURI());
        envVars.clear();
    }

    // -----------------------------------------------------------------------
    // String overrides
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("String overrides")
    class StringOverrides {

        @Test
        @DisplayName("BACKEND_HOST overrides YAML backend.host (S-004-25)")
        void backendHost_overriddenByEnvVar() {
            envVars.put("BACKEND_HOST", "override-host");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendHost()).isEqualTo("override-host");
        }

        @Test
        @DisplayName("PROXY_HOST overrides YAML proxy.host")
        void proxyHost_overriddenByEnvVar() {
            envVars.put("PROXY_HOST", "10.0.0.1");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyHost()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("BACKEND_SCHEME overrides YAML backend.scheme")
        void backendScheme_overriddenByEnvVar() {
            envVars.put("BACKEND_SCHEME", "https");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendScheme()).isEqualTo("https");
        }

        @Test
        @DisplayName("SPECS_DIR overrides YAML engine.specs-dir")
        void specsDir_overriddenByEnvVar() {
            envVars.put("SPECS_DIR", "/custom/specs");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.specsDir()).isEqualTo("/custom/specs");
        }

        @Test
        @DisplayName("PROFILES_DIR overrides YAML engine.profiles-dir")
        void profilesDir_overriddenByEnvVar() {
            envVars.put("PROFILES_DIR", "/custom/profiles");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.profilesDir()).isEqualTo("/custom/profiles");
        }

        @Test
        @DisplayName("ENGINE_PROFILE overrides YAML engine.profile")
        void engineProfile_overriddenByEnvVar() {
            envVars.put("ENGINE_PROFILE", "/opt/my-profile.yaml");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.profilePath()).isEqualTo("/opt/my-profile.yaml");
        }

        @Test
        @DisplayName("SCHEMA_VALIDATION overrides YAML engine.schema-validation")
        void schemaValidation_overriddenByEnvVar() {
            envVars.put("SCHEMA_VALIDATION", "strict");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.schemaValidation()).isEqualTo("strict");
        }

        @Test
        @DisplayName("HEALTH_PATH overrides YAML health.path")
        void healthPath_overriddenByEnvVar() {
            envVars.put("HEALTH_PATH", "/healthz");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.healthPath()).isEqualTo("/healthz");
        }

        @Test
        @DisplayName("HEALTH_READY_PATH overrides YAML health.ready-path")
        void healthReadyPath_overriddenByEnvVar() {
            envVars.put("HEALTH_READY_PATH", "/readyz");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.readyPath()).isEqualTo("/readyz");
        }

        @Test
        @DisplayName("LOG_FORMAT overrides YAML logging.format")
        void logFormat_overriddenByEnvVar() {
            envVars.put("LOG_FORMAT", "text");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.loggingFormat()).isEqualTo("text");
        }

        @Test
        @DisplayName("LOG_LEVEL overrides YAML logging.level")
        void logLevel_overriddenByEnvVar() {
            envVars.put("LOG_LEVEL", "DEBUG");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.loggingLevel()).isEqualTo("DEBUG");
        }

        @Test
        @DisplayName("ADMIN_RELOAD_PATH overrides YAML admin.reload-path")
        void adminReloadPath_overriddenByEnvVar() {
            envVars.put("ADMIN_RELOAD_PATH", "/ops/reload");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.adminReloadPath()).isEqualTo("/ops/reload");
        }
    }

    // -----------------------------------------------------------------------
    // Integer overrides
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Integer overrides")
    class IntegerOverrides {

        @Test
        @DisplayName("PROXY_PORT overrides YAML proxy.port")
        void proxyPort_overriddenByEnvVar() {
            envVars.put("PROXY_PORT", "8080");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyPort()).isEqualTo(8080);
        }

        @Test
        @DisplayName("BACKEND_PORT overrides YAML backend.port")
        void backendPort_overriddenByEnvVar() {
            envVars.put("BACKEND_PORT", "3000");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendPort()).isEqualTo(3000);
        }

        @Test
        @DisplayName("BACKEND_CONNECT_TIMEOUT_MS overrides YAML backend.connect-timeout-ms")
        void backendConnectTimeoutMs_overriddenByEnvVar() {
            envVars.put("BACKEND_CONNECT_TIMEOUT_MS", "10000");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendConnectTimeoutMs()).isEqualTo(10000);
        }

        @Test
        @DisplayName("BACKEND_READ_TIMEOUT_MS overrides YAML backend.read-timeout-ms")
        void backendReadTimeoutMs_overriddenByEnvVar() {
            envVars.put("BACKEND_READ_TIMEOUT_MS", "60000");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendReadTimeoutMs()).isEqualTo(60000);
        }

        @Test
        @DisplayName("PROXY_MAX_BODY_BYTES overrides YAML proxy.max-body-bytes")
        void maxBodyBytes_overriddenByEnvVar() {
            envVars.put("PROXY_MAX_BODY_BYTES", "1048576");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.maxBodyBytes()).isEqualTo(1_048_576);
        }

        @Test
        @DisplayName("RELOAD_DEBOUNCE_MS overrides YAML reload.debounce-ms")
        void reloadDebounceMs_overriddenByEnvVar() {
            envVars.put("RELOAD_DEBOUNCE_MS", "1000");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.reloadDebounceMs()).isEqualTo(1000);
        }

        @Test
        @DisplayName("PROXY_SHUTDOWN_DRAIN_TIMEOUT_MS overrides YAML proxy.shutdown.drain-timeout-ms")
        void shutdownDrainTimeoutMs_overriddenByEnvVar() {
            envVars.put("PROXY_SHUTDOWN_DRAIN_TIMEOUT_MS", "60000");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.shutdownDrainTimeoutMs()).isEqualTo(60000);
        }
    }

    // -----------------------------------------------------------------------
    // Boolean overrides
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Boolean overrides")
    class BooleanOverrides {

        @Test
        @DisplayName("RELOAD_ENABLED=false overrides YAML reload.enabled")
        void reloadEnabled_overriddenByEnvVar() {
            envVars.put("RELOAD_ENABLED", "false");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.reloadEnabled()).isFalse();
        }

        @Test
        @DisplayName("HEALTH_ENABLED=false overrides YAML health.enabled")
        void healthEnabled_overriddenByEnvVar() {
            envVars.put("HEALTH_ENABLED", "false");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.healthEnabled()).isFalse();
        }

        @Test
        @DisplayName("PROXY_FORWARDED_HEADERS_ENABLED=false overrides YAML proxy.forwarded-headers.enabled")
        void forwardedHeadersEnabled_overriddenByEnvVar() {
            envVars.put("PROXY_FORWARDED_HEADERS_ENABLED", "false");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.forwardedHeadersEnabled()).isFalse();
        }

        @Test
        @DisplayName("PROXY_TLS_ENABLED=true overrides YAML proxy.tls.enabled")
        void proxyTlsEnabled_overriddenByEnvVar() {
            envVars.put("PROXY_TLS_ENABLED", "true");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyTls().enabled()).isTrue();
        }

        @Test
        @DisplayName("BACKEND_TLS_VERIFY_HOSTNAME=false overrides YAML backend.tls.verify-hostname")
        void backendTlsVerifyHostname_overriddenByEnvVar() {
            envVars.put("BACKEND_TLS_VERIFY_HOSTNAME", "false");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendTls().verifyHostname()).isFalse();
        }

        @Test
        @DisplayName("BACKEND_POOL_KEEP_ALIVE=false overrides YAML backend.pool.keep-alive")
        void backendPoolKeepAlive_overriddenByEnvVar() {
            envVars.put("BACKEND_POOL_KEEP_ALIVE", "false");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.pool().keepAlive()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // TLS string overrides (representative subset of nested TLS fields)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TLS env var overrides")
    class TlsOverrides {

        @Test
        @DisplayName("PROXY_TLS_KEYSTORE overrides YAML proxy.tls.keystore")
        void proxyTlsKeystore_overriddenByEnvVar() {
            envVars.put("PROXY_TLS_KEYSTORE", "/env/server.p12");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyTls().keystore()).isEqualTo("/env/server.p12");
        }

        @Test
        @DisplayName("PROXY_TLS_KEYSTORE_PASSWORD overrides YAML proxy.tls.keystore-password")
        void proxyTlsKeystorePassword_overriddenByEnvVar() {
            envVars.put("PROXY_TLS_KEYSTORE_PASSWORD", "envpass");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyTls().keystorePassword()).isEqualTo("envpass");
        }

        @Test
        @DisplayName("PROXY_TLS_KEYSTORE_TYPE overrides YAML proxy.tls.keystore-type")
        void proxyTlsKeystoreType_overriddenByEnvVar() {
            envVars.put("PROXY_TLS_KEYSTORE_TYPE", "JKS");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyTls().keystoreType()).isEqualTo("JKS");
        }

        @Test
        @DisplayName("PROXY_TLS_CLIENT_AUTH overrides YAML proxy.tls.client-auth")
        void proxyTlsClientAuth_overriddenByEnvVar() {
            envVars.put("PROXY_TLS_CLIENT_AUTH", "need");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyTls().clientAuth()).isEqualTo("need");
        }

        @Test
        @DisplayName("PROXY_TLS_TRUSTSTORE overrides YAML proxy.tls.truststore")
        void proxyTlsTruststore_overriddenByEnvVar() {
            envVars.put("PROXY_TLS_TRUSTSTORE", "/env/trust.p12");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyTls().truststore()).isEqualTo("/env/trust.p12");
        }

        @Test
        @DisplayName("PROXY_TLS_TRUSTSTORE_PASSWORD overrides YAML proxy.tls.truststore-password")
        void proxyTlsTruststorePassword_overriddenByEnvVar() {
            envVars.put("PROXY_TLS_TRUSTSTORE_PASSWORD", "envtrustpass");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyTls().truststorePassword()).isEqualTo("envtrustpass");
        }

        @Test
        @DisplayName("PROXY_TLS_TRUSTSTORE_TYPE overrides YAML proxy.tls.truststore-type")
        void proxyTlsTruststoreType_overriddenByEnvVar() {
            envVars.put("PROXY_TLS_TRUSTSTORE_TYPE", "JKS");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyTls().truststoreType()).isEqualTo("JKS");
        }

        @Test
        @DisplayName("BACKEND_TLS_TRUSTSTORE overrides YAML backend.tls.truststore")
        void backendTlsTruststore_overriddenByEnvVar() {
            envVars.put("BACKEND_TLS_TRUSTSTORE", "/env/backend-ca.p12");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendTls().truststore()).isEqualTo("/env/backend-ca.p12");
        }

        @Test
        @DisplayName("BACKEND_TLS_TRUSTSTORE_PASSWORD overrides YAML backend.tls.truststore-password")
        void backendTlsTruststorePassword_overriddenByEnvVar() {
            envVars.put("BACKEND_TLS_TRUSTSTORE_PASSWORD", "envbackendtrust");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendTls().truststorePassword()).isEqualTo("envbackendtrust");
        }

        @Test
        @DisplayName("BACKEND_TLS_TRUSTSTORE_TYPE overrides YAML backend.tls.truststore-type")
        void backendTlsTruststoreType_overriddenByEnvVar() {
            envVars.put("BACKEND_TLS_TRUSTSTORE_TYPE", "JKS");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendTls().truststoreType()).isEqualTo("JKS");
        }

        @Test
        @DisplayName("BACKEND_TLS_KEYSTORE overrides YAML backend.tls.keystore")
        void backendTlsKeystore_overriddenByEnvVar() {
            envVars.put("BACKEND_TLS_KEYSTORE", "/env/client.p12");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendTls().keystore()).isEqualTo("/env/client.p12");
        }

        @Test
        @DisplayName("BACKEND_TLS_KEYSTORE_PASSWORD overrides YAML backend.tls.keystore-password")
        void backendTlsKeystorePassword_overriddenByEnvVar() {
            envVars.put("BACKEND_TLS_KEYSTORE_PASSWORD", "envclientpass");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendTls().keystorePassword()).isEqualTo("envclientpass");
        }

        @Test
        @DisplayName("BACKEND_TLS_KEYSTORE_TYPE overrides YAML backend.tls.keystore-type")
        void backendTlsKeystoreType_overriddenByEnvVar() {
            envVars.put("BACKEND_TLS_KEYSTORE_TYPE", "JKS");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendTls().keystoreType()).isEqualTo("JKS");
        }
    }

    // -----------------------------------------------------------------------
    // Pool overrides
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Pool env var overrides")
    class PoolOverrides {

        @Test
        @DisplayName("BACKEND_POOL_MAX_CONNECTIONS overrides YAML backend.pool.max-connections")
        void poolMaxConnections_overriddenByEnvVar() {
            envVars.put("BACKEND_POOL_MAX_CONNECTIONS", "50");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.pool().maxConnections()).isEqualTo(50);
        }

        @Test
        @DisplayName("BACKEND_POOL_IDLE_TIMEOUT_MS overrides YAML backend.pool.idle-timeout-ms")
        void poolIdleTimeoutMs_overriddenByEnvVar() {
            envVars.put("BACKEND_POOL_IDLE_TIMEOUT_MS", "30000");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.pool().idleTimeoutMs()).isEqualTo(30000);
        }
    }

    // -----------------------------------------------------------------------
    // Empty / whitespace-only treated as unset (FR-004-11)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Empty / whitespace-only env vars treated as unset")
    class EmptyAndWhitespaceEnvVars {

        @Test
        @DisplayName("Empty string env var → YAML value used")
        void emptyEnvVar_yamlValueUsed() {
            envVars.put("BACKEND_HOST", "");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendHost()).isEqualTo("backend.local");
        }

        @Test
        @DisplayName("Whitespace-only env var → YAML value used")
        void whitespaceOnlyEnvVar_yamlValueUsed() {
            envVars.put("BACKEND_HOST", "   ");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendHost()).isEqualTo("backend.local");
        }

        @Test
        @DisplayName("Whitespace-only integer env var → YAML value used")
        void whitespaceOnlyIntegerEnvVar_yamlValueUsed() {
            envVars.put("PROXY_PORT", "  ");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.proxyPort()).isEqualTo(9090);
        }

        @Test
        @DisplayName("Whitespace-only boolean env var → YAML value used")
        void whitespaceOnlyBooleanEnvVar_yamlValueUsed() {
            envVars.put("RELOAD_ENABLED", " ");
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.reloadEnabled()).isTrue();
        }

        @Test
        @DisplayName("Undefined env var → YAML value used")
        void undefinedEnvVar_yamlValueUsed() {
            // envVars map doesn't contain BACKEND_HOST → lookup returns null
            ProxyConfig config = ConfigLoader.load(minimalConfigPath, envLookup());
            assertThat(config.backendHost()).isEqualTo("backend.local");
        }
    }

    // -----------------------------------------------------------------------
    // Env var overrides full YAML config (precedence test)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Env var precedence over YAML")
    class EnvVarPrecedence {

        @Test
        @DisplayName("Env var overrides explicit YAML value in full config")
        void envVar_overridesExplicitYamlValue() {
            envVars.put("BACKEND_HOST", "env-host");
            envVars.put("PROXY_PORT", "7070");
            envVars.put("LOG_LEVEL", "TRACE");
            ProxyConfig config = ConfigLoader.load(fullConfigPath, envLookup());

            assertThat(config.backendHost()).isEqualTo("env-host");
            assertThat(config.proxyPort()).isEqualTo(7070);
            assertThat(config.loggingLevel()).isEqualTo("TRACE");

            // Non-overridden values remain from YAML
            assertThat(config.backendScheme()).isEqualTo("https");
            assertThat(config.backendPort()).isEqualTo(8443);
        }
    }

    // -----------------------------------------------------------------------
    // No env vars → identical to YAML-only load
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("No env vars → YAML-only behaviour")
    class NoEnvVars {

        @Test
        @DisplayName("No env vars → load(path) and load(path, envLookup) produce identical configs")
        void noEnvVars_identicalToYamlOnlyLoad() {
            ProxyConfig yamlOnly = ConfigLoader.load(minimalConfigPath);
            ProxyConfig withEmptyEnv = ConfigLoader.load(minimalConfigPath, envLookup());

            assertThat(withEmptyEnv).isEqualTo(yamlOnly);
        }
    }
}
