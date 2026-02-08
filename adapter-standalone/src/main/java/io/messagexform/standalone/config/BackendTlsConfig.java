package io.messagexform.standalone.config;

/**
 * Outbound (backend-side) TLS configuration (CFG-004-20..26, DO-004-03).
 *
 * @param truststore         CA certs for validating backend server cert
 *                           (CFG-004-20)
 * @param truststorePassword truststore password (CFG-004-21)
 * @param truststoreType     truststore type: PKCS12 or JKS (CFG-004-22)
 * @param verifyHostname     hostname verification for backend TLS (CFG-004-23)
 * @param keystore           client cert for outbound mTLS (CFG-004-24)
 * @param keystorePassword   client keystore password (CFG-004-25)
 * @param keystoreType       client keystore type: PKCS12 or JKS (CFG-004-26)
 */
public record BackendTlsConfig(
        String truststore,
        String truststorePassword,
        String truststoreType,
        boolean verifyHostname,
        String keystore,
        String keystorePassword,
        String keystoreType) {

    /**
     * Default backend TLS configuration — system defaults, hostname verification
     * enabled.
     */
    public static final BackendTlsConfig DEFAULT =
            new BackendTlsConfig(null, null, "PKCS12", true, null, null, "PKCS12");
}
