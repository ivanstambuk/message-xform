package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.messagexform.standalone.config.ProxyConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for hop-by-hop header stripping (T-004-12, FR-004-04).
 *
 * <p>
 * Per RFC 7230 §6.1, the following hop-by-hop headers MUST be stripped
 * in both request and response directions:
 * {@code Connection}, {@code Transfer-Encoding}, {@code Keep-Alive},
 * {@code Proxy-Authenticate}, {@code Proxy-Authorization}, {@code TE},
 * {@code Trailer}, {@code Upgrade}.
 */
@DisplayName("UpstreamClient — hop-by-hop header stripping")
class HopByHopHeaderTest {

    private static HttpServer mockBackend;
    private static int backendPort;
    private static UpstreamClient client;

    /** Captures request headers received by the mock backend. */
    private static final AtomicReference<Map<String, String>> capturedRequestHeaders = new AtomicReference<>();

    @BeforeAll
    static void startMockBackend() throws IOException {
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();

        mockBackend.createContext("/api/test", exchange -> {
            // Capture request headers (lowercase)
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(), values.getFirst());
                }
            });
            capturedRequestHeaders.set(headers);

            // Send response with hop-by-hop headers included
            String body = "{\"ok\":true}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Custom", "preserved");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.getResponseHeaders().set("Keep-Alive", "timeout=5");
            exchange.getResponseHeaders().set("Proxy-Authenticate", "Basic");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
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

    // --- Request direction: hop-by-hop headers stripped before forwarding ---

    @Test
    @DisplayName("Request: hop-by-hop headers stripped before forwarding to backend")
    void request_hopByHopHeadersStripped() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("authorization", "Bearer token");
        // Hop-by-hop headers that MUST be stripped
        headers.put("connection", "keep-alive");
        headers.put("transfer-encoding", "chunked");
        headers.put("keep-alive", "timeout=5");
        headers.put("proxy-authenticate", "Basic");
        headers.put("proxy-authorization", "Basic dXNlcjpwYXNz");
        headers.put("te", "trailers");
        headers.put("trailer", "X-Checksum");
        headers.put("upgrade", "websocket");

        client.forward("POST", "/api/test", "{\"data\":true}", headers);

        Map<String, String> received = capturedRequestHeaders.get();

        // Hop-by-hop headers MUST NOT be present at the backend
        assertThat(received).doesNotContainKey("connection");
        assertThat(received).doesNotContainKey("transfer-encoding");
        assertThat(received).doesNotContainKey("keep-alive");
        assertThat(received).doesNotContainKey("proxy-authenticate");
        assertThat(received).doesNotContainKey("proxy-authorization");
        assertThat(received).doesNotContainKey("te");
        assertThat(received).doesNotContainKey("trailer");
        assertThat(received).doesNotContainKey("upgrade");
    }

    @Test
    @DisplayName("Request: non-hop-by-hop headers preserved")
    void request_nonHopByHopHeadersPreserved() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("authorization", "Bearer my-token");
        headers.put("x-custom-header", "keep-this");
        headers.put("accept", "application/json");
        // Include a hop-by-hop to verify it's stripped without affecting others
        headers.put("connection", "keep-alive");

        client.forward("POST", "/api/test", "{}", headers);

        Map<String, String> received = capturedRequestHeaders.get();

        // Non-hop-by-hop headers MUST be forwarded
        assertThat(received).containsEntry("authorization", "Bearer my-token");
        assertThat(received).containsEntry("x-custom-header", "keep-this");
        assertThat(received).containsEntry("accept", "application/json");
    }

    // --- Response direction: hop-by-hop headers stripped ---

    @Test
    @DisplayName("Response: hop-by-hop headers stripped in response")
    void response_hopByHopHeadersStripped() throws Exception {
        UpstreamResponse response = client.forward("GET", "/api/test", null, null);

        // The mock backend sends Connection, Keep-Alive, Proxy-Authenticate
        // These MUST be stripped from the response headers
        assertThat(response.headers()).doesNotContainKey("connection");
        assertThat(response.headers()).doesNotContainKey("keep-alive");
        assertThat(response.headers()).doesNotContainKey("proxy-authenticate");
    }

    @Test
    @DisplayName("Response: non-hop-by-hop headers preserved")
    void response_nonHopByHopHeadersPreserved() throws Exception {
        UpstreamResponse response = client.forward("GET", "/api/test", null, null);

        // Non-hop-by-hop headers MUST be preserved
        assertThat(response.headers()).containsEntry("content-type", "application/json");
        assertThat(response.headers()).containsEntry("x-custom", "preserved");
    }
}
