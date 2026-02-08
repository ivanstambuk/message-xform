package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.messagexform.standalone.config.ProxyConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Content-Length recalculation (T-004-11, FR-004-34, S-004-53/54).
 *
 * <p>
 * Verifies that the proxy does NOT blindly copy the original Content-Length
 * header, but instead lets the JDK HttpClient set the correct value based on
 * the actual body bytes being sent.
 */
@DisplayName("UpstreamClient — Content-Length recalculation")
class ContentLengthTest {

    private static HttpServer mockBackend;
    private static int backendPort;
    private static UpstreamClient client;

    /** Captures the Content-Length header received by the mock backend. */
    private static final AtomicReference<String> capturedContentLength = new AtomicReference<>();

    /** Captures the full request body received by the mock backend. */
    private static final AtomicReference<byte[]> capturedRequestBody = new AtomicReference<>();

    @BeforeAll
    static void startMockBackend() throws IOException {
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();

        // Captures incoming Content-Length and body, echoes back
        mockBackend.createContext("/api/data", exchange -> {
            // Capture the Content-Length header as received by the backend
            String cl = exchange.getRequestHeaders().getFirst("Content-Length");
            capturedContentLength.set(cl);

            // Capture the actual body
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedRequestBody.set(body);

            // Echo the body back
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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

    @Test
    @DisplayName("Transformed body: Content-Length matches actual body size, not original")
    void transformedBody_contentLengthMatchesActualSize() throws Exception {
        // Simulate a scenario where the caller passes a "transformed" body that is
        // larger than an original 10-byte body. The Content-Length must reflect the
        // new body size (150 bytes), not the original (100 bytes).
        String originalBody = "{\"short\":true}"; // 14 bytes
        String transformedBody =
                "{\"long_key\":\"some longer value that makes this body larger than the original\"}"; // longer

        // If a caller (ProxyHandler) were to pass the wrong Content-Length header,
        // the JDK HttpClient must NOT blindly forward it. Our UpstreamClient filters
        // content-length from forwarded headers (isRestrictedHeader).
        Map<String, String> headers = Map.of(
                "content-type",
                "application/json",
                "content-length",
                String.valueOf(originalBody.length())); // WRONG length

        UpstreamResponse response = client.forward("POST", "/api/data", transformedBody, headers);

        assertThat(response.statusCode()).isEqualTo(200);

        // The backend MUST have received the correct Content-Length (matching the
        // transformed body), not the original Content-Length header
        int expectedLength = transformedBody.getBytes(StandardCharsets.UTF_8).length;
        assertThat(capturedContentLength.get()).isEqualTo(String.valueOf(expectedLength));

        // And the full body must arrive intact
        assertThat(new String(capturedRequestBody.get(), StandardCharsets.UTF_8))
                .isEqualTo(transformedBody);
    }

    @Test
    @DisplayName("Body with no Content-Length header: JDK HttpClient sets correct length")
    void noContentLengthHeader_jdkSetsCorrectLength() throws Exception {
        String body = "{\"data\":[1,2,3,4,5]}";
        int expectedLength = body.getBytes(StandardCharsets.UTF_8).length;

        // No explicit Content-Length in headers — JDK HttpClient should set it
        UpstreamResponse response = client.forward("POST", "/api/data", body, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(capturedContentLength.get()).isEqualTo(String.valueOf(expectedLength));
        assertThat(new String(capturedRequestBody.get(), StandardCharsets.UTF_8))
                .isEqualTo(body);
    }

    @Test
    @DisplayName("Empty body POST: Content-Length is 0")
    void emptyBody_contentLengthIsZero() throws Exception {
        UpstreamResponse response = client.forward("POST", "/api/data", "", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(capturedContentLength.get()).isEqualTo("0");
    }
}
