package io.messagexform.standalone.proxy;

import io.javalin.config.JavalinConfig;
import io.messagexform.standalone.config.TlsConfig;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures Javalin's embedded Jetty for inbound TLS (HTTPS) serving
 * (FR-004-14, FR-004-15).
 *
 * <p>
 * Uses Jetty 11's {@link SslContextFactory.Server} to terminate TLS
 * with configurable keystore, truststore, and mTLS client authentication.
 */
public final class TlsConfigurator {

    private static final Logger LOG = LoggerFactory.getLogger(TlsConfigurator.class);

    private TlsConfigurator() {}

    /**
     * Configures inbound TLS on the Javalin server.
     *
     * <p>
     * Adds an HTTPS {@link ServerConnector} to the Jetty server using
     * the keystore, truststore, and client-auth settings from the
     * {@link TlsConfig}. The connector binds to port 0 (ephemeral) for testing
     * — the actual port is set by {@code Javalin.start(port)}.
     *
     * @param javalinConfig the Javalin configuration to modify
     * @param tlsConfig     the inbound TLS configuration
     */
    public static void configureInboundTls(JavalinConfig javalinConfig, TlsConfig tlsConfig) {
        javalinConfig.jetty.addConnector((server, httpConfig) -> {
            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

            // Server keystore (FR-004-14)
            sslContextFactory.setKeyStorePath(tlsConfig.keystore());
            sslContextFactory.setKeyStorePassword(tlsConfig.keystorePassword());
            sslContextFactory.setKeyStoreType(tlsConfig.keystoreType());

            // Client authentication (FR-004-15, CFG-004-07)
            switch (tlsConfig.clientAuth()) {
                case "need" -> {
                    sslContextFactory.setNeedClientAuth(true);
                    configureTruststore(sslContextFactory, tlsConfig);
                }
                case "want" -> {
                    sslContextFactory.setWantClientAuth(true);
                    configureTruststore(sslContextFactory, tlsConfig);
                }
                default -> {
                    // "none" — no client certificate required
                }
            }

            // HTTPS configuration
            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());

            ServerConnector sslConnector = new ServerConnector(
                    server,
                    new SslConnectionFactory(sslContextFactory, "http/1.1"),
                    new HttpConnectionFactory(httpsConfig));

            LOG.info(
                    "Inbound TLS configured: keystore={}, keystoreType={}, clientAuth={}",
                    tlsConfig.keystore(),
                    tlsConfig.keystoreType(),
                    tlsConfig.clientAuth());

            return sslConnector;
        });
    }

    private static void configureTruststore(SslContextFactory.Server sslContextFactory, TlsConfig tlsConfig) {
        if (tlsConfig.truststore() != null) {
            sslContextFactory.setTrustStorePath(tlsConfig.truststore());
            sslContextFactory.setTrustStorePassword(tlsConfig.truststorePassword());
            sslContextFactory.setTrustStoreType(tlsConfig.truststoreType());
        }
    }
}
