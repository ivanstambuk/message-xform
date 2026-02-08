package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for non-JSON body rejection (T-004-28, FR-004-26,
 * S-004-21/55).
 *
 * <p>
 * Verifies that when a profile matches a route by path/method but the
 * request body is not valid JSON, the proxy returns {@code 400 Bad Request}
 * with an RFC 9457 Problem Details body. Routes with no matching profile
 * pass through without body parsing.
 */
@DisplayName("ProxyHandler — non-JSON body rejection")
class NonJsonBodyTest extends ProxyTestHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final NonJsonBodyTest INSTANCE = new NonJsonBodyTest();

    @BeforeAll
    static void startInfrastructure() throws Exception {
        // Use request-body-transform spec which matches POST /api/orders
        INSTANCE.startWithSpecs(
                new String[] {"test-specs/request-body-transform.yaml"}, "test-profiles/non-json-body-profile.yaml");

        // Backend handler (for passthrough requests only)
        INSTANCE.registerBackendHandler("/api/passthrough", 200, "text/plain", "OK");
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-55 — Non-JSON content type on profile-matched route → 400
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-55: POST with text/xml on matched route → 400 RFC 9457")
    void nonJsonContentType_matchedRoute_returns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/orders"))
                .header("Content-Type", "text/xml")
                .POST(HttpRequest.BodyPublishers.ofString("<order><id>1</id></order>"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/problem+json"));

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("type").asText()).isEqualTo(ProblemDetail.URN_BAD_REQUEST);
        assertThat(body.get("title").asText()).isEqualTo("Bad Request");
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("detail").asText()).isNotEmpty();
        assertThat(body.get("instance").asText()).isEqualTo("/api/orders");
    }

    // ---------------------------------------------------------------
    // S-004-21 — Malformed JSON on profile-matched route → 400
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-21: POST with malformed JSON on matched route → 400 RFC 9457")
    void malformedJson_matchedRoute_returns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{invalid json!!!"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/problem+json"));

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("type").asText()).isEqualTo(ProblemDetail.URN_BAD_REQUEST);
        assertThat(body.get("status").asInt()).isEqualTo(400);
    }

    // ---------------------------------------------------------------
    // No matching profile → passthrough regardless of content type
    // ---------------------------------------------------------------

    @Test
    @DisplayName("No matching profile → passthrough with any content type")
    void noMatchingProfile_passthrough_anyContentType() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/passthrough"))
                .header("Content-Type", "text/xml")
                .POST(HttpRequest.BodyPublishers.ofString("<data>not json</data>"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Passthrough — non-JSON body is not parsed, request is forwarded as-is
        assertThat(response.statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------
    // X-Request-ID present in 400 error response
    // ---------------------------------------------------------------

    @Test
    @DisplayName("X-Request-ID present in 400 Bad Request response")
    void badRequest_hasRequestId() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/orders"))
                .header("Content-Type", "application/json")
                .header("X-Request-ID", "bad-json-test-id")
                .POST(HttpRequest.BodyPublishers.ofString("{broken"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("x-request-id")).isPresent().hasValue("bad-json-test-id");
    }
}
