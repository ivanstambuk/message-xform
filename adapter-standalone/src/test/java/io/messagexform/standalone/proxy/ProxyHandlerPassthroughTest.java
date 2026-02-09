package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import io.messagexform.standalone.config.ProxyConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration test for {@link ProxyHandler} — passthrough cycle (T-004-20).
 *
 * <p>
 * Architecture:
 *
 * <pre>
 *   TestClient ← HTTP → Javalin (ProxyHandler) ← HTTP → Mock Backend
 * </pre>
 *
 * <p>
 * No specs or profiles are loaded — the {@link TransformEngine} returns
 * {@code PASSTHROUGH} for all requests, so all traffic flows through
 * unmodified.
 *
 * <p>
 * Covers scenarios: S-004-01, S-004-02, S-004-03, S-004-04, S-004-05,
 * S-004-06.
 */
@DisplayName("ProxyHandler — passthrough cycle")
class ProxyHandlerPassthroughTest {

    private static HttpServer mockBackend;
    private static int backendPort;
    private static Javalin app;
    private static int proxyPort;
    private static HttpClient testClient;

    /**
     * Records the last request received by the mock backend for each path,
     * allowing assertions on forwarded method, headers, path, and body.
     */
    private static final Map<String, ReceivedRequest> receivedRequests = new ConcurrentHashMap<>();

    record ReceivedRequest(String method, String path, String query, Map<String, List<String>> headers, String body) {
    }

    @BeforeAll
    static void startInfrastructure() throws IOException {
        // --- Mock Backend ---
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();

        // Echo endpoint: returns JSON with method, path, and body echoed back
        mockBackend.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            receivedRequests.put(
                    path + (query != null ? "?" + query : ""),
                    new ReceivedRequest(
                            exchange.getRequestMethod(), path, query, exchange.getRequestHeaders(), requestBody));

            String method = exchange.getRequestMethod();
            if ("HEAD".equalsIgnoreCase(method)) {
                // HEAD requests must not include a body
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("X-Backend-Method", method);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            String responseBody = String.format(
                    "{\"method\":\"%s\",\"path\":\"%s\",\"query\":%s,\"body\":%s}",
                    method,
                    path,
                    query != null ? "\"" + query + "\"" : "null",
                    requestBody.isEmpty() ? "null" : requestBody);

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Backend-Method", method);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockBackend.start();

        // --- Proxy (Javalin + ProxyHandler) ---
        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .backendConnectTimeoutMs(5000)
                .backendReadTimeoutMs(5000)
                .build();

        // TransformEngine with empty registry → all requests return PASSTHROUGH
        EngineRegistry registry = new EngineRegistry();
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);
        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(engine, adapter, upstreamClient, -1, true);

        app = Javalin.create()
                .addHttpHandler(HandlerType.GET, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.POST, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.PUT, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.DELETE, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.PATCH, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.HEAD, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.OPTIONS, "/<path>", proxyHandler)
                .start(0);

        proxyPort = app.port();

        testClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (app != null) {
            app.stop();
        }
        if (mockBackend != null) {
            mockBackend.stop(0);
        }
    }

    // ---------------------------------------------------------------
    // S-004-01 — Passthrough GET: forwarded unmodified, response returned
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-01: GET /api/users with no matching profile → passthrough")
    void passthroughGet_forwardedUnmodified() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/users"))
                .GET()
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"method\":\"GET\"");
        assertThat(response.body()).contains("\"path\":\"/api/users\"");
    }

    // ---------------------------------------------------------------
    // S-004-02 — Passthrough POST with JSON body forwarded intact
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-02: POST /api/data with JSON body → body forwarded intact")
    void passthroughPost_bodyIntact() throws Exception {
        String jsonBody = "{\"key\":\"value\",\"count\":42}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/data"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // The mock backend echoes back the body — verify it matches
        assertThat(response.body()).contains("\"key\":\"value\"");
        assertThat(response.body()).contains("\"count\":42");
    }

    // ---------------------------------------------------------------
    // S-004-03 — All seven HTTP methods proxied correctly
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "S-004-03: {0} method proxied correctly")
    @ValueSource(strings = { "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS" })
    @DisplayName("S-004-03: HTTP method proxied correctly")
    void allMethods_proxiedCorrectly(String method) throws Exception {
        String body = "GET".equals(method) || "OPTIONS".equals(method) ? null : "{\"test\":true}";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/method-test"));

        if (body != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = testClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"method\":\"" + method + "\"");
    }

    @Test
    @DisplayName("S-004-03: HEAD method proxied correctly")
    void headMethod_proxiedCorrectly() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/method-test"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // HEAD response MUST have status 200 but no body
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
    }

    // ---------------------------------------------------------------
    // S-004-04 — Headers forwarded to backend (hop-by-hop stripped)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-04: Request headers forwarded to backend")
    void headers_forwardedToBackend() throws Exception {
        receivedRequests.clear();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/header-test"))
                .header("X-Custom-Header", "test-value-123")
                .header("Authorization", "Bearer my-token")
                .GET()
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Verify headers were received by backend
        ReceivedRequest received = receivedRequests.get("/api/header-test");
        assertThat(received).isNotNull();
        assertThat(received.headers()
                .getOrDefault(
                        "X-custom-header",
                        received.headers()
                                .getOrDefault(
                                        "X-Custom-Header",
                                        received.headers()
                                                .getOrDefault("x-custom-header", Collections.emptyList()))))
                .contains("test-value-123");
        assertThat(received.headers()
                .getOrDefault(
                        "Authorization",
                        received.headers().getOrDefault("authorization", Collections.emptyList())))
                .contains("Bearer my-token");
    }

    @Test
    @DisplayName("S-004-04: Response headers from backend returned to client")
    void responseHeaders_returnedToClient() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/users"))
                .GET()
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // Backend sets Content-Type and X-Backend-Method
        assertThat(response.headers().firstValue("content-type")).isPresent().hasValue("application/json");
        assertThat(response.headers().firstValue("x-backend-method"))
                .isPresent()
                .hasValue("GET");
    }

    // ---------------------------------------------------------------
    // S-004-05 — Query string forwarded intact
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-05: Query string forwarded intact")
    void queryString_forwardedIntact() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/users?page=2&size=10"))
                .GET()
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"query\":\"page=2&size=10\"");
    }

    // ---------------------------------------------------------------
    // S-004-06 — Full path forwarded to backend
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-06: Full nested path forwarded to backend")
    void fullPath_forwardedToBackend() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/v1/nested/resource/123"))
                .GET()
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"path\":\"/api/v1/nested/resource/123\"");
    }
}
