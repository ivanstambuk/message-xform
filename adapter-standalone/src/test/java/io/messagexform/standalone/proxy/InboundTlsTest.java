package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import io.messagexform.standalone.config.ProxyConfig;
import io.messagexform.standalone.config.TlsConfig;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for inbound TLS (FR-004-14, S-004-39).
 *
 * <p>
 * Verifies that the proxy serves HTTPS when {@code proxy.tls.enabled: true}
 * and rejects plain HTTP connections on the HTTPS port.
 */
@DisplayName("Inbound TLS (FR-004-14)")
class InboundTlsTest {

    private static final String KEYSTORE_PASSWORD = "changeit";

    private Javalin app;
    private HttpServer mockBackend;
    private int backendPort;
    private int proxyPort;

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (mockBackend != null) mockBackend.stop(0);
    }

    @Test
    @DisplayName("S-004-39: proxy.tls.enabled=true → client connects via HTTPS")
    void tlsEnabled_clientConnectsViaHttps() throws Exception {
        startBackend();

        String keystorePath = tlsResourcePath("server.p12");
        String truststorePath = tlsResourcePath("truststore.p12");

        TlsConfig tlsConfig =
                new TlsConfig(true, keystorePath, KEYSTORE_PASSWORD, "PKCS12", "none", null, null, "PKCS12");

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .proxyTls(tlsConfig)
                .build();

        startProxy(config);

        // Create HTTPS client that trusts our CA
        HttpClient httpsClient = createTrustingClient(truststorePath);

        HttpResponse<String> response = httpsClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + proxyPort + "/api/test"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"path\":\"/api/test\"");
    }

    @Test
    @DisplayName("S-004-39: HTTPS → response headers forwarded correctly")
    void tlsEnabled_responseHeadersVisible() throws Exception {
        startBackend();

        String keystorePath = tlsResourcePath("server.p12");
        String truststorePath = tlsResourcePath("truststore.p12");

        TlsConfig tlsConfig =
                new TlsConfig(true, keystorePath, KEYSTORE_PASSWORD, "PKCS12", "none", null, null, "PKCS12");

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .proxyTls(tlsConfig)
                .build();

        startProxy(config);

        HttpClient httpsClient = createTrustingClient(truststorePath);

        HttpResponse<String> response = httpsClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + proxyPort + "/api/test"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("content-type")).isPresent().hasValue("application/json");
    }

    @Test
    @DisplayName("Untrusted client → SSL handshake fails")
    void untrustedClient_sslHandshakeFails() throws Exception {
        startBackend();

        String keystorePath = tlsResourcePath("server.p12");

        TlsConfig tlsConfig =
                new TlsConfig(true, keystorePath, KEYSTORE_PASSWORD, "PKCS12", "none", null, null, "PKCS12");

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .proxyTls(tlsConfig)
                .build();

        startProxy(config);

        // Default HttpClient does NOT trust our self-signed cert
        HttpClient defaultClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        assertThatThrownBy(() -> defaultClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://localhost:" + proxyPort + "/api/test"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .hasCauseInstanceOf(SSLHandshakeException.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void startBackend() throws Exception {
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();
        mockBackend.createContext("/", exchange -> {
            String body = "{\"path\":\"" + exchange.getRequestURI().getPath() + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockBackend.start();
    }

    private void startProxy(ProxyConfig config) {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);

        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(
                engine, adapter, upstreamClient, config.maxBodyBytes(), config.forwardedHeadersEnabled());

        app = Javalin.create(javalinConfig -> {
                    if (config.proxyTls().enabled()) {
                        TlsConfigurator.configureInboundTls(javalinConfig, config.proxyTls());
                    }
                })
                .addHttpHandler(HandlerType.GET, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.POST, "/<path>", proxyHandler)
                .start(0);

        proxyPort = app.port();
    }

    private HttpClient createTrustingClient(String truststorePath) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (var fis = java.nio.file.Files.newInputStream(Path.of(truststorePath))) {
            ts.load(fis, KEYSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(sslContext)
                .build();
    }

    private static String tlsResourcePath(String filename) {
        return Path.of(InboundTlsTest.class
                        .getClassLoader()
                        .getResource("tls/" + filename)
                        .getPath())
                .toString();
    }
}
