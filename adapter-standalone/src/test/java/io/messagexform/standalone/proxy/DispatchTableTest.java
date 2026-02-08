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
 * Integration test for the TransformResult dispatch table (T-004-24,
 * FR-004-35).
 *
 * <p>
 * Covers scenarios: S-004-60, S-004-61, S-004-62, S-004-63, S-004-64.
 */
@DisplayName("ProxyHandler — TransformResult dispatch table")
class DispatchTableTest extends ProxyTestHarness {

    private static final DispatchTableTest INSTANCE = new DispatchTableTest();

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startWithSpecs(
                new String[] {
                    "test-specs/request-body-transform.yaml",
                    "test-specs/response-body-transform.yaml",
                    "test-specs/bad-transform.yaml"
                },
                "test-profiles/dispatch-table-profile.yaml");

        // Backend for SUCCESS paths
        INSTANCE.registerBackendHandler("/api/dispatch-success", 200, "application/json", "{\"result\":\"ok\"}");
        INSTANCE.registerBackendHandler(
                "/api/dispatch-resp-success", 200, "application/json", "{\"name\":\"DispatchItem\",\"value\":99}");

        // Backend for response error path
        INSTANCE.registerBackendHandler(
                "/api/dispatch-resp-error", 200, "application/json", "{\"data\":\"triggers-error\"}");

        // Backend for 204 No Content test (S-004-64)
        INSTANCE.registerBackendHandler("/api/dispatch-204", 204, "application/json", null);

        // Backend for passthrough path (no profile matches)
        INSTANCE.registerBackendHandler("/api/dispatch-passthrough", 200, "application/json", "{\"passthrough\":true}");
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-60 — REQUEST SUCCESS → transformed message forwarded to backend
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-60: REQUEST SUCCESS → backend receives transformed message")
    void requestSuccess_backendReceivesTransformed() throws Exception {
        String inputBody = "{\"order_id\":\"DISP-1\",\"customer_name\":\"Dispatch\",\"total\":10.0}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/dispatch-success"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(inputBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Backend received the TRANSFORMED body
        ReceivedRequest received = INSTANCE.receivedRequests.get("/api/dispatch-success");
        assertThat(received).isNotNull();
        assertThat(received.body()).contains("\"orderId\":\"DISP-1\"");
        assertThat(received.body()).doesNotContain("\"order_id\"");
    }

    // ---------------------------------------------------------------
    // S-004-61 — RESPONSE SUCCESS → applyChanges called → client gets transformed
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-61: RESPONSE SUCCESS → client receives transformed response")
    void responseSuccess_clientReceivesTransformed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/dispatch-resp-success"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"success\":true");
        assertThat(response.body()).contains("\"name\":\"DispatchItem\"");
    }

    // ---------------------------------------------------------------
    // S-004-62 — REQUEST ERROR → client receives error, NOT forwarded
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-62: REQUEST ERROR → 502 error response, NOT forwarded to backend")
    void requestError_clientReceivesError_notForwarded() throws Exception {
        INSTANCE.receivedRequests.clear();
        String inputBody = "{\"data\":\"trigger-error\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/dispatch-req-error"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(inputBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Error response with problem+json content type
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/problem+json"));

        // Request should NOT have been forwarded to backend
        assertThat(INSTANCE.receivedRequests.get("/api/dispatch-req-error")).isNull();
    }

    // ---------------------------------------------------------------
    // S-004-63 — REQUEST PASSTHROUGH → raw bytes forwarded
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-63: REQUEST PASSTHROUGH → raw request forwarded unmodified")
    void requestPassthrough_rawForwarded() throws Exception {
        String rawBody = "{\"passthrough\":true}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/dispatch-passthrough"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rawBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Backend received the raw unmodified body
        ReceivedRequest received = INSTANCE.receivedRequests.get("/api/dispatch-passthrough");
        assertThat(received).isNotNull();
        assertThat(received.body()).isEqualTo(rawBody);
    }

    // ---------------------------------------------------------------
    // S-004-64 — Backend 204 No Content → NullNode body → profile matching
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-64: Backend returns 204 → NullNode body → no crash")
    void backend204_nullBody_noError() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/dispatch-204"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 204 is passed through (no profile matches /api/dispatch-204)
        assertThat(response.statusCode()).isEqualTo(204);
    }

    // ---------------------------------------------------------------
    // S-004-14 — RESPONSE ERROR → client receives error response
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-14: RESPONSE ERROR → 502 error response to client")
    void responseError_clientReceivesError() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/dispatch-resp-error"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Response transform error → 502
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/problem+json"));
    }
}
