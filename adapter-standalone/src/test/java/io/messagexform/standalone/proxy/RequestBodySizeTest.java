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
 * Integration test verifying request body size enforcement
 * (T-004-29, FR-004-13, S-004-22).
 *
 * <p>
 * Validates that requests exceeding {@code proxy.max-body-bytes} are rejected
 * with {@code 413 Payload Too Large} and an RFC 9457 Problem Details body,
 * while requests within the limit are accepted and forwarded.
 */
@DisplayName("Request body size enforcement — proxy.max-body-bytes")
class RequestBodySizeTest extends ProxyTestHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RequestBodySizeTest INSTANCE = new RequestBodySizeTest();

    /** Set limit to 1 KB for easy testing. */
    private static final int MAX_BODY_BYTES = 1024;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startPassthroughWithMaxBodyBytes(MAX_BODY_BYTES);
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-22 — Body within limit → accepted and forwarded
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Request body within limit → 200 OK (forwarded to backend)")
    void requestWithinLimit_isForwarded() throws Exception {
        String smallBody = "{\"data\":\"" + "x".repeat(100) + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/size/ok"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(smallBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // Verify backend received the request
        assertThat(INSTANCE.receivedRequests).containsKey("/api/size/ok");
    }

    // ---------------------------------------------------------------
    // S-004-22 — Body exceeding limit → 413 Payload Too Large
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Request body exceeding limit → 413 with RFC 9457 body")
    void requestExceedingLimit_returns413() throws Exception {
        // Create a body larger than MAX_BODY_BYTES (1 KB)
        String largeBody = "{\"data\":\"" + "x".repeat(MAX_BODY_BYTES + 500) + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/size/too-large"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(largeBody))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // --- HTTP status ---
        assertThat(response.statusCode()).isEqualTo(413);

        // --- Content-Type ---
        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/problem+json"));

        // --- RFC 9457 body structure ---
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("type").asText()).isEqualTo("urn:message-xform:proxy:body-too-large");
        assertThat(body.get("title").asText()).isEqualTo("Payload Too Large");
        assertThat(body.get("status").asInt()).isEqualTo(413);
        assertThat(body.has("detail")).isTrue();

        // --- NOT forwarded to backend ---
        assertThat(INSTANCE.receivedRequests).doesNotContainKey("/api/size/too-large");
    }

    // ---------------------------------------------------------------
    // Chunked request exceeding limit → rejected
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Chunked request exceeding limit → 413 with RFC 9457 body")
    void chunkedRequestExceedingLimit_returns413() throws Exception {
        // Use BodyPublishers.ofInputStream to force chunked encoding
        byte[] largePayload = ("{\"data\":\"" + "x".repeat(MAX_BODY_BYTES + 500) + "\"}").getBytes();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/size/chunked"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArrays(java.util.List.of(largePayload)))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);

        // RFC 9457 body
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("type").asText()).isEqualTo("urn:message-xform:proxy:body-too-large");
        assertThat(body.get("status").asInt()).isEqualTo(413);
    }

    // ---------------------------------------------------------------
    // Custom limit via proxy.max-body-bytes
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Custom max-body-bytes = 1024 rejects 1025-byte body")
    void customLimit_rejectsBoundaryBody() throws Exception {
        // Body exactly exceeding boundary: 1025 bytes total (exceeds 1024)
        // JSON wrapper {"d":""} is 8 bytes, so we need 1017 chars to make 1025 bytes
        // total
        String body = "{\"d\":\"" + "a".repeat(1017) + "\"}";
        assertThat(body.getBytes().length).isEqualTo(1025);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/size/boundary"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
    }
}
