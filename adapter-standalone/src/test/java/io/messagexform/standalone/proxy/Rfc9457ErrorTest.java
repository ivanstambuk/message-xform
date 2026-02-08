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
 * Integration test verifying RFC 9457 Problem Details response structure
 * (T-004-26, FR-004-23).
 *
 * <p>
 * Validates that ALL proxy error responses conform to RFC 9457 with:
 * <ul>
 * <li>{@code type} — URN identifying the error category</li>
 * <li>{@code title} — short human-readable title</li>
 * <li>{@code status} — HTTP status code as integer</li>
 * <li>{@code detail} — human-readable description</li>
 * <li>{@code Content-Type: application/problem+json}</li>
 * <li>{@code X-Request-ID} header present in error responses</li>
 * </ul>
 *
 * <p>
 * Scenarios: S-004-09, S-004-14.
 */
@DisplayName("RFC 9457 — Problem Details error responses")
class Rfc9457ErrorTest extends ProxyTestHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Rfc9457ErrorTest INSTANCE = new Rfc9457ErrorTest();

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startWithSpecs(
                new String[] {"test-specs/bad-transform.yaml"}, "test-profiles/rfc9457-error-profile.yaml");

        // Backend for response error path (request passthrough + response ERROR)
        INSTANCE.registerBackendHandler(
                "/api/rfc9457/resp-error", 200, "application/json", "{\"data\":\"triggers-error\"}");
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-09 — Request transform error → 502 RFC 9457
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-09: Request transform error → 502 with RFC 9457 body")
    void requestTransformError_produces502WithProblemDetails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/rfc9457/req-error"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"data\"}"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // --- HTTP status ---
        assertThat(response.statusCode()).isEqualTo(502);

        // --- Content-Type ---
        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/problem+json"));

        // --- RFC 9457 body structure ---
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.has("type")).as("RFC 9457 'type' field").isTrue();
        assertThat(body.get("type").asText()).startsWith("urn:");
        assertThat(body.has("title")).as("RFC 9457 'title' field").isTrue();
        assertThat(body.get("title").asText()).isNotEmpty();
        assertThat(body.has("status")).as("RFC 9457 'status' field").isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(502);
        assertThat(body.has("detail")).as("RFC 9457 'detail' field").isTrue();
        assertThat(body.get("detail").asText()).isNotEmpty();
    }

    // ---------------------------------------------------------------
    // S-004-14 — Response transform error → 502 RFC 9457
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-14: Response transform error → 502 with RFC 9457 body")
    void responseTransformError_produces502WithProblemDetails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/rfc9457/resp-error"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // --- HTTP status ---
        assertThat(response.statusCode()).isEqualTo(502);

        // --- Content-Type ---
        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/problem+json"));

        // --- RFC 9457 body structure ---
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.has("type")).as("RFC 9457 'type' field").isTrue();
        assertThat(body.get("type").asText()).startsWith("urn:");
        assertThat(body.has("title")).as("RFC 9457 'title' field").isTrue();
        assertThat(body.has("status")).as("RFC 9457 'status' field").isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(502);
        assertThat(body.has("detail")).as("RFC 9457 'detail' field").isTrue();
    }

    // ---------------------------------------------------------------
    // X-Request-ID presence in error responses
    // ---------------------------------------------------------------

    @Test
    @DisplayName("X-Request-ID generated in error response (no inbound header)")
    void errorResponse_generatesRequestId() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/rfc9457/req-error"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"data\"}"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("x-request-id")).isPresent().hasValueSatisfying(id -> assertThat(id)
                .isNotEmpty());
    }

    @Test
    @DisplayName("X-Request-ID echoed in error response (inbound header present)")
    void errorResponse_echoesRequestId() throws Exception {
        String customRequestId = "rfc9457-test-id-42";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/rfc9457/req-error"))
                .header("Content-Type", "application/json")
                .header("X-Request-ID", customRequestId)
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"data\"}"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("x-request-id")).isPresent().hasValue(customRequestId);
    }

    // ---------------------------------------------------------------
    // instance field in RFC 9457 body
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RFC 9457 'instance' field contains the request path")
    void errorResponse_instanceContainsRequestPath() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/rfc9457/req-error"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"data\"}"))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.has("instance")).as("RFC 9457 'instance' field").isTrue();
        assertThat(body.get("instance").asText()).isEqualTo("/api/rfc9457/req-error");
    }
}
