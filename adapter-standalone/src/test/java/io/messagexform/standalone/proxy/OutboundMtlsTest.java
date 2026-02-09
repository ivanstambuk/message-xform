package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import io.messagexform.standalone.config.BackendTlsConfig;
import io.messagexform.standalone.config.ProxyConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for outbound mutual TLS — proxy presents a client
 * certificate to the backend (FR-004-17, S-004-42).
 *
 * <p>
 * Uses an embedded Jetty HTTPS backend configured to require client
 * certificates ({@code needClientAuth}) to verify that the proxy correctly
 * presents its client cert from the configured keystore.
 */
@DisplayName("Outbound mTLS (FR-004-17)")
class OutboundMtlsTest {

    private static final String PASSWORD = "changeit";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Javalin proxyApp;
    private Server httpsBackend;
    private int backendPort;
    private int proxyPort;

    @AfterEach
    void tearDown() throws Exception {
        if (proxyApp != null) proxyApp.stop();
        if (httpsBackend != null) httpsBackend.stop();
    }

    @Test
    @DisplayName("S-004-42: backend.tls.keystore configured → proxy presents client cert → backend accepts")
    void outboundMtls_clientCertPresented_backendAccepts() throws Exception {
        startMtlsBackend();

        BackendTlsConfig backendTls = new BackendTlsConfig(
                tlsResourcePath("truststore.p12"),
                PASSWORD,
                "PKCS12",
                true,
                tlsResourcePath("client.p12"),
                PASSWORD,
                "PKCS12"); // client keystore for mTLS

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("https")
                .backendHost("localhost")
                .backendPort(backendPort)
                .backendTls(backendTls)
                .build();

        startProxy(config);

        HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + proxyPort + "/api/mtls-verify"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("path").asText()).isEqualTo("/api/mtls-verify");
        assertThat(body.path("clientAuth").asText()).isEqualTo("true");
    }

    @Test
    @DisplayName("No client keystore → backend rejects → 502")
    void outboundMtls_noClientCert_backendRejects() throws Exception {
        startMtlsBackend();

        // Trust the backend's CA but do NOT provide a client keystore
        BackendTlsConfig backendTls = new BackendTlsConfig(
                tlsResourcePath("truststore.p12"),
                PASSWORD,
                "PKCS12",
                true,
                null,
                null,
                "PKCS12"); // no client keystore

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("https")
                .backendHost("localhost")
                .backendPort(backendPort)
                .backendTls(backendTls)
                .build();

        startProxy(config);

        HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + proxyPort + "/api/mtls-verify"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Backend requires client cert → handshake failure → 502
        assertThat(response.statusCode()).isEqualTo(502);
    }

    // ── HTTPS mTLS Mock Backend ────────────────────────────────────────

    private void startMtlsBackend() throws Exception {
        httpsBackend = new Server();

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(tlsResourcePath("server.p12"));
        sslContextFactory.setKeyStorePassword(PASSWORD);
        sslContextFactory.setKeyStoreType("PKCS12");
        sslContextFactory.setNeedClientAuth(true);
        sslContextFactory.setTrustStorePath(tlsResourcePath("truststore.p12"));
        sslContextFactory.setTrustStorePassword(PASSWORD);
        sslContextFactory.setTrustStoreType("PKCS12");

        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        ServerConnector sslConnector = new ServerConnector(
                httpsBackend,
                new SslConnectionFactory(sslContextFactory, "http/1.1"),
                new HttpConnectionFactory(httpsConfig));
        sslConnector.setHost("127.0.0.1");
        sslConnector.setPort(0);
        httpsBackend.addConnector(sslConnector);

        httpsBackend.setHandler(new AbstractHandler() {
            @Override
            public void handle(
                    String target,
                    org.eclipse.jetty.server.Request baseRequest,
                    jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response)
                    throws java.io.IOException {
                response.setStatus(200);
                response.setContentType("application/json");
                // Report whether a client cert was presented
                boolean hasClientCert = request.getAttribute("jakarta.servlet.request.X509Certificate") != null;
                String body = "{\"path\":\"" + target + "\",\"clientAuth\":\"" + hasClientCert + "\"}";
                response.getWriter().print(body);
                baseRequest.setHandled(true);
            }
        });

        httpsBackend.start();
        backendPort = sslConnector.getLocalPort();
    }

    // ── Proxy ───────────────────────────────────────────────────────────

    private void startProxy(ProxyConfig config) {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);

        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(
                engine, adapter, upstreamClient, config.maxBodyBytes(), config.forwardedHeadersEnabled());

        proxyApp = Javalin.create()
                .addHttpHandler(HandlerType.GET, "/<path>", proxyHandler)
                .start(0);

        proxyPort = proxyApp.port();
    }

    private static String tlsResourcePath(String filename) {
        return Path.of(OutboundMtlsTest.class
                        .getClassLoader()
                        .getResource("tls/" + filename)
                        .getPath())
                .toString();
    }
}
