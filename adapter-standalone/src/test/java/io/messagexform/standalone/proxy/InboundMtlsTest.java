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
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for inbound mutual TLS (FR-004-15, S-004-40).
 *
 * <p>
 * Verifies that client certificate verification works when
 * {@code proxy.tls.client-auth} is set to {@code need} or {@code want}.
 */
@DisplayName("Inbound mTLS (FR-004-15)")
class InboundMtlsTest {

    private static final String PASSWORD = "changeit";

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
    @DisplayName("S-004-40: client-auth=need + valid client cert → accepted")
    void needClientAuth_validCert_accepted() throws Exception {
        startBackend();
        startProxy("need");

        HttpClient mtlsClient = createMtlsClient();

        HttpResponse<String> response = mtlsClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + proxyPort + "/api/mtls"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"path\":\"/api/mtls\"");
    }

    @Test
    @DisplayName("S-004-40: client-auth=need + no client cert → handshake rejected")
    void needClientAuth_noCert_rejected() throws Exception {
        startBackend();
        startProxy("need");

        // Client trusts the server but provides no client cert
        HttpClient noClientCertClient = createTrustOnlyClient();

        assertThatThrownBy(() -> noClientCertClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://localhost:" + proxyPort + "/api/mtls"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .hasCauseInstanceOf(SSLHandshakeException.class);
    }

    @Test
    @DisplayName("S-004-40: client-auth=want + no client cert → accepted")
    void wantClientAuth_noCert_accepted() throws Exception {
        startBackend();
        startProxy("want");

        // Client trusts the server but provides no client cert
        HttpClient noClientCertClient = createTrustOnlyClient();

        HttpResponse<String> response = noClientCertClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + proxyPort + "/api/mtls"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"path\":\"/api/mtls\"");
    }

    @Test
    @DisplayName("S-004-40: client-auth=want + valid client cert → accepted")
    void wantClientAuth_validCert_accepted() throws Exception {
        startBackend();
        startProxy("want");

        HttpClient mtlsClient = createMtlsClient();

        HttpResponse<String> response = mtlsClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + proxyPort + "/api/mtls"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
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

    private void startProxy(String clientAuth) {
        String keystorePath = tlsResourcePath("server.p12");
        String truststorePath = tlsResourcePath("truststore.p12");

        TlsConfig tlsConfig =
                new TlsConfig(true, keystorePath, PASSWORD, "PKCS12", clientAuth, truststorePath, PASSWORD, "PKCS12");

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .proxyTls(tlsConfig)
                .build();

        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);

        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(
                engine, adapter, upstreamClient, config.maxBodyBytes(), config.forwardedHeadersEnabled());

        app = Javalin.create(javalinConfig -> {
                    TlsConfigurator.configureInboundTls(javalinConfig, tlsConfig);
                })
                .addHttpHandler(HandlerType.GET, "/<path>", proxyHandler)
                .start(0);

        proxyPort = app.port();
    }

    /** Creates an HTTPS client with both client keystore and truststore (mTLS). */
    private HttpClient createMtlsClient() throws Exception {
        // Trust the server's CA
        KeyStore ts = loadKeyStore(tlsResourcePath("truststore.p12"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        // Client identity
        KeyStore ks = loadKeyStore(tlsResourcePath("client.p12"));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(sslContext)
                .build();
    }

    /** Creates an HTTPS client that trusts the server but has NO client cert. */
    private HttpClient createTrustOnlyClient() throws Exception {
        KeyStore ts = loadKeyStore(tlsResourcePath("truststore.p12"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(sslContext)
                .build();
    }

    private static KeyStore loadKeyStore(String path) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var fis = java.nio.file.Files.newInputStream(Path.of(path))) {
            ks.load(fis, PASSWORD.toCharArray());
        }
        return ks;
    }

    private static String tlsResourcePath(String filename) {
        return Path.of(InboundMtlsTest.class
                        .getClassLoader()
                        .getResource("tls/" + filename)
                        .getPath())
                .toString();
    }
}
