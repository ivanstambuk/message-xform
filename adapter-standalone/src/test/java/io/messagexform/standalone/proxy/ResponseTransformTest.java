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
 * Integration test for response transformation (T-004-22, FR-004-03).
 *
 * <p>
 * Covers scenarios: S-004-11, S-004-12.
 */
@DisplayName("ProxyHandler — response transformation")
class ResponseTransformTest extends ProxyTestHarness {

    private static final ResponseTransformTest INSTANCE = new ResponseTransformTest();

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startWithSpecs(
                new String[] {"test-specs/response-body-transform.yaml", "test-specs/response-header-transform.yaml"},
                "test-profiles/response-transform-profile.yaml");

        // Register backend handlers with predictable JSON responses
        INSTANCE.registerBackendHandler("/api/items", 200, "application/json", "{\"name\":\"Widget\",\"value\":42}");

        INSTANCE.registerBackendHandler("/api/headers", 200, "application/json", "{\"data\":\"test\"}");
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-11 — Response body transformed before returning to client
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-11: GET /api/items — response body wrapped in envelope")
    void responseBodyTransform_wrappedInEnvelope() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/items"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // Response body should be the transformed envelope
        assertThat(response.body()).contains("\"success\":true");
        assertThat(response.body()).contains("\"data\":");
        assertThat(response.body()).contains("\"name\":\"Widget\"");
        assertThat(response.body()).contains("\"value\":42");
    }

    // ---------------------------------------------------------------
    // S-004-12 — Response headers modified before returning to client
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-12: GET /api/headers — response headers add/remove applied")
    void responseHeaderTransform_headersModified() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/headers"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Added headers should be present in client response
        assertThat(response.headers().firstValue("x-api-version")).isPresent().hasValue("2.0");
        assertThat(response.headers().firstValue("x-processed")).isPresent().hasValue("true");
    }
}
