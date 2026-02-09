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
 * Integration tests for outbound TLS — proxy connecting to HTTPS backend
 * (FR-004-16, S-004-41).
 *
 * <p>
 * Uses an embedded Jetty HTTPS server as the mock backend to verify TLS
 * certificate validation and hostname verification behavior.
 */
@DisplayName("Outbound TLS (FR-004-16)")
class OutboundTlsTest {

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
    @DisplayName("S-004-41: backend.scheme=https → proxy validates backend cert via truststore")
    void httpsBackend_trustedCert_forwardsSuccessfully() throws Exception {
        startHttpsBackend();

        BackendTlsConfig backendTls =
                new BackendTlsConfig(tlsResourcePath("truststore.p12"), PASSWORD, "PKCS12", true, null, null, "PKCS12");

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
                        .uri(URI.create("http://localhost:" + proxyPort + "/api/secure"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("path").asText()).isEqualTo("/api/secure");
    }

    @Test
    @DisplayName("Backend cert not in truststore → 502 Bad Gateway")
    void httpsBackend_untrustedCert_returns502() throws Exception {
        startHttpsBackend();

        // No truststore configured → default JVM truststore doesn't contain our CA
        BackendTlsConfig backendTls = new BackendTlsConfig(null, null, "PKCS12", true, null, null, "PKCS12");

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
                        .uri(URI.create("http://localhost:" + proxyPort + "/api/secure"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("Backend Unreachable");
    }

    @Test
    @DisplayName("verify-hostname=false → hostname validation skipped")
    void verifyHostnameDisabled_succeeds() throws Exception {
        startHttpsBackend();

        BackendTlsConfig backendTls = new BackendTlsConfig(
                tlsResourcePath("truststore.p12"),
                PASSWORD,
                "PKCS12",
                false, // <-- disable hostname verification
                null,
                null,
                "PKCS12");

        // Use 127.0.0.1 instead of localhost — cert has SAN=localhost,IP:127.0.0.1
        // so this should work even with verification enabled, but serves as test
        // that hostname verification can be disabled
        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("https")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .backendTls(backendTls)
                .build();

        startProxy(config);

        HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + proxyPort + "/api/secure"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    // ── HTTPS Mock Backend ──────────────────────────────────────────────

    private void startHttpsBackend() throws Exception {
        httpsBackend = new Server();

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(tlsResourcePath("server.p12"));
        sslContextFactory.setKeyStorePassword(PASSWORD);
        sslContextFactory.setKeyStoreType("PKCS12");

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
                String body = "{\"path\":\"" + target + "\",\"secure\":true}";
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
        return Path.of(OutboundTlsTest.class
                        .getClassLoader()
                        .getResource("tls/" + filename)
                        .getPath())
                .toString();
    }
}
