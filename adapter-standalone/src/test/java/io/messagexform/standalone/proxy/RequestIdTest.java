package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for X-Request-ID generation and echo (T-004-25, FR-004-38).
 *
 * <p>
 * Covers scenarios: S-004-71, S-004-72, S-004-73.
 */
@DisplayName("ProxyHandler — X-Request-ID")
class RequestIdTest extends ProxyTestHarness {

    private static final RequestIdTest INSTANCE = new RequestIdTest();

    /** UUID v4 pattern (case-insensitive). */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startWithSpecs(
                new String[] {"test-specs/bad-transform.yaml"}, "test-profiles/request-id-error-profile.yaml");

        // Normal backend handler for passthrough
        INSTANCE.registerBackendHandler("/api/rid-test", 200, "application/json", "{\"ok\":true}");
        INSTANCE.registerBackendHandler("/api/rid-echo", 200, "application/json", "{\"ok\":true}");
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-71 — No X-Request-ID → generated UUID in response
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-71: No X-Request-ID in request → response includes generated UUID")
    void noRequestId_generatedUuidInResponse() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/rid-test"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Response must include X-Request-ID header with a UUID value
        assertThat(response.headers().firstValue("x-request-id")).isPresent().hasValueSatisfying(id -> assertThat(id)
                .matches(UUID_PATTERN));
    }

    // ---------------------------------------------------------------
    // S-004-72 — X-Request-ID present → echoed back in response
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-72: X-Request-ID: abc-123 → echoed in response")
    void requestId_echoedInResponse() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/rid-echo"))
                .header("X-Request-ID", "abc-123")
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Response must echo the same X-Request-ID
        assertThat(response.headers().firstValue("x-request-id")).isPresent().hasValue("abc-123");
    }

    // ---------------------------------------------------------------
    // S-004-73 — Error response still includes X-Request-ID
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-73: Error response includes X-Request-ID")
    void errorResponse_includesRequestId() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/rid-error"))
                .header("Content-Type", "application/json")
                .header("X-Request-ID", "err-trace-456")
                .POST(HttpRequest.BodyPublishers.ofString("{\"data\":\"test\"}"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Error response (502)
        assertThat(response.statusCode()).isEqualTo(502);

        // X-Request-ID must still be present in error responses
        assertThat(response.headers().firstValue("x-request-id")).isPresent().hasValue("err-trace-456");
    }
}
