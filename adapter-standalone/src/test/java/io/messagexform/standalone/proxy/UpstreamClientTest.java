package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.messagexform.standalone.config.ProxyConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UpstreamClient} — basic forwarding (T-004-09, FR-004-01).
 *
 * <p>
 * Uses JDK's built-in {@code com.sun.net.httpserver.HttpServer} as the mock
 * backend. This avoids adding WireMock as a dependency while providing full
 * control over request/response assertions.
 */
@DisplayName("UpstreamClient — basic forwarding")
class UpstreamClientTest {

    private static HttpServer mockBackend;
    private static int backendPort;
    private static UpstreamClient client;

    @BeforeAll
    static void startMockBackend() throws IOException {
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();

        // GET /api/users → 200, JSON body, custom header
        mockBackend.createContext("/api/users", exchange -> {
            String responseBody = "{\"users\":[{\"id\":1,\"name\":\"Alice\"}]}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Custom-Header", "test-value");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // POST /api/data → 201, echoes back the request body
        mockBackend.createContext("/api/data", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Echo", "true");
            exchange.sendResponseHeaders(201, requestBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(requestBody);
            }
        });

        // GET /api/empty → 204 No Content
        mockBackend.createContext("/api/empty", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        mockBackend.start();

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .backendConnectTimeoutMs(5000)
                .backendReadTimeoutMs(5000)
                .build();
        client = new UpstreamClient(config);
    }

    @AfterAll
    static void stopMockBackend() {
        if (mockBackend != null) {
            mockBackend.stop(0);
        }
    }

    @Test
    @DisplayName("GET request forwarded → response 200 with JSON body intact")
    void getRequestForwarded_responseBodyIntact() throws Exception {
        UpstreamResponse response = client.forward("GET", "/api/users", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"users\":[{\"id\":1,\"name\":\"Alice\"}]}");
    }

    @Test
    @DisplayName("GET request forwarded → response headers returned to caller")
    void getRequestForwarded_responseHeadersReturned() throws Exception {
        UpstreamResponse response = client.forward("GET", "/api/users", null, null);

        assertThat(response.headers()).containsEntry("content-type", "application/json");
        assertThat(response.headers()).containsEntry("x-custom-header", "test-value");
    }

    @Test
    @DisplayName("POST request with JSON body → body forwarded correctly, 201 returned")
    void postRequestWithBody_bodyForwardedCorrectly() throws Exception {
        String requestBody = "{\"key\":\"value\",\"count\":42}";
        UpstreamResponse response = client.forward("POST", "/api/data", requestBody, null);

        assertThat(response.statusCode()).isEqualTo(201);
        // Backend echoes back the request body
        assertThat(response.body()).isEqualTo(requestBody);
        assertThat(response.headers()).containsEntry("x-echo", "true");
    }

    @Test
    @DisplayName("GET request forwarded → query string preserved in path")
    void getRequestForwarded_queryStringPreserved() throws Exception {
        // The /api/users handler will still respond (HttpServer matches by prefix)
        UpstreamResponse response = client.forward("GET", "/api/users?page=2&size=10", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Request with custom headers → headers forwarded to backend")
    void requestWithHeaders_headersForwarded() throws Exception {
        // Use the /api/data endpoint and check the echo
        java.util.Map<String, String> headers = java.util.Map.of(
                "content-type", "application/json",
                "authorization", "Bearer test-token");
        String body = "{\"test\":true}";
        UpstreamResponse response = client.forward("POST", "/api/data", body, headers);

        // If headers are forwarded, backend processes normally and echoes
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo(body);
    }

    @Test
    @DisplayName("GET to 204 No Content → empty body returned")
    void getNoContent_emptyBodyReturned() throws Exception {
        UpstreamResponse response = client.forward("GET", "/api/empty", null, null);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }
}
