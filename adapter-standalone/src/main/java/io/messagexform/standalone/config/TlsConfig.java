package io.messagexform.standalone.config;

/**
 * Inbound (proxy-side) TLS configuration (CFG-004-03..10, DO-004-03).
 *
 * @param enabled            whether inbound HTTPS is enabled (CFG-004-03)
 * @param keystore           path to server certificate keystore (CFG-004-04)
 * @param keystorePassword   keystore password (CFG-004-05)
 * @param keystoreType       keystore type: PKCS12 or JKS (CFG-004-06)
 * @param clientAuth         client authentication mode: none, want, need
 *                           (CFG-004-07)
 * @param truststore         client CA truststore for mTLS (CFG-004-08)
 * @param truststorePassword truststore password (CFG-004-09)
 * @param truststoreType     truststore type: PKCS12 or JKS (CFG-004-10)
 */
public record TlsConfig(
        boolean enabled,
        String keystore,
        String keystorePassword,
        String keystoreType,
        String clientAuth,
        String truststore,
        String truststorePassword,
        String truststoreType) {

    /** Default TLS configuration — TLS disabled. */
    public static final TlsConfig DISABLED = new TlsConfig(false, null, null, "PKCS12", "none", null, null, "PKCS12");
}
