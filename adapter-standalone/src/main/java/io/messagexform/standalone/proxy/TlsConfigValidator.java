package io.messagexform.standalone.proxy;

import io.messagexform.standalone.config.BackendTlsConfig;
import io.messagexform.standalone.config.TlsConfig;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates TLS configuration at startup (S-004-43).
 *
 * <p>
 * Checks that keystore/truststore files exist, are readable, and have
 * correct passwords. Called during server startup to provide descriptive
 * error messages instead of cryptic runtime failures.
 */
public final class TlsConfigValidator {

    private static final Logger LOG = LoggerFactory.getLogger(TlsConfigValidator.class);

    private TlsConfigValidator() {}

    /**
     * Validates inbound (proxy-side) TLS configuration.
     *
     * @param tlsConfig the inbound TLS configuration to validate
     * @throws IllegalStateException if any configuration is invalid
     */
    public static void validateInbound(TlsConfig tlsConfig) {
        if (!tlsConfig.enabled()) {
            return; // TLS disabled — no validation needed
        }

        // Keystore is required when TLS is enabled
        if (tlsConfig.keystore() == null || tlsConfig.keystore().isBlank()) {
            throw new IllegalStateException("Inbound TLS enabled but no keystore path configured. "
                    + "Set proxy.tls.keystore to a valid PKCS12 or JKS keystore file.");
        }

        verifyKeystore(
                "Inbound TLS keystore", tlsConfig.keystore(), tlsConfig.keystorePassword(), tlsConfig.keystoreType());

        // Truststore is required when client-auth is 'need' or 'want'
        if ("need".equals(tlsConfig.clientAuth()) || "want".equals(tlsConfig.clientAuth())) {
            if (tlsConfig.truststore() == null || tlsConfig.truststore().isBlank()) {
                throw new IllegalStateException("proxy.tls.client-auth=" + tlsConfig.clientAuth()
                        + " requires a truststore for client certificate validation. "
                        + "Set proxy.tls.truststore to a valid truststore file.");
            }
            verifyKeystore(
                    "Inbound mTLS truststore",
                    tlsConfig.truststore(),
                    tlsConfig.truststorePassword(),
                    tlsConfig.truststoreType());
        }

        LOG.info("Inbound TLS configuration validated successfully");
    }

    /**
     * Validates outbound (backend-side) TLS configuration.
     *
     * @param backendTls the outbound TLS configuration to validate
     * @param scheme     the backend scheme (http or https)
     * @throws IllegalStateException if any configuration is invalid
     */
    public static void validateOutbound(BackendTlsConfig backendTls, String scheme) {
        if (!"https".equalsIgnoreCase(scheme)) {
            return; // No outbound TLS — no validation needed
        }

        // Validate truststore if configured
        if (backendTls.truststore() != null && !backendTls.truststore().isBlank()) {
            verifyKeystore(
                    "Outbound TLS truststore",
                    backendTls.truststore(),
                    backendTls.truststorePassword(),
                    backendTls.truststoreType());
        }

        // Validate client keystore if configured (outbound mTLS)
        if (backendTls.keystore() != null && !backendTls.keystore().isBlank()) {
            verifyKeystore(
                    "Outbound mTLS client keystore",
                    backendTls.keystore(),
                    backendTls.keystorePassword(),
                    backendTls.keystoreType());
        }

        LOG.info("Outbound TLS configuration validated successfully");
    }

    /**
     * Verifies that a keystore/truststore file exists, is readable, and can
     * be loaded with the given password and type.
     */
    private static void verifyKeystore(String label, String path, String password, String type) {
        Path storePath = Path.of(path);

        if (!Files.exists(storePath)) {
            throw new IllegalStateException(label + " file does not exist: " + path);
        }

        if (!Files.isReadable(storePath)) {
            throw new IllegalStateException(label + " file is not readable: " + path);
        }

        try {
            KeyStore ks = KeyStore.getInstance(type);
            try (InputStream is = Files.newInputStream(storePath)) {
                ks.load(is, password != null ? password.toCharArray() : null);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    label + " could not be loaded (wrong password or corrupt file?): " + path + " — " + e.getMessage(),
                    e);
        }
    }
}
