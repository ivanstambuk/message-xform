package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.standalone.config.BackendTlsConfig;
import io.messagexform.standalone.config.TlsConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for TLS configuration validation at startup (S-004-43).
 *
 * <p>
 * Verifies that invalid keystore/truststore paths, wrong passwords, and
 * missing required files produce descriptive errors at startup rather than
 * cryptic runtime failures.
 */
@DisplayName("TLS config validation (S-004-43)")
class TlsConfigValidationTest {

    private static final String PASSWORD = "changeit";

    @Nested
    @DisplayName("Inbound TLS validation")
    class InboundTls {

        @Test
        @DisplayName("Valid inbound TLS config → no error")
        void validInboundTls_noError() {
            TlsConfig tlsConfig = new TlsConfig(
                    true, tlsResourcePath("server.p12"), PASSWORD, "PKCS12", "none", null, null, "PKCS12");

            assertThatCode(() -> TlsConfigValidator.validateInbound(tlsConfig)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Invalid keystore path → descriptive startup error")
        void invalidKeystorePath_startsupError() {
            TlsConfig tlsConfig =
                    new TlsConfig(true, "/nonexistent/server.p12", PASSWORD, "PKCS12", "none", null, null, "PKCS12");

            assertThatThrownBy(() -> TlsConfigValidator.validateInbound(tlsConfig))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("keystore")
                    .hasMessageContaining("/nonexistent/server.p12");
        }

        @Test
        @DisplayName("Wrong keystore password → descriptive startup error")
        void wrongKeystorePassword_startupError() {
            TlsConfig tlsConfig = new TlsConfig(
                    true, tlsResourcePath("server.p12"), "wrongpassword", "PKCS12", "none", null, null, "PKCS12");

            assertThatThrownBy(() -> TlsConfigValidator.validateInbound(tlsConfig))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("keystore");
        }

        @Test
        @DisplayName("client-auth=need but no truststore → descriptive error")
        void needClientAuth_noTruststore_error() {
            TlsConfig tlsConfig = new TlsConfig(
                    true, tlsResourcePath("server.p12"), PASSWORD, "PKCS12", "need", null, null, "PKCS12");

            assertThatThrownBy(() -> TlsConfigValidator.validateInbound(tlsConfig))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("truststore")
                    .hasMessageContaining("client-auth");
        }

        @Test
        @DisplayName("client-auth=want but no truststore → descriptive error")
        void wantClientAuth_noTruststore_error() {
            TlsConfig tlsConfig = new TlsConfig(
                    true, tlsResourcePath("server.p12"), PASSWORD, "PKCS12", "want", null, null, "PKCS12");

            assertThatThrownBy(() -> TlsConfigValidator.validateInbound(tlsConfig))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("truststore");
        }

        @Test
        @DisplayName("TLS disabled → no validation needed")
        void tlsDisabled_noValidation() {
            TlsConfig tlsConfig = TlsConfig.DISABLED;

            assertThatCode(() -> TlsConfigValidator.validateInbound(tlsConfig)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Enabled but no keystore path → descriptive error")
        void enabledNoKeystore_error() {
            TlsConfig tlsConfig = new TlsConfig(true, null, PASSWORD, "PKCS12", "none", null, null, "PKCS12");

            assertThatThrownBy(() -> TlsConfigValidator.validateInbound(tlsConfig))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("keystore");
        }
    }

    @Nested
    @DisplayName("Outbound TLS validation")
    class OutboundTls {

        @Test
        @DisplayName("Valid outbound TLS config → no error")
        void validOutboundTls_noError() {
            BackendTlsConfig backendTls = new BackendTlsConfig(
                    tlsResourcePath("truststore.p12"), PASSWORD, "PKCS12", true, null, null, "PKCS12");

            assertThatCode(() -> TlsConfigValidator.validateOutbound(backendTls, "https"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Invalid truststore path → descriptive error")
        void invalidTruststorePath_error() {
            BackendTlsConfig backendTls =
                    new BackendTlsConfig("/nonexistent/truststore.p12", PASSWORD, "PKCS12", true, null, null, "PKCS12");

            assertThatThrownBy(() -> TlsConfigValidator.validateOutbound(backendTls, "https"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("truststore")
                    .hasMessageContaining("/nonexistent/truststore.p12");
        }

        @Test
        @DisplayName("Invalid client keystore path → descriptive error")
        void invalidClientKeystorePath_error() {
            BackendTlsConfig backendTls = new BackendTlsConfig(
                    tlsResourcePath("truststore.p12"),
                    PASSWORD,
                    "PKCS12",
                    true,
                    "/nonexistent/client.p12",
                    PASSWORD,
                    "PKCS12");

            assertThatThrownBy(() -> TlsConfigValidator.validateOutbound(backendTls, "https"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("keystore")
                    .hasMessageContaining("/nonexistent/client.p12");
        }

        @Test
        @DisplayName("Wrong truststore password → descriptive error")
        void wrongTruststorePassword_error() {
            BackendTlsConfig backendTls = new BackendTlsConfig(
                    tlsResourcePath("truststore.p12"), "wrongpassword", "PKCS12", true, null, null, "PKCS12");

            assertThatThrownBy(() -> TlsConfigValidator.validateOutbound(backendTls, "https"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("truststore");
        }

        @Test
        @DisplayName("HTTP scheme → no validation needed")
        void httpScheme_noValidation() {
            BackendTlsConfig backendTls = BackendTlsConfig.DEFAULT;

            assertThatCode(() -> TlsConfigValidator.validateOutbound(backendTls, "http"))
                    .doesNotThrowAnyException();
        }
    }

    private static String tlsResourcePath(String filename) {
        return Path.of(TlsConfigValidationTest.class
                        .getClassLoader()
                        .getResource("tls/" + filename)
                        .getPath())
                .toString();
    }
}
