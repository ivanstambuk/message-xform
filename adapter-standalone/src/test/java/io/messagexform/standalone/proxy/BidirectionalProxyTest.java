package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for bidirectional transformation (T-004-23).
 *
 * <p>
 * Covers scenarios: S-004-15, S-004-16, S-004-17.
 */
@DisplayName("ProxyHandler — bidirectional transformation")
class BidirectionalProxyTest extends ProxyTestHarness {

    private static final BidirectionalProxyTest INSTANCE = new BidirectionalProxyTest();

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startWithSpecs(
                new String[] {"test-specs/request-body-transform.yaml", "test-specs/response-body-transform.yaml"},
                "test-profiles/bidirectional-profile.yaml");

        // Register a handler for /api/bidi that returns its own JSON
        INSTANCE.registerBackendHandler("/api/bidi", 200, "application/json", "{\"name\":\"Result\",\"value\":100}");

        // Handler for /api/req-only — returns plain JSON (no response transform)
        INSTANCE.registerBackendHandler("/api/req-only", 200, "application/json", "{\"status\":\"received\"}");

        // Handler for /api/resp-only — returns JSON that gets response-transformed
        INSTANCE.registerBackendHandler("/api/resp-only", 200, "application/json", "{\"name\":\"Raw\",\"value\":77}");
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-15 — Both request and response transformed
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-15: POST /api/bidi — request body transformed + response body transformed")
    void bidirectional_bothTransformed() throws Exception {
        String inputBody = "{\"order_id\":\"ORD-BIDI\",\"customer_name\":\"Bob\",\"total\":50.0}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/bidi"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(inputBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Request was transformed before forwarding to backend
        ReceivedRequest received = INSTANCE.receivedRequests.get("/api/bidi");
        assertThat(received).isNotNull();
        assertThat(received.body()).contains("\"orderId\":\"ORD-BIDI\"");
        assertThat(received.body()).contains("\"customerName\":\"Bob\"");

        // Response was also transformed (envelope wrapper)
        assertThat(response.body()).contains("\"success\":true");
        assertThat(response.body()).contains("\"data\":");
        assertThat(response.body()).contains("\"name\":\"Result\"");
    }

    // ---------------------------------------------------------------
    // S-004-16 — Request transformed + response passthrough
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-16: POST /api/req-only — request transformed, response passed through")
    void requestOnly_responsePassthrough() throws Exception {
        String inputBody = "{\"order_id\":\"ORD-REQ\",\"customer_name\":\"Charlie\",\"total\":30.0}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/req-only"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(inputBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Request was transformed
        ReceivedRequest received = INSTANCE.receivedRequests.get("/api/req-only");
        assertThat(received).isNotNull();
        assertThat(received.body()).contains("\"orderId\":\"ORD-REQ\"");

        // Response was NOT transformed (passthrough — raw backend response)
        assertThat(response.body()).contains("\"status\":\"received\"");
        assertThat(response.body()).doesNotContain("\"success\"");
    }

    // ---------------------------------------------------------------
    // S-004-17 — Request passthrough + response transformed
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-17: POST /api/resp-only — request passed through, response transformed")
    void responseOnly_requestPassthrough() throws Exception {
        String inputBody = "{\"raw_data\":\"hello\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/resp-only"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(inputBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Request was NOT transformed — raw body forwarded
        ReceivedRequest received = INSTANCE.receivedRequests.get("/api/resp-only");
        assertThat(received).isNotNull();
        assertThat(received.body()).contains("\"raw_data\":\"hello\"");

        // Response WAS transformed (envelope wrapper)
        assertThat(response.body()).contains("\"success\":true");
        assertThat(response.body()).contains("\"name\":\"Raw\"");
    }
}
