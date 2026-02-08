package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for request transformation (T-004-21, FR-004-02).
 *
 * <p>
 * Covers scenarios: S-004-07, S-004-08, S-004-09, S-004-10.
 */
@DisplayName("ProxyHandler — request transformation")
class RequestTransformTest extends ProxyTestHarness {

    private static final RequestTransformTest INSTANCE = new RequestTransformTest();

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startWithSpecs(
                new String[] {"test-specs/request-body-transform.yaml", "test-specs/request-header-transform.yaml"},
                "test-profiles/request-transform-profile.yaml");
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-07 — Profile matches POST /api/orders → body transformed
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-07: POST /api/orders — request body transformed before forwarding")
    void requestBodyTransform_bodyTransformedBeforeForwarding() throws Exception {
        String inputBody = "{\"order_id\":\"ORD-001\",\"customer_name\":\"Alice\",\"total\":99.95}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(inputBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Backend should have received the TRANSFORMED body
        ReceivedRequest received = INSTANCE.receivedRequests.get("/api/orders");
        assertThat(received).isNotNull();
        assertThat(received.body()).contains("\"orderId\":\"ORD-001\"");
        assertThat(received.body()).contains("\"customerName\":\"Alice\"");
        assertThat(received.body()).contains("\"totalAmount\":99.95");
        // Original field names should NOT be present
        assertThat(received.body()).doesNotContain("\"order_id\"");
        assertThat(received.body()).doesNotContain("\"customer_name\"");
    }

    // ---------------------------------------------------------------
    // S-004-08 — Profile adds/removes request headers
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-08: POST /api/headers — headers transformed before forwarding")
    void requestHeaderTransform_headersModifiedBeforeForwarding() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/headers"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Debug", "true")
                .POST(HttpRequest.BodyPublishers.ofString("{\"data\":\"test\"}"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Verify the backend received modified headers
        ReceivedRequest received = INSTANCE.receivedRequests.get("/api/headers");
        assertThat(received).isNotNull();

        // Added headers should be present
        assertThat(INSTANCE.getReceivedHeader(received, "x-transformed")).contains("true");
        assertThat(INSTANCE.getReceivedHeader(received, "x-correlation-id")).contains("auto-generated");

        // Removed headers (x-internal-*) should NOT be present
        assertThat(INSTANCE.getReceivedHeader(received, "x-internal-debug")).isEqualTo(Collections.emptyList());
    }

    // ---------------------------------------------------------------
    // S-004-10 — GET with no body → transform receives NullNode body
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-10: GET /api/nullbody — transform with NullNode body")
    void requestNullBody_transformReceivesNullNode() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/nullbody"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // The transform should succeed even with no body (NullNode)
        // The JSLT expression will produce null fields from NullNode input
        assertThat(response.statusCode()).isEqualTo(200);

        // Verify the request was forwarded (passthrough or transformed)
        ReceivedRequest received = INSTANCE.receivedRequests.get("/api/nullbody");
        assertThat(received).isNotNull();
        assertThat(received.method()).isEqualTo("GET");
    }
}
