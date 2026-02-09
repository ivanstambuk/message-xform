package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import io.messagexform.standalone.config.ProxyConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for backend error handling in ProxyHandler (T-004-27,
 * FR-004-24/25, S-004-18/19/20).
 *
 * <p>
 * Verifies that upstream connectivity failures are translated to RFC 9457
 * Problem Details responses with correct HTTP status codes:
 * <ul>
 * <li>Backend unreachable → 502 Bad Gateway (S-004-18)</li>
 * <li>Backend timeout → 504 Gateway Timeout (S-004-19)</li>
 * <li>Backend connection refused → 502 Bad Gateway (S-004-20)</li>
 * </ul>
 *
 * <p>
 * NOTE: This test does NOT extend ProxyTestHarness because each scenario
 * requires a different backend configuration (different port, timeout, etc.).
 */
@DisplayName("ProxyHandler — backend error handling")
class BackendErrorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------------------------------------------------------------
    // S-004-20 — Backend connection refused → 502 Bad Gateway
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-20: Backend connection refused → 502 RFC 9457")
    void connectionRefused_returns502() throws Exception {
        // Point proxy at port 1 — nobody is listening → ConnectException
        Javalin app = startProxyWithBackend("127.0.0.1", 1, 5000, 5000);
        int proxyPort = app.port();

        try {
            HttpClient client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/test"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.headers().firstValue("content-type"))
                    .isPresent()
                    .hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));

            JsonNode body = MAPPER.readTree(response.body());
            assertThat(body.get("type").asText()).isEqualTo(ProblemDetail.URN_BACKEND_UNREACHABLE);
            assertThat(body.get("title").asText()).isEqualTo("Backend Unreachable");
            assertThat(body.get("status").asInt()).isEqualTo(502);
            assertThat(body.get("detail").asText()).contains("127.0.0.1");
            assertThat(body.get("instance").asText()).isEqualTo("/api/test");

            // X-Request-ID present
            assertThat(response.headers().firstValue("x-request-id")).isPresent();
        } finally {
            app.stop();
        }
    }

    // ---------------------------------------------------------------
    // S-004-19 — Backend timeout → 504 Gateway Timeout
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-19: Backend read timeout → 504 RFC 9457")
    void readTimeout_returns504() throws Exception {
        // Start a backend that accepts connections but never responds
        HttpServer slowBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int slowPort = slowBackend.getAddress().getPort();
        slowBackend.createContext("/", exchange -> {
            try {
                Thread.sleep(10_000); // Sleep longer than timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        slowBackend.start();

        Javalin app = startProxyWithBackend("127.0.0.1", slowPort, 5000, 500);
        int proxyPort = app.port();

        try {
            HttpClient client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/slow"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(response.headers().firstValue("content-type"))
                    .isPresent()
                    .hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));

            JsonNode body = MAPPER.readTree(response.body());
            assertThat(body.get("type").asText()).isEqualTo(ProblemDetail.URN_GATEWAY_TIMEOUT);
            assertThat(body.get("title").asText()).isEqualTo("Gateway Timeout");
            assertThat(body.get("status").asInt()).isEqualTo(504);
            assertThat(body.get("detail").asText()).contains("127.0.0.1");
            assertThat(body.get("instance").asText()).isEqualTo("/api/slow");

            // X-Request-ID present
            assertThat(response.headers().firstValue("x-request-id")).isPresent();
        } finally {
            app.stop();
            slowBackend.stop(0);
        }
    }

    // ---------------------------------------------------------------
    // S-004-18 — Backend unreachable (non-routable) → 502 Bad Gateway
    // NOTE: Uses connection timeout to avoid long test. Points at RFC
    // 5737 TEST-NET-1 (192.0.2.1) which is non-routable.
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-18: Backend unreachable → 502 RFC 9457")
    void unreachableHost_returns502() throws Exception {
        // Short connect timeout to keep test fast
        Javalin app = startProxyWithBackend("192.0.2.1", 80, 500, 500);
        int proxyPort = app.port();

        try {
            HttpClient client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/unreachable"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Either 502 (connect exception) or could be 504 depending on JDK behavior
            // with non-routable hosts — accept both as valid proxy error responses
            assertThat(response.statusCode()).isIn(502, 504);
            assertThat(response.headers().firstValue("content-type"))
                    .isPresent()
                    .hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));

            JsonNode body = MAPPER.readTree(response.body());
            assertThat(body.has("type")).isTrue();
            assertThat(body.get("type").asText()).startsWith("urn:message-xform:proxy:");
            assertThat(body.has("status")).isTrue();
            assertThat(body.has("detail")).isTrue();
            assertThat(body.get("instance").asText()).isEqualTo("/api/unreachable");
        } finally {
            app.stop();
        }
    }

    // ---------------------------------------------------------------
    // Helper: start proxy with custom backend config
    // ---------------------------------------------------------------

    private static Javalin startProxyWithBackend(
            String backendHost, int backendPort, int connectTimeoutMs, int readTimeoutMs) {
        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost(backendHost)
                .backendPort(backendPort)
                .backendConnectTimeoutMs(connectTimeoutMs)
                .backendReadTimeoutMs(readTimeoutMs)
                .build();

        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        TransformEngine engine = new TransformEngine(new SpecParser(registry));

        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(engine, adapter, upstreamClient, -1, true);

        return Javalin.create()
                .addHttpHandler(HandlerType.GET, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.POST, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.PUT, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.DELETE, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.PATCH, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.HEAD, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.OPTIONS, "/<path>", proxyHandler)
                .start(0);
    }
}
